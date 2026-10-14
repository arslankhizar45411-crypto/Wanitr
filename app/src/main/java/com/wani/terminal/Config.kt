package com.wani.terminal

/**
 * ROOTFS_URL / ROOTFS_SHA256 build time par app/build.gradle.kts se aate hain
 * (GitHub repo variables ya -ProotfsUrl / -ProotfsSha256 se). Yahan default placeholder hai.
 */
object Config {
    /** Arm64 Debian rootfs (.tar.xz) ka URL. */
    val ROOTFS_URL: String = BuildConfig.ROOTFS_URL

    /** Us file ka SHA-256 (64 hex characters). Placeholder ho to install nahi hoga. */
    val ROOTFS_SHA256: String = BuildConfig.ROOTFS_SHA256

    const val ROOTFS_DIR_NAME = "rootfs"
    const val SHELL = "/bin/bash"
    const val MAX_TEXT_CHARS = 60_000
}
