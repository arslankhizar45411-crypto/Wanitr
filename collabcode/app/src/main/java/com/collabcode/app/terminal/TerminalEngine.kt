package com.collabcode.app.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.collabcode.app.TerminalService
import com.collabcode.app.core.AnsiScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed interface TerminalSetup {
    object Idle : TerminalSetup
    data class Running(val step: String, val fraction: Float?) : TerminalSetup
    data class Failed(val message: String) : TerminalSetup
    object Ready : TerminalSetup
}

data class ExecResult(val exit: Int, val output: String, val timedOut: Boolean, val truncated: Boolean)

/**
 * The one terminal of the app. It lives for the whole process, so the shell keeps running
 * while the screen is closed. The foreground service keeps the process alive in the background.
 */
object TerminalEngine {
    private const val EXEC_LIMIT = 20_000

    var setup by mutableStateOf<TerminalSetup>(TerminalSetup.Idle)
        private set
    var shellAlive by mutableStateOf(false)
        private set

    /** Changes whenever the screen changes. The UI reads it to redraw. */
    var tick by mutableLongStateOf(0L)
        private set

    val screen = AnsiScreen(60, 40, 3000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val execProcs = ConcurrentHashMap.newKeySet<Process>()
    private var setupJob: Job? = null
    private var ticker: Job? = null
    private var process: Process? = null
    private var stdin: OutputStream? = null

    fun isInstalled(ctx: Context): Boolean = RootfsManager.isInstalled(ctx)

    /** Opens the terminal: sets Debian up the first time, then starts the shell. */
    fun open(ctx: Context, cols: Int, rows: Int) {
        val app = ctx.applicationContext
        if (setupJob?.isActive == true) return
        startTicker()
        if (RootfsManager.isInstalled(app)) {
            setup = TerminalSetup.Ready
            if (!shellAlive) startShell(app, cols, rows)
        } else {
            startSetup(app, cols, rows)
        }
    }

    private fun startSetup(app: Context, cols: Int, rows: Int) {
        updateService(app, setupRunning = true)
        setupJob = scope.launch {
            try {
                setup = TerminalSetup.Running("Starting...", null)
                RootfsManager.install(app) { step, fraction -> setup = TerminalSetup.Running(step, fraction) }
                setup = TerminalSetup.Ready
                startShell(app, cols, rows)
            } catch (e: CancellationException) {
                setup = TerminalSetup.Idle
                throw e
            } catch (e: Exception) {
                setup = TerminalSetup.Failed(e.message ?: "Setup failed.")
            } finally {
                updateService(app, setupRunning = false)
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
    }

    @Synchronized
    private fun startShell(app: Context, cols: Int, rows: Int) {
        if (shellAlive) return
        val usePty = Proot.hasScript(app)
        screen.clear()
        screen.resize(cols, rows)
        screen.setNewlineMode(!usePty)
        screen.setResponder { reply -> write(reply) }
        val pb = ProcessBuilder(Proot.shellCommand(app, cols, rows, usePty)).redirectErrorStream(true)
        Proot.configure(app, pb)
        val p = try {
            pb.start()
        } catch (e: IOException) {
            screen.feed("Could not start the shell: ${e.message}\r\n")
            return
        }
        process = p
        stdin = p.outputStream
        shellAlive = true
        updateService(app, setupRunning = false)
        scope.launch { readLoop(p, app) }
    }

    private fun readLoop(p: Process, app: Context) {
        try {
            InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    screen.feed(String(buf, 0, n))
                }
            }
        } catch (e: IOException) {
            // The process ended.
        }
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            -1
        }
        synchronized(this) {
            if (process === p || process == null) {
                screen.feed("\r\n[Shell ended with code $code. Tap Restart.]\r\n")
            }
            if (process === p) {
                process = null
                stdin = null
                shellAlive = false
            }
        }
        updateService(app, setupRunning = setupJob?.isActive == true)
    }

    /** Sends text to the shell. Writes happen in order on one thread. */
    fun write(text: String) {
        val out = stdin ?: return
        writeScope.launch {
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                // The shell is gone.
            }
        }
    }

    fun restart(ctx: Context, cols: Int, rows: Int) {
        val app = ctx.applicationContext
        synchronized(this) {
            val old = process
            process = null
            stdin = null
            shellAlive = false
            old?.destroyForcibly()
        }
        open(app, cols, rows)
    }

    fun resetDebian(ctx: Context) {
        val app = ctx.applicationContext
        setupJob?.cancel()
        synchronized(this) {
            val old = process
            process = null
            stdin = null
            shellAlive = false
            old?.destroyForcibly()
        }
        killExec()
        scope.launch {
            RootfsManager.reset(app)
            screen.clear()
            setup = TerminalSetup.Idle
            updateService(app, setupRunning = false)
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var last = -1L
            while (isActive) {
                val v = screen.version()
                if (v != last) {
                    last = v
                    tick = v
                }
                delay(60)
            }
        }
    }

    private fun updateService(app: Context, setupRunning: Boolean) {
        if (shellAlive || setupRunning) TerminalService.start(app) else TerminalService.stop(app)
    }

    fun killExec() {
        execProcs.forEach { it.destroyForcibly() }
    }

    /** Runs one command to the end and returns its output. Used for commands the user approved. */
    suspend fun exec(app: Context, command: String, timeoutSec: Long = 120): ExecResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(Proot.hostCommand(app, listOf("/bin/bash", "-lc", command))).redirectErrorStream(true)
        Proot.configure(app, pb)
        val proc = pb.start()
        execProcs.add(proc)
        try {
            proc.outputStream.close()
        } catch (e: IOException) {
            // Nothing to close.
        }
        val out = StringBuilder()
        var truncated = false
        val reader = launch {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            val room = EXEC_LIMIT - out.length
                            if (room > 0) out.append(buf, 0, minOf(n, room))
                            if (n > room) truncated = true
                        }
                    }
                }
            } catch (e: IOException) {
                // The process ended.
            }
        }
        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (proc.isAlive) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    proc.destroyForcibly()
                    break
                }
                delay(100)
            }
            withTimeoutOrNull(3000) { reader.join() }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            execProcs.remove(proc)
        }
        val exit = if (proc.waitFor(2, TimeUnit.SECONDS)) proc.exitValue() else -1
        val text = synchronized(out) { out.toString() }
        ExecResult(if (timedOut) -1 else exit, text, timedOut, truncated)
    }
}
