package com.collabcode.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the app alive while Debian is being set up or while the shell is running. */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = Notifier.terminal(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.TERMINAL_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.TERMINAL_ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, TerminalService::class.java))
            } catch (e: Exception) {
                // Without the service the terminal still works while the app is open.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, TerminalService::class.java))
            } catch (e: Exception) {
                // Nothing to stop.
            }
        }
    }
}
