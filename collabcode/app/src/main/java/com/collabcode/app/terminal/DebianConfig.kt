package com.collabcode.app.terminal

object DebianConfig {
    /** The same Debian arm64 root filesystem that Wani Terminal downloads. */
    const val ROOTFS_URL =
        "https://github.com/arslankhizar45411-crypto/wani-assets/releases/download/assets-v1/debian-arm64.tar.xz"

    /** Checked after the download. A wrong value stops the setup, so nothing bad is unpacked. */
    const val ROOTFS_SHA256 = "e237bec31d042c6aaa02a7d9a20381b6b354efb4c3c9f4688584a9889cdd3a25"

    const val MAX_DOWNLOAD_BYTES = 2_000_000_000L
}
