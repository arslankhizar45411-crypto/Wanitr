package com.collabcode.app.agent

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.collabcode.app.data.Http
import com.collabcode.app.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private val TEXT_EXTENSIONS = listOf(
    "txt", "md", "json", "yaml", "yml", "dart", "js", "ts", "py", "html", "css", "kt", "java",
    "xml", "gradle", "sh", "toml", "csv",
)

/** The whole agent: chats, the model loop, tool approvals, stop and export. */
class AgentEngine(app: Application) : AndroidViewModel(app) {
    private val cfgStore = AgentConfigStore(app)
    private val chatStore = AgentStore(app)
    private val client = AgentClient()
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var stopRequested = false

    var cfg by mutableStateOf(cfgStore.load())
        private set
    val chats = mutableStateListOf<Chat>()
    var cur by mutableStateOf<Chat?>(null)
        private set
    val files = mutableStateListOf<Attachment>()
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
    var settingsRequest by mutableStateOf(0)
    var repos by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        chats.addAll(chatStore.load())
        if (chats.isEmpty()) chats.add(Chat("New chat"))
        cur = chats.first()
        loadRepos()
    }

    // ---------- chats ----------

    fun newChat() {
        if (busy) return
        val c = Chat("New chat")
        chats.add(0, c)
        cur = c
        persist()
    }

    fun select(c: Chat) {
        if (!busy) cur = c
    }

    fun deleteChat(c: Chat) {
        if (busy) return
        chats.remove(c)
        if (chats.isEmpty()) chats.add(Chat("New chat"))
        if (cur === c || cur == null) cur = chats.first()
        persist()
    }

    fun clearAll() {
        if (busy) return
        chats.clear()
        chats.add(Chat("New chat"))
        cur = chats.first()
        chatStore.clear()
    }

    private fun persist() {
        chatStore.save(chats.toList())
    }

    // ---------- settings ----------

    fun saveConfig(next: AgentConfig) {
        cfg = next
        cfgStore.save(next)
        client.resetTemperature()
        loadRepos()
        toast = "Settings saved"
    }

    fun setRepo(r: String) {
        cfg = cfg.copy(repo = r.trim())
        cfgStore.save(cfg)
    }

    fun setTheme(t: String) {
        cfg = cfg.copy(theme = t)
        cfgStore.save(cfg)
    }

    private fun loadRepos() {
        val token = cfg.gt
        if (token.isBlank()) return
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fetchRepos(token) }
                repos = list
                if (cfg.repo.isBlank() && list.isNotEmpty()) setRepo(list[0])
            } catch (e: Exception) {
                // The list is optional. Typing the name still works.
            }
        }
    }

    private fun fetchRepos(token: String): List<String> {
        val r = Http.call(
            "GET",
            "https://api.github.com/user/repos?sort=pushed&per_page=50",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
            null,
            "api.github.com",
        )
        if (r.code !in 200..299) return emptyList()
        val arr = JSONArray(r.body)
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("full_name") }
    }

    // ---------- attachments ----------

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                val item = withContext(Dispatchers.IO) { readAttachment(u) } ?: continue
                files.add(item)
            }
        }
    }

    fun removeFile(i: Int) {
        if (i in files.indices) files.removeAt(i)
    }

    private fun readAttachment(uri: Uri): Attachment? {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val isText = TEXT_EXTENSIONS.any { name.lowercase().endsWith(".$it") }
        val text = if (isText && size < 200_000) {
            resolver.openInputStream(uri)?.use { input -> input.bufferedReader().readText().take(200_000) }
        } else {
            null
        }
        val bytes = if (size >= 0) size else (text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
        return Attachment(name, mime, bytes, text)
    }

    // ---------- sending ----------

    /** Returns true when the message was accepted. */
    fun send(raw: String): Boolean {
        val txt = raw.trim()
        if (txt.isEmpty() || busy) return false
        if (cfg.key.isBlank()) {
            toast = "Add your API key in Settings first"
            settingsRequest++
            return false
        }
        val chat = cur ?: return false
        repair(chat)
        val at = files.joinToString("") { f ->
            val t = f.text
            if (t != null) "\n\n[File: ${f.name}]\n" + t.take(8000) else "\n\n[Attached: ${f.name} (${f.size} bytes)]"
        }
        files.clear()
        chat.ui.add(Me(txt))
        chat.hist.add(JSONObject().put("role", "user").put("content", txt + at))
        if (chat.title == "New chat") chat.title = txt.take(30)
        busy = true
        stopRequested = false
        persist()
        job = viewModelScope.launch {
            try {
                loop(chat)
            } catch (e: CancellationException) {
                chat.ui.add(Note("Stopped."))
                throw e
            } catch (e: Exception) {
                if (stopRequested) {
                    chat.ui.add(Note("Stopped."))
                } else {
                    chat.ui.add(Ai("Request failed: " + (e.message ?: e.toString())))
                }
            } finally {
                repair(chat)
                busy = false
                job = null
                persist()
            }
        }
        return true
    }

    private suspend fun loop(chat: Chat) {
        for (step in 0 until 14) {
            val bubble = Ai("")
            chat.ui.add(bubble)
            val res = client.complete(cfg, chat.hist, AgentToolDefs.ALL) { partial ->
                main.post { bubble.text = partial }
            }
            bubble.text = res.text
            if (res.text.isBlank()) chat.ui.remove(bubble)

            val m = JSONObject().put("role", "assistant").put("content", if (res.text.isEmpty()) JSONObject.NULL else res.text)
            if (res.calls.isNotEmpty()) {
                val arr = JSONArray()
                for (c in res.calls) {
                    arr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", c.args))
                    )
                }
                m.put("tool_calls", arr)
            }
            chat.hist.add(m)
            if (res.calls.isEmpty()) break
            for (c in res.calls) {
                val result = runCall(c, chat)
                chat.hist.add(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", result))
            }
            persist()
        }
    }

    private suspend fun runCall(c: ToolCallOut, chat: Chat): String {
        val a = try {
            JSONObject(c.args.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        val card = ToolCard(c.name, a.toString().take(160))
        chat.ui.add(ToolItem(card))
        card.status = "Running"
        card.kind = "warn"
        return try {
            val out = AgentTools(cfg).run(c.name, a, card) { cd, need -> ask(cd, need) }
            if (card.status != "Denied") {
                card.status = "Done"
                card.kind = "ok"
            }
            out
        } catch (e: CancellationException) {
            card.status = "Stopped"
            card.kind = "err"
            card.pending = false
            throw e
        } catch (e: Exception) {
            card.out = "Error: " + (e.message ?: e.toString())
            card.status = "Failed"
            card.kind = "err"
            card.out
        }
    }

    /** Shows the approval buttons and waits for the user. Without approval the call runs at once. */
    private suspend fun ask(card: ToolCard, need: Boolean): Boolean {
        if (!need) {
            card.status = "Auto-approved"
            card.kind = "ok"
            return true
        }
        val d = CompletableDeferred<Boolean>()
        card.decision = d
        card.pending = true
        card.status = "Needs approval"
        card.kind = "warn"
        try {
            val yes = d.await()
            card.pending = false
            card.decision = null
            card.status = if (yes) "Allowed" else "Denied"
            card.kind = if (yes) "ok" else "err"
            return yes
        } catch (e: CancellationException) {
            card.pending = false
            card.decision = null
            card.status = "Stopped"
            card.kind = "err"
            throw e
        }
    }

    fun decide(card: ToolCard, yes: Boolean) {
        card.decision?.complete(yes)
    }

    /** The stop control. Denies pending approvals, cancels the reply and stops the request. */
    fun stop() {
        if (!busy) return
        stopRequested = true
        for (ch in chats) {
            for (u in ch.ui) {
                if (u is ToolItem && u.card.pending) u.card.decision?.complete(false)
            }
        }
        client.cancelRequest()
        job?.cancel()
    }

    // ---------- history repair (a tool call always needs an answer) ----------

    private fun repair(chat: Chat) {
        val h = chat.hist
        var i = 0
        while (i < h.size) {
            val m = h[i]
            val calls = m.optJSONArray("tool_calls")
            if (m.optString("role") == "assistant" && calls != null) {
                val have = HashSet<String>()
                var j = i + 1
                while (j < h.size && h[j].optString("role") == "tool") {
                    have.add(h[j].optString("tool_call_id"))
                    j++
                }
                val add = mutableListOf<JSONObject>()
                for (k in 0 until calls.length()) {
                    val id = calls.getJSONObject(k).optString("id")
                    if (id !in have) {
                        add.add(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "Cancelled before it ran."))
                    }
                }
                h.addAll(j, add)
                i = j + add.size
            } else {
                i++
            }
        }
    }

    // ---------- export ----------

    fun exportFile(): File? {
        val chat = cur ?: return null
        val text = chat.ui.joinToString("\n\n") { u ->
            when (u) {
                is Me -> "## You\n" + u.text
                is Ai -> "## CollabAgent\n" + u.text
                is Note -> "## CollabAgent\n" + u.text
                is ToolItem -> ""
            }
        }
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val name = chat.title.replace(Regex("\\W+"), "-").ifEmpty { "chat" }
        val f = File(dir, "$name.md")
        f.writeText(text)
        return f
    }
}
