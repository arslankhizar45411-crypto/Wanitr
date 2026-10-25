package com.collabcode.app.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class SetupException(message: String) : Exception(message)

/**
 * Downloads, checks and unpacks the Debian root filesystem.
 * Safety rules while unpacking: every path stays inside the root folder, a file is never
 * written through a symbolic link that leaves the root, and device files are skipped.
 */
object RootfsManager {
    fun baseDir(ctx: Context): File = File(ctx.filesDir, "debian").apply { mkdirs() }

    fun rootfsDir(ctx: Context): File = File(baseDir(ctx), "rootfs")

    fun isInstalled(ctx: Context): Boolean =
        File(baseDir(ctx), "installed.ok").exists() && File(rootfsDir(ctx), "bin/bash").exists()

    /** Removes the whole Debian folder without following symbolic links. */
    fun reset(ctx: Context) {
        deleteTree(baseDir(ctx))
    }

    suspend fun install(ctx: Context, onProgress: (String, Float?) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isInstalled(ctx)) return@withContext
        if (!File(Proot.nativeDir(ctx), "libproot.so").exists()) {
            throw SetupException("libproot.so is missing from the app. Install the APK again.")
        }
        val base = baseDir(ctx)
        val part = File(base, "rootfs.tar.xz.part")
        val archive = File(base, "rootfs.tar.xz")

        if (!archive.exists()) {
            download(DebianConfig.ROOTFS_URL, part, onProgress)
            onProgress("Checking SHA-256 checksum", null)
            val hex = sha256(part)
            if (!hex.equals(DebianConfig.ROOTFS_SHA256, ignoreCase = true)) {
                part.delete()
                throw SetupException("SHA-256 does not match. The download is corrupt or the link is wrong.")
            }
            if (!part.renameTo(archive)) throw SetupException("Could not move the download into place.")
        }

        val extracting = File(base, "rootfs.extracting")
        onProgress("Unpacking Debian", 0f)
        extract(archive, extracting, onProgress)

        val src = locateRoot(extracting)
            ?: throw SetupException("bin/bash was not found in the rootfs. Check the rootfs link and format.")
        val finalRoot = rootfsDir(ctx)
        deleteTree(finalRoot)
        if (!src.renameTo(finalRoot)) throw SetupException("Could not rename the rootfs folder.")
        deleteTree(extracting)

