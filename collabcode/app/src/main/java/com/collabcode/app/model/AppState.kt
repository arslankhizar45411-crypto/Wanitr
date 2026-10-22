package com.collabcode.app.model

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.collabcode.app.BuildWatchService
import com.collabcode.app.data.AiClient
import com.collabcode.app.data.ApiException
import com.collabcode.app.data.DiffLine
import com.collabcode.app.data.GhJob
import com.collabcode.app.data.GhRun
import com.collabcode.app.data.GitHub
import com.collabcode.app.data.Prefs
import com.collabcode.app.data.ProjectStore
import com.collabcode.app.data.Template
import com.collabcode.app.data.lineDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

enum class Screen { Home, Chat, Editor, Preview, Build, Terminal }
enum class EditStatus { Pending, Accepted, Rejected }

data class PendingEdit(val file: String, val newContent: String, val diff: List<DiffLine>)
data class BuildRun(val id: Long, val number: Int, val status: String, val url: String)

const val DEFAULT_PROMPT = "Add a multiply function to utils.kt"

sealed interface ChatItem
class UserMsg(val text: String) : ChatItem
class NoticeMsg(val tool: String, val arg: String, val note: String) : ChatItem
class AssistantMsg(
    val tool: String?,
    val toolArg: String?,
    val text: String,
    val edits: List<PendingEdit>,
) : ChatItem {
    var status by mutableStateOf(EditStatus.Pending)
}

class AppState(application: Application) : AndroidViewModel(application) {
    val prefs = Prefs(application)
    private val store = ProjectStore(application)
    private val chatStore = ChatStore(application)
    private var pollJob: Job? = null
    private var sendJob: Job? = null
    private val saveJobs = HashMap<String, Job>()

    /** Files as last pushed to GitHub, by repo path. Used to send only what changed. */
    private var committed: Map<String, String> = emptyMap()

    var screen by mutableStateOf(Screen.Home)
    var activeFile by mutableStateOf("")
    val openFiles = mutableStateListOf<String>()
    val files = mutableStateMapOf<String, String>()
    val chat = mutableStateListOf<ChatItem>()
    val builds = mutableStateListOf<BuildRun>()

    var onboarded by mutableStateOf(prefs.isSetUp)
    var githubLogin by mutableStateOf(prefs.githubLogin.orEmpty())
    var aiModel by mutableStateOf(prefs.aiModel)
    var busy by mutableStateOf(false)

    var buildStarting by mutableStateOf(false)
    var runningRunId by mutableStateOf<Long?>(null)
    var buildMessage by mutableStateOf<String?>(null)

