package com.wani.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Ek command ka result (AI tool ke liye). */
class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean)

object ProotRunner {

    private const val HOST_STORAGE = "/storage/emulated/0"
    private const val MAX_CAPTURE = 60_000

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun loaderBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libprootloader.so")

    fun rootfsDir(context: Context): File = File(context.filesDir, Config.ROOTFS_DIR_NAME)

    /** Symlink khud exist karta ho to true (target host par na bhi ho). */
    fun pathExists(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Agar proot chalane mein koi masla ho to wajah (English), warna null. */
    fun problem(context: Context): String? =
        if (!pathExists(prootBinary(context))) {
            "libproot.so not found in the APK. Add the arm64 proot binary to app/src/main/jniLibs/arm64-v8a and rebuild."
        } else {
            null
        }

    /** Rootfs mein `script` (PTY ke liye) maujood hai ya nahi. */
    fun hasPty(context: Context): Boolean {
        val root = rootfsDir(context)
        return pathExists(File(root, "usr/bin/script")) || pathExists(File(root, "bin/script"))
    }

    /** Rootfs ke andar command chalane ke liye ProcessBuilder. Phone storage /sdcard par bind hota hai. */
    fun builder(context: Context, args: List<String>, term: String = "dumb"): ProcessBuilder {
        val rootfs = rootfsDir(context)
        val tmp = File(context.filesDir, "proot-tmp").apply { mkdirs() }

        val cmd = mutableListOf(
            prootBinary(context).absolutePath,
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "-r", rootfs.absolutePath
        )
        for (dir in listOf("/dev", "/proc", "/sys")) {
            if (File(dir).exists()) {
                cmd.add("-b")
                cmd.add(dir)
            }
        }
        if (File(HOST_STORAGE).isDirectory) {
            File(rootfs, "sdcard").mkdirs()
            cmd.add("-b")
            cmd.add("$HOST_STORAGE:/sdcard")
        }
        cmd.addAll(
            listOf(
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "TERM=$term",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            )
        )
        cmd.addAll(args)

        return ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            val env = environment()
            val loader = loaderBinary(context)
            if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
            env["PROOT_TMP_DIR"] = tmp.absolutePath
            // Kuch Android devices par seccomp proot ko crash kar deta hai
            env["PROOT_NO_SECCOMP"] = "1"
        }
    }

    /**
     * AI ke liye: ek bash command chalata hai, output aur exit code return karta hai.
     * Timeout ke baad process khatam kar diya jata hai.
     */
    suspend fun capture(context: Context, command: String, timeoutSec: Long = 120): CommandResult =
        withContext(Dispatchers.IO) {
            val process = builder(context, listOf(Config.SHELL, "-c", command)).start()
            val out = StringBuilder()
            val reader = thread(isDaemon = true) {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            if (out.length < MAX_CAPTURE) out.append(buf, 0, n)
                        }
                    }
                }
            }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(3000)
            val text = synchronized(out) { out.toString() }
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                output = if (text.length >= MAX_CAPTURE) text + "\n[output truncated]" else text,
                timedOut = !finished
            )
        }
}
