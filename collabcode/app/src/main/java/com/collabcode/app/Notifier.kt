package com.collabcode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.collabcode.app.data.GhRun

object Notifier {
    const val CH_WATCH = "build_watch"
    const val CH_DONE = "build_done"
    const val WATCH_ID = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Build progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Build finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun watching(context: Context): Notification =
        Notification.Builder(context, CH_WATCH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CollabCode")
            .setContentText("Watching your build...")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    /** Shown when the run ends. Without notification permission, Android simply hides it. */
    fun buildFinished(context: Context, run: GhRun) {
        val ok = run.conclusion == "success"
        val title = if (ok) "Build #${run.number} finished" else "Build #${run.number} ${run.conclusion ?: "ended"}"
        val text = if (ok) "Open CollabCode to download the APK." else "Open CollabCode to read the log."
        val n = Notification.Builder(context, CH_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(run.id.toInt(), n)
        } catch (e: SecurityException) {
            // Notification permission was denied. The build result is still visible in the app.
        }
    }
}
