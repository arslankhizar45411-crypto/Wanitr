package com.wani.terminal

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

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
                _text.value = buffer.snapshot()
                _alt.value = buffer.isAltScreen()
                delay(RENDER_INTERVAL_MS)
            }
        }
        // Input hamesha usi order mein jaye jis order mein dabaya gaya
        scope.launch {
            for (msg in outbox) writeOut(msg)
        }
    }

    /** Terminal ka size (characters mein). Agli dafa shell start hone par lagu hota hai. */
    fun setSize(newCols: Int, newRows: Int) {
        cols = newCols.coerceIn(20, 300)
        rows = newRows.coerceIn(5, 100)
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

    private fun addImage(bytes: ByteArray, name: String) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            scope.launch { appendSystem("[image decode nahi hui: $name]") }
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

        val args = if (usePty) {
            listOf(
                "script", "-qfc",
                "stty cols $cols rows $rows 2>/dev/null; exec ${Config.SHELL} -i",
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
        private const val RENDER_INTERVAL_MS = 40L

        private val ART = """
            | __        ___    _   _ ___
            | \ \      / / \  | \ | |_ _|
            |  \ \ /\ / / _ \ |  \| || |
            |   \ V  V / ___ \| |\  || |
            |    \_/\_/_/   \_\_| \_|___|
        """.trimMargin()

        private val BANNER =
            "\u001B[32m" + ART + "\u001B[0m\r\n\r\n" +
                "Welcome to Wani Terminal v1.3 by Mecotina\r\n" +
                "Screen par tap karke keyboard kholein, keys seedha shell ko jaayengi.\r\n" +
                "Graphics: 'wani-show chart.png' | Charts: 'wani-install-charts' phir 'wani-chart-demo'\r\n\r\n"
    }
}
