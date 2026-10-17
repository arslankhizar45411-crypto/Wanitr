package com.wani.terminal

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Terminal mein aayi hui inline image (wani-show ya OSC 1337 se). */
class TermImage(
    val id: Long,
    val name: String,
    val bytes: ByteArray,
    val bitmap: ImageBitmap
)

/**
 * Proot ke andar bash chalata hai. Agar rootfs mein `script` ho (Debian mein hota hai)
 * to asli PTY milti hai: nano, vim, top, colors, Ctrl+C, Tab, history sab chalte hain.
 */
class ShellSession(private val context: Context) {

    /** Session ki pehchaan (Compose key ke liye). */
    val id: Int = nextSessionId.getAndIncrement()

    private class Outgoing(val data: String, val echo: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = TerminalBuffer(Config.MAX_TEXT_CHARS)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private val intentionalStops = ConcurrentHashMap.newKeySet<Process>()
    private var nextImageId = 1L

    private var process: Process? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var viewing = false
    private var bannerShown = false

    private val _text = MutableStateFlow(AnnotatedString(""))
    val text: StateFlow<AnnotatedString> = _text.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _pty = MutableStateFlow(false)
    val pty: StateFlow<Boolean> = _pty.asStateFlow()

    private val _alt = MutableStateFlow(false)
    val altScreen: StateFlow<Boolean> = _alt.asStateFlow()

    private val _images = MutableStateFlow<List<TermImage>>(emptyList())
    val images: StateFlow<List<TermImage>> = _images.asStateFlow()

    init {
        buffer.onReply = { reply -> send(reply) }
        buffer.onImage = { bytes, name -> addImage(bytes, name) }
        // UI ko 40ms mein ek hi dafa update karo, taake tez output par app na atke
        scope.launch {
            for (ignored in dirty) {
                // Terminal khuli na ho to render nahi karte: AI screen par CPU aur lag bachta hai
                if (viewing) {
                    _text.value = buffer.snapshot(MAX_RENDER_ROWS)
                    _alt.value = buffer.isAltScreen()
                }
                delay(RENDER_INTERVAL_MS)
            }
        }
        // Input hamesha usi order mein jaye jis order mein dabaya gaya
        scope.launch {
            for (msg in outbox) writeOut(msg)
        }
    }

    /**
     * Terminal ka size badalta hai (keyboard khulna, rotation). Buffer ka grid bhi badalta hai
     * aur chal rahi shell ki PTY ka size bhi (nano/htop naye size par redraw hote hain).
     */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(20, 300)
        val r = newRows.coerceIn(5, 100)
        if (c == cols && r == rows) return
        cols = c
        rows = r
        buffer.resize(c, r)
        dirty.trySend(Unit)
        if (_pty.value && _running.value) {
            scope.launch { applyPtySize(c, r) }
        }
    }

    /** Shell ka tty path (startInternal mein /tmp/wani-tty mein likha jata hai). */
    private fun ttyPath(): String? {
        val f = File(ProotRunner.rootfsDir(context), "tmp/wani-tty")
        val path = runCatching { f.readText().trim() }.getOrNull() ?: return null
        return path.takeIf { Regex("/dev/[A-Za-z0-9/_.-]+").matches(it) }
    }

    /** PTY ka window size set karta hai. Ye kernel ko SIGWINCH bhejta hai, to apps khud redraw karte hain. */
    private suspend fun applyPtySize(c: Int, r: Int) {
        val tty = ttyPath() ?: return
        runCatching {
            ProotRunner.capture(context, "stty -F '$tty' cols $c rows $r 2>/dev/null", timeoutSec = 10)
        }
    }

    /** Arrow / Home / End jaisi keys: app ke mode (DECCKM) ke hisaab se sahi sequence. */
    fun sendKey(normal: String, application: String? = null) {
        val seq = if (application != null && buffer.appCursorKeys()) application else normal
        send(seq)
    }

    /**
     * Clipboard ka text shell ko bhejta hai. Bracketed paste on ho to poora text ek saath
     * jaata hai, taake multi-line paste har line par alag command na chalaye.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val body = text.replace("\r\n", "\n").replace('\n', '\r')
        if (_pty.value && buffer.bracketedPasteMode()) {
            send("\u001B[200~$body\u001B[201~")
        } else {
            send(body, echo = !_pty.value)
        }
    }

    /** Shell chalu karta hai. Agar pehle se chal raha ho to kuch nahi karta. */
    fun ensureRunning() {
        scope.launch { startInternal() }
    }

    fun restart() {
        scope.launch {
            stopInternal()
            startInternal()
        }
    }

    fun interrupt() {
        if (_pty.value && _running.value) {
            send("\u0003")
        } else {
            appendSystem("^C")
            restart()
        }
    }

    /** Theme badalne par output dobara banao, taake naye rang lagein. */
    /** TerminalScreen khuli ho to true. Band ho to render pause. */
    fun setViewing(v: Boolean) {
        viewing = v
        if (v) dirty.trySend(Unit)
    }

    fun refreshTheme() {
        dirty.trySend(Unit)
    }

    fun clear() {
        buffer.clear()
        dirty.trySend(Unit)
    }

    fun clearImages() {
        _images.value = emptyList()
    }

