package com.wani.terminal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed class SetupState {
    object NotInstalled : SetupState()
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : SetupState()
    object Verifying : SetupState()
    data class Extracting(val files: Int) : SetupState()
    data class Bootstrapping(val message: String) : SetupState()
    object Ready : SetupState()
    data class Error(val message: String) : SetupState()
}

class RootfsManager(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir = context.filesDir
    private val rootfsDir = File(baseDir, Config.ROOTFS_DIR_NAME)
    private val archive = File(baseDir, "rootfs.tar.xz")
    private val partial = File(baseDir, "rootfs.tar.xz.part")
    private val extractDir = File(baseDir, "rootfs.extracting")
    private val doneMarker = File(baseDir, "setup.done")
    private val installing = AtomicBoolean(false)

    private val _state = MutableStateFlow<SetupState>(
        if (isReady()) SetupState.Ready else SetupState.NotInstalled
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        // Pehle se installed rootfs mein helper commands ko update kar do (naye version ke liye)
        if (isReady()) runCatching { writeHelpers() }
    }

    private fun isExtracted(): Boolean = ProotRunner.pathExists(File(rootfsDir, "bin/bash"))

    fun isReady(): Boolean = doneMarker.isFile && isExtracted()

    /** Download, verify, extract, aur bootstrap karta hai. Dobara call karne par wahin se jaari hota hai. */
    suspend fun install() {
        if (!installing.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { runInstall() }
        } finally {
            installing.set(false)
        }
    }

    private suspend fun runInstall() {
        try {
            ProotRunner.problem(appContext)?.let { error(it) }

            if (!isExtracted()) {
                check(!Config.ROOTFS_URL.contains("example.com")) {
                    "ROOTFS_URL is not set. Edit Config.kt or set the ROOTFS_URL repository variable on GitHub."
                }
                check(!Config.ROOTFS_SHA256.all { it == '0' }) {
                    "ROOTFS_SHA256 is not set. Edit Config.kt or set the ROOTFS_SHA256 repository variable on GitHub."
                }

                if (!archive.isFile || !verifyArchive()) {
                    archive.delete()
                    download()
                    _state.value = SetupState.Verifying
                    if (!verifyArchive()) {
                        archive.delete()
                        error("SHA-256 does not match. The download is corrupt or the link is wrong.")
                    }
                }

                _state.value = SetupState.Extracting(0)
                extract()
                archive.delete()
                prepareRootfs()
            }

            bootstrap()
            doneMarker.writeText("ok")
            _state.value = SetupState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = SetupState.Error(e.message ?: e.toString())
        }
    }

    /**
     * Setup ka aakhri hissa. Koi package (python, matplotlib waghera) yahan install NAHI hota:
     * user khud jo chahe install karega. Yahan sirf helper commands aur storage folder bante hain.
     */
    private fun bootstrap() {
        _state.value = SetupState.Bootstrapping("Configuring the terminal")
        writeHelpers()
        File(rootfsDir, "sdcard").mkdirs()
        _state.value = SetupState.Bootstrapping("Setup complete")
    }

    /** wani-show aur wani-install-charts ko rootfs ke /usr/local/bin mein copy karta hai. */
    fun writeHelpers() {
        val bin = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        for (name in listOf("wani-show", "wani-install-charts", "wani-chart-demo", "wani-setup-storage")) {
            val dest = File(bin, name)
            appContext.assets.open(name).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
        }
    }

    private suspend fun download() {
        val conn = URL(Config.ROOTFS_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var done = 0L
            _state.value = SetupState.Downloading(0L, total)

            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = SetupState.Downloading(done, total)
                    }
                }
            }
            archive.delete()
            if (!partial.renameTo(archive)) {
                throw IOException("Could not rename the downloaded file")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyArchive(): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archive).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(Config.ROOTFS_SHA256, ignoreCase = true)
    }

    private fun extract() {
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        var count = 0

        FileInputStream(archive).use { fis ->
            XZInputStream(BufferedInputStream(fis)).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val target = safeChild(extractDir, entry.name)
                        when {
                            entry.isDirectory -> target.mkdirs()

                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                Files.deleteIfExists(target.toPath())
                                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                            }

                            entry.isLink -> {
                                target.parentFile?.mkdirs()
                                Files.deleteIfExists(target.toPath())
                                val src = safeChild(extractDir, entry.linkName)
                                try {
                                    Files.createLink(target.toPath(), src.toPath())
                                } catch (e: Exception) {
                                    // Android par hard link na bane to copy kar do
                                    src.copyTo(target, overwrite = true)
                                    target.setExecutable(src.canExecute(), false)
                                }
                            }

                            entry.isFile -> {
                                target.parentFile?.mkdirs()
                                // Pehle se maujood symlink ko follow na karein (bahar likhne se bachne ke liye)
                                Files.deleteIfExists(target.toPath())
                                FileOutputStream(target).use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0x49) != 0) {
                                    target.setExecutable(true, false)
                                }
                                target.setReadable(true, false)
                            }

                            else -> { /* device nodes skip */ }
                        }
                        count++
                        if (count % 200 == 0) _state.value = SetupState.Extracting(count)
                    }
                }
            }
        }
        _state.value = SetupState.Extracting(count)

        check(ProotRunner.pathExists(File(extractDir, "bin/bash"))) {
            "bin/bash not found in rootfs. Check the rootfs URL and format"
        }
        rootfsDir.deleteRecursively()
        check(extractDir.renameTo(rootfsDir)) { "Could not rename the rootfs folder" }
    }

    /** DNS, mount points, aur apt ki zaroori settings (bina inke apt-get update fail hota hai). */
    private fun prepareRootfs() {
        for (d in listOf("dev", "proc", "sys", "tmp", "root", "etc", "usr/local/bin", "sdcard")) {
            File(rootfsDir, d).mkdirs()
        }
        File(rootfsDir, "tmp").apply {
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        val resolv = File(rootfsDir, "etc/resolv.conf")
        Files.deleteIfExists(resolv.toPath())
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        val hosts = File(rootfsDir, "etc/hosts")
        if (!ProotRunner.pathExists(hosts)) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        val aptDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptDir.mkdirs()
        File(aptDir, "99wani").writeText("APT::Sandbox::User \"root\";\n")
    }

    /**
     * Archive ke path ko bahar nikalne se rokta hai (../ aur symlink escape).
     * Archive ki root entry ("./") ko allow karta hai.
     */
    private fun safeChild(base: File, name: String): File {
        val root = base.canonicalFile
        val lexicalRoot = base.absoluteFile.normalize()
        val child = File(base, name)
        val lexical = child.absoluteFile.normalize()
        if (!lexical.toPath().startsWith(lexicalRoot.toPath())) {
            throw IOException("Unsafe path in archive: $name")
        }
        if (lexical == lexicalRoot) return child
        var existing: File? = lexical.parentFile
        while (existing != null && !existing.exists()) existing = existing.parentFile
        val realParent = existing?.canonicalFile ?: root
        if (!realParent.toPath().startsWith(root.toPath())) {
            throw IOException("Unsafe symlink escape in archive: $name")
        }
        return child
    }
}
