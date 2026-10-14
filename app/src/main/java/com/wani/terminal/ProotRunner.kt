package com.wani.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object ProotRunner {

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun loaderBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libprootloader.so")

    fun rootfsDir(context: Context): File = File(context.filesDir, Config.ROOTFS_DIR_NAME)

    /** Symlink khud exist karta ho to true (target host par na bhi ho). */
    fun pathExists(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Agar proot chalane mein koi masla ho to wajah, warna null. */
    fun problem(context: Context): String? {
        val proot = prootBinary(context)
        return if (!pathExists(proot)) {
            "libproot.so not found. Put the arm64 proot binary at app/src/main/jniLibs/arm64-v8a/libproot.so and rebuild the APK."
        } else {
            null
        }
    }

    /** Rootfs mein `script` (PTY ke liye) maujood hai ya nahi. */
    fun hasPty(context: Context): Boolean {
        val root = rootfsDir(context)
        return pathExists(File(root, "usr/bin/script")) || pathExists(File(root, "bin/script"))
    }

    /** Rootfs ke andar command chalane ke liye ProcessBuilder banata hai. */
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

    /** Ek command chalata hai, har output line par callback deta hai, aur exit code return karta hai. */
    suspend fun runOnce(context: Context, args: List<String>, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val process = builder(context, args).start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(onLine)
            }
            process.waitFor()
        }
}