    /** Shell ko input bhejta hai. echo=true sirf bina-PTY mode mein local echo dikhata hai. */
    fun send(data: String, echo: Boolean = false) {
        outbox.trySend(Outgoing(data, echo))
    }

    fun shutdown() {
        scope.launch { stopInternal() }
    }

    /** Session hamesha ke liye band: shell rok kar coroutines bhi khatam. */
    fun dispose() {
        scope.launch {
            stopInternal()
            delay(2000)
            scope.cancel()
        }
    }

    /** Screen par abhi jo text hai, aakhri [lines] lines (AI ke tool ke liye). */
    fun recentText(lines: Int = 60): String = buffer.snapshot(lines.coerceIn(5, 300)).text

    /** AI ke command aur output ko terminal mein rang ke saath dikhata hai. */
    fun logAi(text: String) {
        val clean = text.replace("\u001B", "").replace("\r", "").take(4000)
        val body = clean.lines().joinToString("\r\n") { "\u001B[36m$it\u001B[0m" }
        appendRaw("\r\n$body\r\n")
    }

    private fun addImage(bytes: ByteArray, name: String) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            scope.launch { appendSystem("[could not decode image: $name]") }
            return
        }
        val item = TermImage(nextImageId++, name, bytes, bmp.asImageBitmap())
        _images.value = (_images.value + item).takeLast(30)
    }

    private fun writeOut(msg: Outgoing) {
        val o = out
        if (o == null) {
            appendSystem("[shell is not running. Tap Restart]")
            return
        }
        if (msg.echo && !_pty.value) appendRaw(msg.data)
        try {
            o.write(msg.data.toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (e: IOException) {
            appendSystem("[write failed: ${e.message}]")
        }
    }

    @Synchronized
    private fun startInternal() {
        if (process?.isAlive == true) return

        val problem = ProotRunner.problem(context)
        if (problem != null) {
            appendSystem("[$problem]")
            return
        }

        val usePty = ProotRunner.hasPty(context)
        // Grid ko current size par set karo (pichla screen clear hota hai, scrollback rehta hai)
        buffer.resetGrid(cols, rows)

        // Shell apna tty path /tmp/wani-tty mein likhti hai, taake resize par stty use ho sake
        File(ProotRunner.rootfsDir(context), "tmp/wani-tty").delete()
        val args = if (usePty) {
            listOf(
                "script", "-qfc",
                "tty > /tmp/wani-tty 2>/dev/null; stty cols $cols rows $rows 2>/dev/null; exec ${Config.SHELL} -i",
                "/dev/null"
            )
        } else {
            listOf(Config.SHELL, "-i")
        }

        val p = try {
            ProotRunner.builder(context, args, if (usePty) "xterm-256color" else "dumb").start()
        } catch (e: IOException) {
            appendSystem("[shell failed to start: ${e.message}]")
            return
        }

        if (!bannerShown) {
            bannerShown = true
            appendRaw(BANNER)
        }
        process = p
        out = p.outputStream
        _pty.value = usePty
        _running.value = true
        scope.launch { readOutput(p) }
        scope.launch { watchExit(p) }
    }

    @Synchronized
    private fun stopInternal() {
        val p = process
        if (p != null) intentionalStops.add(p)
        try {
            out?.close()
        } catch (_: IOException) {
            // band karte waqt error ignore
        }
        p?.destroy()
        process = null
        out = null
        _running.value = false
        if (p != null) {
            scope.launch {
                delay(1500)
                if (p.isAlive) p.destroyForcibly()
            }
        }
    }

    private fun readOutput(p: Process) {
        val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
        val buf = CharArray(4096)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                appendRaw(String(buf, 0, n))
            }
        } catch (e: IOException) {
            // process band hone par normal hai
        }
    }

    private fun watchExit(p: Process) {
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            -1
        }
        synchronized(this) {
            if (process === p) {
                process = null
                out = null
                _running.value = false
            }
        }
        if (!intentionalStops.remove(p)) {
            appendSystem("[shell exited (exit code $code)]")
        }
    }

    private fun appendRaw(chunk: String) {
        buffer.write(chunk)
        dirty.trySend(Unit)
    }

    private fun appendSystem(msg: String) {
        appendRaw("\r\n\u001B[33m$msg\u001B[0m\r\n")
    }

    companion object {
        private val nextSessionId = AtomicInteger(1)
        private const val RENDER_INTERVAL_MS = 120L
        private const val MAX_RENDER_ROWS = 600

        private val ART = """
            | __        ___    _   _ ___
            | \ \      / / \  | \ | |_ _|
            |  \ \ /\ / / _ \ |  \| || |
            |   \ V  V / ___ \| |\  || |
            |    \_/\_/_/   \_\_| \_|___|
        """.trimMargin()

        private val BANNER =
            "\u001B[32m" + ART + "\u001B[0m\r\n\r\n" +
                "Welcome to Wani Terminal v2.0\r\n" +
                "Tap the screen to type. Keys go straight to the shell.\r\n" +
                "AI: tap AI, use the AI cell button, or type @ai <question>.\r\n" +
                "Setup: run wani-setup-storage to link Downloads.\r\n\r\n"
    }
}