        onProgress("Preparing Debian", null)
        prepare(finalRoot)
        File(base, "installed.ok").writeText("1")
        archive.delete()
    }

    private suspend fun download(url: String, part: File, onProgress: (String, Float?) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        var current = url
        for (hop in 0..5) {
            val u = URL(current)
            if (u.protocol != "https") throw SetupException("Blocked a download that is not https.")
            val conn = u.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "CollabAgent/0.6")
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw SetupException("The download redirect had no address.")
                    current = URL(u, loc).toString()
                    continue
                }
                // 416 means the partial file already has every byte. The checksum decides.
                if (code == 416) return
                if (code != 200 && code != 206) throw SetupException("Download failed (HTTP $code).")

                val append = code == 206 && existing > 0
                if (!append) existing = 0L
                val remaining = conn.contentLengthLong
                val total = if (remaining > 0) existing + remaining else -1L
                if (total > DebianConfig.MAX_DOWNLOAD_BYTES) throw SetupException("The download is larger than expected.")
                val free = (part.parentFile ?: part).usableSpace
                if (total > 0 && free < total * 4) {
                    throw SetupException("Not enough free storage. About ${total * 4 / 1_000_000} MB is needed.")
                }

                var done = existing
                var lastUi = 0L
                FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 300) {
                                lastUi = now
                                val mb = done / 1_000_000
                                if (total > 0) {
                                    onProgress("Downloading Debian: $mb of ${total / 1_000_000} MB", done.toFloat() / total)
                                } else {
                                    onProgress("Downloading Debian: $mb MB", null)
                                }
                            }
                        }
                    }
                }
                return
            } catch (e: IOException) {
                throw SetupException("Network problem while downloading. Tap Try again to resume.")
            } finally {
                conn.disconnect()
            }
        }
        throw SetupException("Too many redirects.")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    private suspend fun extract(archive: File, target: File, onProgress: (String, Float?) -> Unit) {
        deleteTree(target)
        target.mkdirs()
        val rootCanon = target.canonicalFile
        val total = archive.length().toFloat().coerceAtLeast(1f)
        val counting = CountingInputStream(FileInputStream(archive))
        var lastUi = 0L
        try {
            TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry as? TarArchiveEntry ?: break
                    val rel = cleanName(entry.name) ?: continue
                    if (rel.isEmpty()) continue
                    writeEntry(tar, entry, File(target, rel), target, rootCanon)
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        lastUi = now
                        onProgress("Unpacking Debian", counting.count / total)
                    }
                }
            }
        } catch (e: IOException) {
            throw SetupException("Could not unpack the Debian archive: ${e.message}")
        }
    }

    /** Returns the cleaned relative path, an empty string for the root itself, or null if unsafe. */
    private fun cleanName(raw: String): String? {
        var n = raw.replace('\\', '/')
        while (n.startsWith("./")) n = n.removePrefix("./")
        n = n.trimStart('/').trimEnd('/')
        if (n.isEmpty() || n == ".") return ""
        if (n.split('/').any { it == ".." }) return null
        return n
    }

    private fun isInside(dir: File, rootCanon: File): Boolean {
        val c = try {
            dir.canonicalPath
        } catch (e: IOException) {
            return false
        }
        val r = rootCanon.path
        return c == r || c.startsWith(r + File.separator)
    }

    private fun ownerRwx(f: File) {
        f.setReadable(true, true)
        f.setWritable(true, true)
        f.setExecutable(true, true)
    }

    private fun applyMode(f: File, mode: Int) {
        f.setReadable(true, true)
        f.setWritable(true, true)
        if ((mode and 0x49) != 0) f.setExecutable(true, true)
    }

    private fun deleteLink(f: File) {
        try {
            Files.deleteIfExists(f.toPath())
        } catch (e: Exception) {
            // A real folder here is rare. The write that follows reports the problem.
        }
    }

    private fun writeEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, dest: File, target: File, rootCanon: File) {
        val parent = dest.parentFile ?: return
        parent.mkdirs()
        if (!isInside(parent, rootCanon)) return
        when {
            entry.isDirectory -> {
                if (!dest.exists()) dest.mkdirs()
                ownerRwx(dest)
            }
            entry.isSymbolicLink -> {
                deleteLink(dest)
                try {
                    Files.createSymbolicLink(dest.toPath(), Paths.get(entry.linkName))
                } catch (e: Exception) {
                    // A link that cannot be made is skipped.
                }
            }
            entry.isLink -> {
                val srcRel = cleanName(entry.linkName)
                val src = if (srcRel.isNullOrEmpty()) null else File(target, srcRel)
                val srcParent = src?.parentFile
                deleteLink(dest)
                if (src != null && srcParent != null && src.exists() && isInside(srcParent, rootCanon)) {
                    try {
                        Files.createLink(dest.toPath(), src.toPath())
                    } catch (e: Exception) {
                        try {
                            src.copyTo(dest, overwrite = true)
                            if (src.canExecute()) dest.setExecutable(true, true)
                        } catch (e2: Exception) {
                            // Skipped.
                        }
                    }
                }
            }
            entry.isFile -> {
                deleteLink(dest)
                FileOutputStream(dest).use { out -> tar.copyTo(out) }
                applyMode(dest, entry.mode)
            }
            else -> Unit
        }
    }

    /** Some archives have one top folder. Use whichever folder holds bin/bash. */
    private fun locateRoot(extracting: File): File? {
        fun hasBash(d: File) = File(d, "bin/bash").exists() || File(d, "usr/bin/bash").exists()
        if (hasBash(extracting)) return extracting
        val subs = extracting.listFiles()?.filter {
            it.isDirectory && !Files.isSymbolicLink(it.toPath())
        } ?: return null
        return subs.firstOrNull { hasBash(it) }
    }

    private fun writeFile(f: File, text: String) {
        f.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(f.toPath())
        } catch (e: Exception) {
            // Written below anyway.
        }
        f.writeText(text)
    }

    private fun prepare(root: File) {
        File(root, "root").mkdirs()
        File(root, "root/project").mkdirs()
        File(root, "tmp").mkdirs()
        writeFile(File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        writeFile(File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        // apt drops privileges to a user that does not exist under proot, so keep it as root.
        writeFile(File(root, "etc/apt/apt.conf.d/99collab-sandbox"), "APT::Sandbox::User \"root\";\n")
        val script = File(root, "usr/local/bin/collab-install-python")
        writeFile(
            script,
            "#!/bin/sh\n" +
                "# Installs Python 3 and pip in this Debian.\n" +
                "export DEBIAN_FRONTEND=noninteractive\n" +
                "apt-get update && apt-get install -y --no-install-recommends python3 python3-pip\n" +
                "python3 --version\n"
        )
        script.setExecutable(true, true)
    }

    fun deleteTree(dir: File) {
        val p = dir.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                ownerRwx(d.toFile())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.delete(f)
                } catch (e: IOException) {
                    // Keep going. The folder delete below reports what is left.
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                try {
                    Files.delete(d)
                } catch (e: IOException) {
                    // Ignored.
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