    var selectedRun by mutableStateOf<BuildRun?>(null)
    var jobs by mutableStateOf<List<GhJob>>(emptyList())
    var logTail by mutableStateOf<String?>(null)
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)

    var apkBusy by mutableStateOf(false)
    var apkMessage by mutableStateOf<String?>(null)
    var readyApk by mutableStateOf<File?>(null)
    var readyApkRunId by mutableStateOf<Long?>(null)

    init {
        val loaded = store.loadFiles()
        if (loaded != null) {
            files.putAll(loaded)
        } else {
            defaultFiles().forEach { (name, text) ->
                files[name] = text
                store.save(name, text)
            }
        }
        committed = store.loadCommitted()
        chat.addAll(chatStore.load())
        val first = if ("main.kt" in files) "main.kt" else files.keys.sorted().firstOrNull().orEmpty()
        if (first.isNotEmpty()) {
            openFiles.add(first)
            activeFile = first
        }
    }

    fun title(): String = when (screen) {
        Screen.Home -> "CollabCode"
        Screen.Chat -> "AI chat"
        Screen.Editor -> activeFile.ifEmpty { "Code editor" }
        Screen.Preview -> "Live preview"
        Screen.Build -> "Build"
        Screen.Terminal -> "Terminal"
    }

    // ---------- Files ----------

    fun fileNames(): List<String> = files.keys.sorted()

    fun open(file: String) {
        if (file !in files) return
        if (file !in openFiles) openFiles.add(file)
        activeFile = file
    }

    fun closeTab(file: String) {
        openFiles.remove(file)
        if (activeFile == file) activeFile = openFiles.lastOrNull().orEmpty()
    }

    /** Updates a file in memory now and saves it to the phone shortly after. */
    fun updateFile(name: String, text: String) {
        if (name !in files) return
        files[name] = text
        saveJobs[name]?.cancel()
        saveJobs[name] = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { store.save(name, text) }
        }
    }

    /** Returns an error message, or null when the file was created. */
    fun createFile(rawName: String): String? {
        val name = rawName.trim()
        if (!ProjectStore.isValidName(name)) {
            return "Use letters, numbers, dot, dash or underscore. No folders, up to 64 characters."
        }
        if (name in Template.RESERVED) return "$name is created by the build. Pick another name."
        if (name in files) return "A file named $name already exists."
        files[name] = ""
        store.save(name, "")
        open(name)
        return null
    }

    fun deleteFile(name: String) {
        saveJobs.remove(name)?.cancel()
        files.remove(name)
        store.delete(name)
        openFiles.remove(name)
        if (activeFile == name) activeFile = openFiles.lastOrNull() ?: fileNames().firstOrNull().orEmpty()
        if (activeFile.isNotEmpty() && activeFile !in openFiles) openFiles.add(activeFile)
    }

    // ---------- AI chat ----------

    fun sendAsync(prompt: String) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch { send(prompt) }
    }

    /** Stops the reply in progress. The question stays in the chat. */
    fun stopSend() {
        sendJob?.cancel()
        sendJob = null
        busy = false
        chatStore.save(chat.toList())
    }

    fun clearHistory() {
        chat.clear()
        chatStore.clear()
    }

    suspend fun send(prompt: String) {
        val history = chat.takeLast(8).mapNotNull { item ->
            when (item) {
                is UserMsg -> "user" to item.text
                is AssistantMsg -> "assistant" to item.text
                else -> null
            }
        }
        chat.add(UserMsg(prompt))
        if (prefs.aiKey.isNullOrBlank()) {
            chat.add(NoticeMsg("ai", "not connected", "Add an AI key in Settings first."))
            return
        }
        busy = true
        try {
            val client = AiClient(prefs.aiBaseUrl, prefs.aiKey.orEmpty(), prefs.aiModel)
            val result = client.ask(prompt, files.toMap(), history)
            val edits = result.edits.mapNotNull { (name, content) ->
                val old = files[name] ?: return@mapNotNull null
                if (old == content) null else PendingEdit(name, content, lineDiff(old, content))
            }
            chat.add(
                AssistantMsg(
                    tool = if (edits.isEmpty()) null else "edit_file",
                    toolArg = edits.joinToString { it.file }.takeIf { it.isNotEmpty() },
                    text = result.reply,
                    edits = edits,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            chat.add(NoticeMsg("ai", "error", e.message ?: "AI request failed."))
        } finally {
            busy = false
            chatStore.save(chat.toList())
        }
    }

    fun accept(msg: AssistantMsg) {
        if (msg.status != EditStatus.Pending) return
        msg.edits.forEach { updateFile(it.file, it.newContent) }
        msg.status = EditStatus.Accepted
    }

    fun reject(msg: AssistantMsg) {
        if (msg.status == EditStatus.Pending) msg.status = EditStatus.Rejected
    }

    /** Returns an error message, or null when saved. Leave [newKey] empty to keep the current key. */
    suspend fun saveAiSettings(base: String, model: String, newKey: String): String? {
        val b = base.trim()
        val m = model.trim()
        val k = newKey.trim().ifEmpty { prefs.aiKey.orEmpty() }
        if (m.isEmpty()) return "Model cannot be empty."
        if (k.isEmpty()) return "Enter an API key."
        return try {
            AiClient(b, k, m).checkKey()
            prefs.aiBaseUrl = b
            prefs.aiModel = m
            prefs.aiKey = k
            aiModel = m
            null
        } catch (e: Exception) {
            e.message ?: "Could not verify the key."
        }
    }

    // ---------- GitHub ----------

    /**
     * Pushes what changed since the last push, in one commit, and removes deleted files.
     * With [withBuildFiles], the generated Android project files are included too.
     */
    private suspend fun pushChanges(repo: String, withBuildFiles: Boolean): Int {
        val pkg = Template.packageOf(files["main.kt"].orEmpty())
        val wanted = LinkedHashMap<String, String>()
        wanted.putAll(Template.userFiles(files.toMap(), pkg))
        if (withBuildFiles) wanted.putAll(Template.generated(pkg))

        val changed = wanted.filter { (path, text) -> committed[path] != text }
        val deleted = committed.keys.filter { it !in wanted && it !in Template.GENERATED_PATHS }
        if (changed.isEmpty() && deleted.isEmpty()) return 0

        val gh = GitHub(prefs.githubToken.orEmpty())
        gh.commitFiles(repo, gh.defaultBranch(repo), changed, deleted.toSet(), "Update from CollabCode")

        committed = committed.filterKeys { it !in deleted } + changed
        withContext(Dispatchers.IO) { store.saveCommitted(committed) }
        return changed.size + deleted.size
    }

    fun commitAsync() {
        viewModelScope.launch { commit() }
    }

    suspend fun commit() {
        val repo = prefs.repo ?: return
        try {
            val n = pushChanges(repo, withBuildFiles = false)
            chat.add(
                NoticeMsg(
                    "commit",
                    "$n change(s) to $repo",
                    if (n == 0) "Nothing new to commit." else "Committed.",
                )
            )
        } catch (e: Exception) {
            chat.add(NoticeMsg("commit", "error", e.message ?: "Commit failed."))
        }
    }

    // ---------- Build ----------

    fun buildAsync() {
        viewModelScope.launch { startBuild() }
    }

    /** Pushes the project, starts the APK workflow, and waits for its run to appear. */
    suspend fun startBuild() {
        val repo = prefs.repo ?: return
        if (buildStarting) return
        buildStarting = true
        buildMessage = "Saving project..."
        // Started now, while the app is on screen, so Android allows the background watcher.
        startWatch(System.currentTimeMillis())
        try {
            pushChanges(repo, withBuildFiles = true)
            val gh = GitHub(prefs.githubToken.orEmpty())
            buildMessage = "Starting build..."
            val dispatchedAt = gh.dispatch(repo, gh.defaultBranch(repo))
            buildMessage = "Queued. Waiting for GitHub to start it..."

            var run: GhRun? = null
            for (i in 1..20) {
                delay(3_000)
                run = gh.latestRunSince(repo, dispatchedAt)
                if (run != null) break
            }
            if (run == null) {
                buildMessage = "Build started, but the run is not visible yet. Check the Actions tab."
                return
            }
            upsert(run)
            runningRunId = run.id
            buildMessage = null
            startPolling()
        } catch (e: Exception) {
            buildMessage = e.message ?: "Build could not start."
            stopWatch()
        } finally {
            buildStarting = false
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (runningRunId != null) {
                refreshRun()
                delay(5_000)
            }
        }
    }

    suspend fun refreshRun() {
        val id = runningRunId ?: return
        val repo = prefs.repo ?: return
        try {
            val run = GitHub(prefs.githubToken.orEmpty()).getRun(repo, id)
            val item = upsert(run)
            if (selectedRun?.id == id) {
                selectedRun = item
                loadDetail(item)
            }
            if (run.status == "completed") {
                runningRunId = null
                buildMessage = null
            }
        } catch (e: Exception) {
            runningRunId = null
            buildMessage = e.message ?: "Lost connection to the build."
        }
    }

    private fun upsert(run: GhRun): BuildRun {
        val status = when {
            run.status != "completed" -> "running"
            run.conclusion == "success" -> "success"
            else -> "failed"
        }
        val item = BuildRun(run.id, run.number, status, run.url)
        val i = builds.indexOfFirst { it.id == run.id }
        if (i >= 0) builds[i] = item else builds.add(0, item)
        return item
    }

    private fun startWatch(since: Long) {
        try {
            val ctx = getApplication<Application>()
            ctx.startForegroundService(
                Intent(ctx, BuildWatchService::class.java).putExtra(BuildWatchService.EXTRA_SINCE, since)
            )
        } catch (e: Exception) {
            // Background watching is optional. The build still runs and the app still polls.
        }
    }

    private fun stopWatch() {
        try {
            val ctx = getApplication<Application>()
            ctx.stopService(Intent(ctx, BuildWatchService::class.java))
        } catch (e: Exception) {
            // Nothing to stop.
        }
    }

    // ---------- Run details: steps, log, APK ----------

    fun openRun(run: BuildRun) {
        selectedRun = run
        jobs = emptyList()
        logTail = null
        detailError = null
        apkMessage = null
        viewModelScope.launch { loadDetail(run) }
    }

    fun closeRun() {
        selectedRun = null
        jobs = emptyList()
        logTail = null
        detailError = null
        apkMessage = null
    }

    private suspend fun loadDetail(run: BuildRun) {
        val repo = prefs.repo ?: return
        if (jobs.isEmpty()) detailLoading = true
        try {
            val gh = GitHub(prefs.githubToken.orEmpty())
            val list = gh.listJobs(repo, run.id)
            jobs = list
            detailError = null
            if (run.status != "running" && logTail == null) {
                val job = list.firstOrNull { it.conclusion == "failure" } ?: list.firstOrNull()
                if (job != null) logTail = gh.jobLogTail(repo, job.id, 150)
            }
        } catch (e: Exception) {
            detailError = e.message ?: "Could not load the build details."
        } finally {
            detailLoading = false
        }
    }

    fun prepareApkAsync(run: BuildRun) {
        viewModelScope.launch { prepareApk(run) }
    }

    /** Downloads the run's APK artifact into app storage. The UI then offers Install. */
    private suspend fun prepareApk(run: BuildRun) {
        val repo = prefs.repo ?: return
        if (apkBusy) return
        apkBusy = true
        apkMessage = "Looking for the APK..."
        try {
            val gh = GitHub(prefs.githubToken.orEmpty())
            val artifact = gh.listArtifacts(repo, run.id).firstOrNull { it.name == "debug-apk" && !it.expired }
                ?: throw ApiException(404, "No APK found for this run. It may have expired.")
            apkMessage = "Downloading..."
            val dir = File(getApplication<Application>().filesDir, "apks").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val zip = File(dir, "download.zip")
            withContext(Dispatchers.IO) { zip.outputStream().use { out -> gh.downloadArtifact(repo, artifact.id, out) } }
            val apk = File(dir, "app-run${run.number}.apk")
            withContext(Dispatchers.IO) { extractApk(zip, apk) }
            zip.delete()
            readyApk = apk
            readyApkRunId = run.id
            apkMessage = null
        } catch (e: Exception) {
            apkMessage = e.message ?: "Could not download the APK."
        } finally {
            apkBusy = false
        }
    }

    private fun extractApk(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    target.outputStream().use { out -> zin.copyTo(out) }
                    return
                }
            }
        }
        throw ApiException(0, "There is no APK file inside the download.")
    }

    // ---------- Account ----------

    fun signOut() {
        pollJob?.cancel()
        stopWatch()
        prefs.clear()
        chat.clear()
        chatStore.clear()
        builds.clear()
        committed = emptyMap()
        store.saveCommitted(emptyMap())
        File(getApplication<Application>().filesDir, "apks").deleteRecursively()
        runningRunId = null
        buildMessage = null
        selectedRun = null
        jobs = emptyList()
        logTail = null
        readyApk = null
        readyApkRunId = null
        githubLogin = ""
        aiModel = prefs.aiModel
        onboarded = false
        screen = Screen.Home
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

private fun defaultFiles(): Map<String, String> = linkedMapOf(
    "main.kt" to MAIN_KT,
    "utils.kt" to UTILS_KT,
    "index.html" to INDEX_HTML,
    "README.md" to README_MD,
)

private val MAIN_KT =
    "// Entry point\npackage com.example.sample\n\nfun main() {\n" +
    "    val name = \"CollabCode\"\n" +
    "    println(\"Hello, \$name\")\n" +
    "    val n = 16\n" +
    "    println(\"sqrt = \${Math.sqrt(n.toDouble())}\")\n" +
    "}\n"

private const val UTILS_KT = "fun add(a: Int, b: Int): Int = a + b\n"

private const val INDEX_HTML =
    "<!doctype html><html><body style=\"font-family:sans-serif;padding:20px\">" +
    "<h1>Hello from CollabCode</h1>" +
    "<button onclick=\"this.textContent='Tapped!'\">Tap me</button>" +
    "</body></html>\n"

private const val README_MD = "# CollabCode Sample\n\nA small project for testing the UI.\n"
