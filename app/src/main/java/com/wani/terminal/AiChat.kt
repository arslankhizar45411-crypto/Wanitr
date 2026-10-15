package com.wani.terminal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class Role { USER, ASSISTANT, TOOL, SYSTEM, ERROR }

data class ChatMessage(val role: Role, val text: String)

/**
 * AI assistant. OpenAI-compatible chat API (base URL, model aur key Settings se).
 * Tools:
 *  - run_command: Linux shell (proot) mein command. Approval ke baad (agar auto-approve off ho).
 *  - read_file:   /root/... (Linux) ya /sdcard/... (phone storage, permission ke saath).
 */
class AiChat(private val context: Context) {

    private val settings = AppSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableStateFlow(listOf(ChatMessage(Role.SYSTEM, WELCOME)))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var history = JSONArray()
    private var job: Job? = null

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _busy.value) return
        append(ChatMessage(Role.USER, text))
        history.put(JSONObject().put("role", "user").put("content", text))
        _busy.value = true
        job = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(ChatMessage(Role.ERROR, e.message ?: e.toString()))
            } finally {
                _busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _busy.value = false
        append(ChatMessage(Role.SYSTEM, "Stopped."))
    }

    fun clear() {
        if (_busy.value) return
        history = JSONArray()
        _messages.value = listOf(ChatMessage(Role.SYSTEM, WELCOME))
    }

    private fun append(m: ChatMessage) {
        _messages.update { it + m }
    }

    private suspend fun runLoop() {
        if (settings.aiApiKey.isBlank()) {
            throw IllegalStateException("No API key yet. Open Settings and add your AI API key.")
        }
        for (step in 0 until MAX_STEPS) {
            val message = requestCompletion()
            val content = if (message.isNull("content")) "" else message.optString("content")
            val calls = message.optJSONArray("tool_calls")

            history.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", if (content.isEmpty()) JSONObject.NULL else content)
                    .apply { if (calls != null) put("tool_calls", calls) }
            )
            if (content.isNotBlank()) append(ChatMessage(Role.ASSISTANT, content.trim()))

            if (calls == null || calls.length() == 0) return

            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                    .getOrElse { JSONObject() }
                val result = execute(fn.optString("name"), args)
                history.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", result)
                )
            }
        }
        append(ChatMessage(Role.SYSTEM, "Paused after $MAX_STEPS steps. Send a message to continue."))
    }

    private fun requestCompletion(): JSONObject {
        val body = JSONObject()
            .put("model", settings.aiModel)
            .put("temperature", 0.2)
            .put("messages", buildMessages())
            .put("tools", toolSpecs())
            .put("tool_choice", "auto")

        val conn = URL(settings.aiBaseUrl + "/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${settings.aiApiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("AI API error $code: ${raw.take(300)}")
            }
            return JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMessages(): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (i in 0 until history.length()) arr.put(history.get(i))
        return arr
    }

    private fun toolSpecs(): JSONArray = JSONArray()
        .put(
            tool(
                "run_command",
                "Run a non-interactive bash command in the Debian Linux environment (root, home /root). Returns exit code and output.",
                "command",
                "The bash command to run"
            )
        )
        .put(
            tool(
                "read_file",
                "Read a text file. Linux files: /root/... . Phone Downloads folder: /sdcard/Download/... (asks the user for storage access once).",
                "path",
                "Absolute path of the file"
            )
        )

    private fun tool(name: String, description: String, param: String, paramDesc: String): JSONObject {
        val props = JSONObject().put(param, JSONObject().put("type", "string").put("description", paramDesc))
        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray().put(param))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params)
            )
    }

    private suspend fun execute(name: String, args: JSONObject): String = when (name) {
        "run_command" -> runCommand(args.optString("command"))
        "read_file" -> readFile(args.optString("path"))
        else -> "Unknown tool: $name"
    }

    private suspend fun runCommand(command: String): String {
        if (command.isBlank()) return "Empty command."
        if (!settings.aiAllowCommands) {
            return "Running commands is turned off in Settings. Tell the user to enable it if they want this."
        }
        if (!settings.aiAutoApprove) {
            val approved = Approvals.ask("Allow AI to run this command?", command)
            if (!approved) return "The user denied this command. Do not retry it. Ask what they want instead."
        }
        val result = withContext(Dispatchers.IO) { ProotRunner.capture(context, command) }
        val body = result.output.trimEnd().ifEmpty { "(no output)" }
        append(ChatMessage(Role.TOOL, "\$ $command\n" + body.take(MAX_SHOWN)))
        val timeoutNote = if (result.timedOut) " (timed out after 120 s and was stopped)" else ""
        return "exit code ${result.exitCode}$timeoutNote\n" + result.output.take(MAX_TOOL_OUTPUT)
    }

    private suspend fun readFile(path: String): String {
        val cleaned = path.trim()
        if (cleaned.isEmpty()) return "Empty path."

        val file: File
        if (cleaned == "/sdcard" || cleaned.startsWith("/sdcard/")) {
            if (!StoragePermission.isGranted(context)) {
                val ok = Approvals.ask(
                    "Allow storage access?",
                    "The AI wants to read $cleaned from your phone storage."
                )
                if (!ok) return "The user declined storage access. Do not try again without asking."
                if (!StoragePermission.request(context)) {
                    return "Storage permission was not granted. Tell the user to allow it in Android settings or in Wani Terminal Settings."
                }
            }
            file = File("/storage/emulated/0" + cleaned.removePrefix("/sdcard"))
        } else {
            val abs = if (cleaned.startsWith("/")) cleaned else "/root/$cleaned"
            file = File(ProotRunner.rootfsDir(context), abs.trimStart('/'))
        }

        if (!file.isFile) return "File not found: $cleaned"
        val size = file.length()
        val bytes = readHead(file)
        if (bytes.any { it == 0.toByte() }) {
            return "Binary file ($size bytes). Use run_command with `file` or `xxd` to inspect it."
        }
        append(ChatMessage(Role.TOOL, "read $cleaned ($size bytes)"))
        val text = String(bytes, Charsets.UTF_8)
        return if (size > bytes.size) {
            text + "\n[truncated: showing first ${bytes.size} of $size bytes]"
        } else {
            text
        }
    }

    private fun readHead(file: File): ByteArray {
        val buf = ByteArray(MAX_READ_BYTES)
        var total = 0
        file.inputStream().use { input ->
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
        }
        return buf.copyOf(total)
    }

    companion object {
        private const val MAX_STEPS = 12
        private const val MAX_SHOWN = 1500
        private const val MAX_TOOL_OUTPUT = 6000
        private const val MAX_READ_BYTES = 40_000

        private const val WELCOME =
            "Hi, I'm Wani AI. I can run commands in your Linux shell and read files from Downloads, " +
                "with your permission. Add your API key in Settings to start."

        private val SYSTEM_PROMPT = """
            You are Wani AI, the built-in assistant of Wani Terminal, an Android app that runs a real Debian Linux shell (arm64, through proot).
            Reply in English. Be brief and clear. Use tools when they help; never guess file contents or command output.

            Environment:
            - Shell: bash as root in the Debian rootfs. Home directory: /root.
            - Phone storage is mounted at /sdcard. The Downloads folder is /sdcard/Download. Use read_file for it.
            - To show an image in the app, run: wani-show FILE.png
            - Python and other packages are NOT installed by default. Install them with apt-get only when the user asks, and say what you will install first.

            Rules:
            - Say what you are about to create before you create it. Put new command files in /root with a clear name, and make them executable when they are scripts.
            - Never run destructive commands (rm -rf on /, /sdcard or system folders, formatting, reboot) unless the user explicitly asks for that exact action.
            - Never print, copy or send API keys or tokens.
            - If a command fails, read the error, fix it and retry. Stop and ask only when you need the user's decision.
            - Keep command output short in your replies.
        """.trimIndent()
    }
}
