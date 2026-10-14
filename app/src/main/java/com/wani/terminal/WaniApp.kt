package com.wani.terminal

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WaniApp : Application() {

    // Download aur setup activity band hone par bhi chalta rahe
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var rootfs: RootfsManager
        private set

    lateinit var shell: ShellSession
        private set

    override fun onCreate() {
        super.onCreate()
        rootfs = RootfsManager(this)
        shell = ShellSession(this)
    }
}
