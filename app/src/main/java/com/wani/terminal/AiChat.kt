package com.wani.terminal

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

enum class Role { USER, ASSISTANT, TOOL, SYSTEM, ERROR }

data class ChatMessage(val role: Role, val text: String)

/**
 * Background jobs: lambi command AI ke intezaar ke baghair chalti rahti hai.
 * AI (ya user) baad mein job_status se result dekh sakta hai.
 */
object BackgroundJobs {

    class BgJob(val id: Int, val command: String) {
        @Volatile var done = false
        @Volatile var result: CommandResult? = null
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, BgJob>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 1

    @Synchronized
    private fun newId(): Int = nextId++

    fun start(context: Context, command: String): Int {
        val id = newId()
        val job = BgJob(id, command)
        jobs[id] = job
        val appCtx = context.applicationContext
        scope.launch {
            job.result = ProotRunner.capture(appCtx, command, timeoutSec = 1800)
            job.done = true
        }
        return id
    }

    fun status(id: Int): String {
        val job = jobs[id] ?: return "No background job with id $id."
        val r = job.result ?: return "Job $id is still running: ${job.command}"
        val timeout = if (r.timedOut) " (timed out)" else ""
        return "Job $id finished with exit code ${r.exitCode}$timeout\n" + r.output.takeLast(6000)
    }

    fun list(): String =
        if (jobs.isEmpty()) "No background jobs."
        else jobs.values.sortedBy { it.id }.joinToString("\n") {
            "#${it.id} ${if (it.done) "done" else "running"}: ${it.command}"
        }
}

/**
 * AI assistant. OpenAI-compatible chat API (base URL, model, key Settings se).
 *
 * v2.0: history aur chat app band hone ke baad bhi save rehti hai, kaam ke dauraan
 * phone ko jagaye rakhta hai (wake lock), aur terminal mein AI ke commands dikhata hai.
 * Sab state ek hi thread par badalti hai, taake race na ho.
 */
class AiChat(private val context: Context) {

    private val settings = AppSettings(context)

