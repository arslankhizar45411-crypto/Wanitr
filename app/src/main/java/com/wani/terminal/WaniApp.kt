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

    /** Sab terminal sessions (tabs). */
    lateinit var sessions: SessionManager
        private set

    /** Abhi dikhne wala shell. AI tools isi ko use karte hain. */
    val shell: ShellSession
        get() = sessions.current

    lateinit var settings: AppSettings
        private set

    lateinit var ai: AiChat
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        applyTheme(settings.darkTheme)
        rootfs = RootfsManager(this)
        sessions = SessionManager(this)
        ai = AiChat(this)
    }
}