    private val aiThread = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, "wani-ai").apply { isDaemon = true }
    }).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + aiThread)

    private val stateFile = File(context.filesDir, "ai_state.json")

    private val _messages = MutableStateFlow(listOf(ChatMessage(Role.SYSTEM, WELCOME)))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Sirf aiThread par badlti hai
    private var history = JSONArray()

    @Volatile private var job: Job? = null

    init {
        load()
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _busy.value) return
        _busy.value = true
        job = scope.launch {
            val wake = acquireWakeLock()
            try {
                append(ChatMessage(Role.USER, text))
                history.put(JSONObject().put("role", "user").put("content", text))
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(ChatMessage(Role.ERROR, e.message ?: e.toString()))
            } finally {
                releaseWakeLock(wake)
                _busy.value = false
                save()
            }
        }
    }

    /** Upload: file ko /root/uploads mein copy karta hai, AI ko uska path deta hai. */
    fun attachFile(uri: Uri) {
        scope.launch {
            if (_busy.value) {
                append(ChatMessage(Role.SYSTEM, "Upload again after the AI finishes this step."))
                return@launch
            }
            try {
                val name = safeName(displayName(uri))
                val dest = withContext(Dispatchers.IO) {
                    val dir = File(ProotRunner.rootfsDir(context), "root/uploads").apply { mkdirs() }
                    val file = File(dir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open the selected file.")
                    file
                }
                val linuxPath = "/root/uploads/${dest.name}"
                append(ChatMessage(Role.SYSTEM, "Uploaded $linuxPath (${dest.length()} bytes)"))
                history.put(
                    JSONObject().put("role", "user").put(
                        "content",
                        "I uploaded a file to $linuxPath (${dest.length()} bytes). Use read_file or run_command on it when I ask."
                    )
                )
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(ChatMessage(Role.ERROR, "Upload failed: ${e.message}"))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _busy.value = false
        append(ChatMessage(Role.SYSTEM, "Stopped. Background jobs keep running."))
    }

    fun clear() {
        if (_busy.value) return
        scope.launch {
            history = JSONArray()
            _messages.value = listOf(ChatMessage(Role.SYSTEM, WELCOME))
            save()
        }
    }

    private fun append(m: ChatMessage) {
        _messages.update { (it + m).takeLast(MAX_MESSAGES) }
    }

    // ------------------------------------------------------------ save / load

    /** Chat aur history phone par save karta hai (app dobara khulne par wahin se chalti hai). */
    private fun save() {
        runCatching {
            val msgs = JSONArray()
            for (m in _messages.value.takeLast(MAX_SAVED_MESSAGES)) {
                msgs.put(JSONObject().put("role", m.role.name).put("text", m.text))
            }
            val root = JSONObject().put("history", history).put("messages", msgs)
            stateFile.writeText(root.toString())
        }
    }

    private fun load() {
        if (!stateFile.isFile) return
        runCatching {
            val root = JSONObject(stateFile.readText())
            history = root.optJSONArray("history") ?: JSONArray()
            val arr = root.optJSONArray("messages") ?: JSONArray()
            val list = ArrayList<ChatMessage>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.SYSTEM)
                list.add(ChatMessage(role, o.optString("text")))
            }
            if (list.isNotEmpty()) {
                _messages.value = listOf(ChatMessage(Role.SYSTEM, "Earlier chat restored. Tap Clear to start fresh.")) + list
            }
        }
    }

    // ------------------------------------------------------------ wake lock (24/7 kaam ke liye)

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wani:ai").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }.getOrNull()

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        runCatching {
            if (lock != null && lock.isHeld) lock.release()
        }
    }

    // ------------------------------------------------------------ history hygiene

    /**
     * Agar koi tool call bina jawab (result) ke reh gaya ho (jaise Stop dabane par),
     * to API error deta hai. Ye har adhoore call ke liye khali result bhar deta hai,
     * aur bina tool_call wale orphan result hata deta hai.
     */
    private fun repairHistory() {
        val out = JSONArray()
        val pending = LinkedHashSet<String>()
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            val role = m.optString("role")
            if (role == "tool") {
                val id = m.optString("tool_call_id")
                if (pending.remove(id)) out.put(m)
                continue
            }
            if (pending.isNotEmpty()) {
                for (id in pending) out.put(stubResult(id))
                pending.clear()
            }
            out.put(m)
            if (role == "assistant") {
                val calls = m.optJSONArray("tool_calls")
                if (calls != null) {
                    for (j in 0 until calls.length()) pending.add(calls.getJSONObject(j).optString("id"))
                }
            }
        }
        for (id in pending) out.put(stubResult(id))
        history = out
    }

    private fun stubResult(id: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("content", "This step was stopped before it finished.")

    /** History bahut lambi ho to purani baatein chhod deta hai (hamesha user message se shuru hota hai). */
    private fun trimHistory() {
        if (history.length() <= MAX_HISTORY) return
        var start = history.length() - KEEP_HISTORY
        while (start < history.length() && history.getJSONObject(start).optString("role") != "user") start++
        val out = JSONArray()
        for (i in start until history.length()) out.put(history.get(i))
        history = out
    }

    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        val name = cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return name ?: uri.lastPathSegment ?: "upload.bin"
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.bin" }

    private suspend fun runLoop() {
        if (settings.aiApiKey.isBlank() && !isLocalBase(settings.aiBaseUrl)) {
            throw IllegalStateException("No API key yet. Open Settings and add your AI API key.")
        }
        repairHistory()
        trimHistory()
        for (step in 0 until MAX_STEPS) {
            val message = requestWithRetry()
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
                // Tool ka error bhi model ko result ki tarah jaata hai, loop nahi tootta
                val result = try {
                    execute(fn.optString("name"), args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Tool error: ${e.message ?: e.toString()}"
                }
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

    /** Model ko request bhejta hai. Network ya server (429/5xx) ka masla ho to 3 dafa tak try karta hai. */
    private suspend fun requestWithRetry(): JSONObject {
        var lastError: Exception? = null
        // Body aiThread par banti hai (history yahin badalti hai), network IO par chalta hai
        val body = JSONObject()
            .put("model", settings.aiModel)
            .put("temperature", 0.2)
            .put("messages", buildMessages())
            .put("tools", toolSpecs())
            .put("tool_choice", "auto")
        for (attempt in 1..3) {
            try {
                return withContext(Dispatchers.IO) { requestCompletion(body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            } catch (e: IllegalStateException) {
                val retryable = Regex("AI API error (429|5\\d\\d)").containsMatchIn(e.message.orEmpty())
                if (!retryable) throw e
                lastError = e
            }
            delay(2000L * attempt)
        }
        throw lastError ?: IllegalStateException("AI request failed")
    }

    private fun requestCompletion(body: JSONObject): JSONObject {
        val conn = URL(settings.aiBaseUrl + "/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (settings.aiApiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${settings.aiApiKey}")
            }
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
        val custom = settings.aiCustomInstructions
        val system = if (custom.isBlank()) {
            SYSTEM_PROMPT
        } else {
            SYSTEM_PROMPT + "\n\nExtra instructions from the user (follow them unless they conflict with the rules above):\n" + custom
        }
        arr.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until history.length()) arr.put(history.get(i))
        return arr
    }

    private fun toolSpecs(): JSONArray = JSONArray()
        .put(tool("run_command", "Run a bash command in the Debian Linux environment (root, home /root) and wait for the result. Max 2 minutes. Use for quick commands.",
            "command" to "The bash command to run"))
        .put(tool("start_background_job", "Start a long bash command in the background. Returns a job id. Check it later with job_status.",
            "command" to "The bash command to run in the background"))
        .put(tool("job_status", "Check a background job by id. Send 'list' as id to list all jobs.",
            "id" to "Job id number, or 'list'"))
        .put(tool("type_in_terminal", "Type a command into the visible terminal and press Enter. Its output appears in the terminal, not in this chat.",
            "text" to "The text to type"))
        .put(tool("read_terminal", "Read the last lines of the visible terminal screen (what the user sees). Use it to check the output of a command or an error the user mentions.",
            "lines" to "How many lines to read, for example 60"))
        .put(tool("set_app_setting", "Change an app setting. Allowed names: shortcut_keys (comma-separated button ids, for example esc,tab,ctrl,up,down,enter,ai) and show_ai_in_terminal (true or false). The user approves the change unless they turned on Run without asking. API key, model and approval settings can only be changed by the user.",
            "name" to "Setting name: shortcut_keys or show_ai_in_terminal",
            "value" to "New value"))
        .put(tool("write_file", "Create or overwrite a text file in the Linux environment. Relative paths go to /root.",
            "path" to "File path, for example /root/hello.sh",
            "content" to "Full file content"))
        .put(tool("read_file", "Read a text file. Linux: /root/... . Phone Downloads: /sdcard/Download/... (asks the user for storage access once).",
            "path" to "Absolute file path"))
        .put(tool("web_search", "Search the web (DuckDuckGo). Returns titles, URLs and snippets. Use it for current facts and cite the URLs you used.",
            "query" to "Search query"))
        .put(tool("web_fetch", "Fetch a web page and return its text (first ~8000 characters).",
            "url" to "Full http or https URL"))
        .put(tool("open_live_page", "Show a live page on the phone to ask the user something (choices, a form, a question). Write a complete HTML page. When the user submits, call WaniBridge.submit(string) with the answer; this tool returns that answer. Use plain CSS, no external resources, support dark and light (prefers-color-scheme).",
            "title" to "Short page title",
            "html" to "Complete HTML document for the page"))

    private fun tool(name: String, description: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for ((p, d) in params) {
            props.put(p, JSONObject().put("type", "string").put("description", d))
            required.put(p)
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
        if (required.length() > 0) schema.put("required", required)
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", schema)
            )
    }

    private suspend fun execute(name: String, args: JSONObject): String = when (name) {
        "run_command" -> runCommand(args.optString("command"))
        "start_background_job" -> startJob(args.optString("command"))
        "job_status" -> jobStatus(args.optString("id").trim())
        "type_in_terminal" -> typeInTerminal(args.optString("text"))
        "read_terminal" -> readTerminal(args.optString("lines"))
        "set_app_setting" -> setAppSetting(args.optString("name"), args.optString("value"))
        "write_file" -> writeFile(args.optString("path"), args.optString("content"))
        "read_file" -> readFile(args.optString("path"))
        "web_search" -> webSearch(args.optString("query"))
        "web_fetch" -> webFetch(args.optString("url"))
        "open_live_page" -> openLivePage(args.optString("title"), args.optString("html"))
        else -> "Unknown tool: $name"
    }

    /** Command tools ke liye: pehle Settings check, phir approval (auto-approve on ho to seedha). */
    private suspend fun allowedToRun(detail: String): String? {
        if (!settings.aiAllowCommands) {
            return "Running commands is turned off in Settings. Tell the user to enable it if they want this."
        }
        if (settings.aiAutoApprove) return null
        val approved = Approvals.ask("Allow AI to run this?", detail)
        return if (approved) null else "The user denied this action. Do not retry it. Ask what they want instead."
    }

    /** AI ke kaam ko terminal mein dikhata hai (Settings mein on ho to). */
    private fun showInTerminal(header: String, body: String) {
        if (!settings.aiShowInTerminal) return
        val app = context.applicationContext as WaniApp
        app.shell.logAi("[AI] $header\n" + body.take(MAX_TERMINAL_ECHO))
    }

    private suspend fun runCommand(command: String): String {
        if (command.isBlank()) return "Empty command."
        allowedToRun(command)?.let { return it }
        val result = withContext(Dispatchers.IO) { ProotRunner.capture(context, command) }
        val body = result.output.trimEnd().ifEmpty { "(no output)" }
        append(ChatMessage(Role.TOOL, "\$ $command\n" + body.take(MAX_SHOWN)))
        showInTerminal("$ $command", body)
        val timeoutNote = if (result.timedOut) " (timed out after 120 s and was stopped)" else ""
        return "exit code ${result.exitCode}$timeoutNote\n" + result.output.take(MAX_TOOL_OUTPUT)
    }

    private suspend fun startJob(command: String): String {
        if (command.isBlank()) return "Empty command."
        allowedToRun("(background) $command")?.let { return it }
        val id = BackgroundJobs.start(context, command)
        append(ChatMessage(Role.TOOL, "started background job #$id: $command"))
        showInTerminal("background job #$id", command)
        return "Started background job $id. Check it later with job_status."
    }

    private fun jobStatus(id: String): String {
        if (id == "list") return BackgroundJobs.list()
        val n = id.toIntOrNull() ?: return "Give a numeric job id, or 'list'."
        return BackgroundJobs.status(n)
    }

    private suspend fun typeInTerminal(text: String): String {
        if (text.isBlank()) return "Empty text."
        allowedToRun("Type into the terminal: $text")?.let { return it }
        val app = context.applicationContext as WaniApp
        app.shell.ensureRunning()
        val pty = app.shell.pty.value
        app.shell.send(text + if (pty) "\r" else "\n", echo = !pty)
        append(ChatMessage(Role.TOOL, "typed into terminal: $text"))
        return "Typed into the terminal. Output shows in the terminal, not here. Use read_terminal to see it."
    }

    /** AI ko sirf kuch safe settings badalne deta hai. API key, model, approval waghera user ke haath mein hi rehte hain. */
    private suspend fun setAppSetting(name: String, value: String): String {
        val key = name.trim()
        val v = value.trim()
        return when (key) {
            "shortcut_keys" -> changeShortcutKeys(v)
            "show_ai_in_terminal" -> changeShowInTerminal(v)
            else -> "Unknown setting. Allowed: shortcut_keys, show_ai_in_terminal."
        }
    }

    private suspend fun changeShortcutKeys(v: String): String {
        val ids = v.split(',').map { it.trim() }.filter { ShortcutKeys.isKnown(it) }.distinct()
        if (ids.isEmpty()) {
            return "No valid button ids. Valid ids: " + ShortcutKeys.ALL.joinToString(", ") { it.id }
        }
        if (!settings.aiAutoApprove &&
            !Approvals.ask("Change the shortcut bar?", "Buttons: " + ids.joinToString(", "))
        ) {
            return "The user denied this change. Do not retry it."
        }
        settings.shortcutKeys = ids
        append(ChatMessage(Role.TOOL, "set shortcut bar: " + ids.joinToString(", ")))
        return "Shortcut bar updated: " + ids.joinToString(", ")
    }

    private suspend fun changeShowInTerminal(v: String): String {
        val on = when (v.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> return "Use true or false."
        }
        if (!settings.aiAutoApprove &&
            !Approvals.ask("Change terminal logging?", "Show AI actions in terminal: $on")
        ) {
            return "The user denied this change. Do not retry it."
        }
        settings.aiShowInTerminal = on
        return "Show AI actions in terminal is now " + (if (on) "on" else "off") + "."
    }

    /** Terminal screen ka aakhri hissa AI ko deta hai. */
    private fun readTerminal(linesArg: String): String {
        val lines = linesArg.trim().toIntOrNull()?.coerceIn(5, 300) ?: 60
        val app = context.applicationContext as WaniApp
        val screen = app.shell.recentText(lines).trimEnd()
        append(ChatMessage(Role.TOOL, "read terminal ($lines lines)"))
        return screen.ifBlank { "(terminal is empty)" }.takeLast(MAX_TOOL_OUTPUT)
    }

    private suspend fun writeFile(path: String, content: String): String {
        val cleaned = path.trim()
        if (cleaned.isEmpty() || cleaned.contains("..")) return "Give a plain file path without '..'."
        val abs = if (cleaned.startsWith("/")) cleaned else "/root/$cleaned"
        allowedToRun("Write file $abs (${content.length} chars)")?.let { return it }
        val file = File(ProotRunner.rootfsDir(context), abs.trimStart('/'))
        file.parentFile?.mkdirs()
        file.writeText(content)
        append(ChatMessage(Role.TOOL, "wrote $abs (${content.length} chars)"))
        showInTerminal("wrote $abs", "(${content.length} chars)")
        return "Wrote ${content.length} characters to $abs."
    }

    private suspend fun openLivePage(title: String, html: String): String {
        if (html.isBlank()) return "Empty page. Write a complete HTML document."
        val label = title.ifBlank { "Question" }
        append(ChatMessage(Role.SYSTEM, "Live page opened: $label"))
        val answer = LivePages.open(label, html)
        append(ChatMessage(Role.TOOL, "user answer: ${answer.take(400)}"))
        return "User answered: $answer"
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
                    return "Storage permission was not granted. Tell the user to allow it in Settings."
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

    private fun isLocalBase(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2") || u.contains("[::1]")
    }

    private suspend fun webSearch(query: String): String {
        if (query.isBlank()) return "Empty query."
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val html = withContext(Dispatchers.IO) { httpGet(url) }
        val results = parseDuckDuckGo(html)
        append(ChatMessage(Role.TOOL, "web search: $query (${results.size} results)"))
        return if (results.isEmpty()) {
            "No results found. The search page may have blocked the request. Try web_fetch on a known URL."
        } else {
            results.joinToString("\n\n")
        }
    }

    private suspend fun webFetch(url: String): String {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return "Give a full http or https URL."
        val raw = withContext(Dispatchers.IO) { httpGet(u) }
        val text = stripTags(raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " "))
        append(ChatMessage(Role.TOOL, "fetched $u"))
        return text.take(8000).ifBlank { "(empty page)" }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) WaniTerminal/2.0")
            conn.setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }.take(300_000)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDuckDuckGo(html: String): List<String> {
        val linkRe = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snips = snipRe.findAll(html).toList()
        return links.take(6).mapIndexed { i, m ->
            val url = unwrapDdg(m.groupValues[1])
            val title = stripTags(m.groupValues[2])
            val snip = snips.getOrNull(i)?.let { stripTags(it.groupValues[1]) }.orEmpty()
            "${i + 1}. $title\n   $url\n   $snip"
        }
    }

    /** DuckDuckGo ka redirect link (uddg=...) asli URL mein badalta hai. */
    private fun unwrapDdg(href: String): String {
        val full = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("[?&]uddg=([^&]+)").find(full)?.groupValues?.get(1)
        return if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else full
    }

    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

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
        private const val MAX_STEPS = 24
        private const val MAX_SHOWN = 1500
        private const val MAX_TOOL_OUTPUT = 6000
        private const val MAX_READ_BYTES = 40_000
        private const val MAX_TERMINAL_ECHO = 2000
        private const val MAX_MESSAGES = 400
        private const val MAX_SAVED_MESSAGES = 300
        private const val MAX_HISTORY = 120
        private const val KEEP_HISTORY = 80
        private const val WAKE_LOCK_MS = 30L * 60 * 1000

        private const val WELCOME =
            "Hi, I'm Wani AI. I can run commands, start background jobs, type into the terminal, " +
                "read what is on the terminal screen, edit files, search the web, read Downloads, and " +
                "ask you things with live pages. Add your API key in Settings to start. " +
                "I keep working while the app runs in the background."

        private val SYSTEM_PROMPT = """
            You are Wani AI, the built-in assistant of Wani Terminal, an Android app that runs a real Debian Linux shell (arm64, through proot).
            Reply in English unless the user writes in another language. Be brief and clear. Use tools when they help; never guess file contents or command output.

            Environment:
            - Shell: bash as root in the Debian rootfs. Home directory: /root. Uploaded files are in /root/uploads.
            - Phone storage is mounted at /sdcard. Downloads is /sdcard/Download. Use read_file for it.
            - Python and other packages are NOT installed by default. Install with apt-get only when the user asks, and say what you will install first.
            - To show an image in the app, run: wani-show FILE.png

            How to work:
            - Use run_command for quick commands. Use start_background_job for long ones (installs, builds), then job_status to check.
            - After a command, read its result before the next step. If a command fails, read the error, fix it and retry.
            - Use read_terminal to see what is on the user's terminal screen before answering questions about it.
            - Use write_file to create scripts, then run them (chmod +x for shell scripts).
            - Use type_in_terminal only when the user wants something visible in the terminal.
            - When you need a choice or information from the user, use open_live_page with a small HTML form. Its submit button must call WaniBridge.submit(answer). Then continue with the answer.
            - For multi-step tasks, keep the user informed with one short line per step.
            - Stop and ask only when you need the user's decision.

            Rules:
            - Say what you are about to do before risky actions.
            - Never run destructive commands (rm -rf on /, /sdcard or system folders, formatting, reboot) unless the user explicitly asks for that exact action.
            - Never print, copy or send API keys or tokens.
            - For current facts, use web_search, then web_fetch the best result. Cite the source URLs.
            - Keep command output short in your replies.
        """.trimIndent()
    }
}
