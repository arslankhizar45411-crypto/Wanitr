package com.collabcode.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.collabcode.app.data.GhRun
import com.collabcode.app.data.GitHub
import com.collabcode.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps watching a build while the app is in the background, then posts a
 * "build finished" notification and stops itself. It gives up after 45 minutes.
 */
class BuildWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground soon after startForegroundService, so do it first.
        Notifier.ensureChannels(this)
        val notification = Notifier.watching(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.WATCH_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.WATCH_ID, notification)
        }

        val since = intent?.getLongExtra(EXTRA_SINCE, 0L) ?: 0L
        val prefs = Prefs(applicationContext)
        val token = prefs.githubToken
        val repo = prefs.repo
        if (token.isNullOrBlank() || repo == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        job = scope.launch {
            val gh = GitHub(token)
            val deadline = System.currentTimeMillis() + 45L * 60L * 1000L
            var knownRunId: Long? = null
            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    val current = knownRunId
                    val run: GhRun? = if (current == null) gh.latestRunSince(repo, since) else gh.getRun(repo, current)
                    if (run != null) {
                        knownRunId = run.id
                        if (run.status == "completed") {
                            Notifier.buildFinished(applicationContext, run)
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Network blips are normal on a phone. Try again on the next round.
                }
                delay(6_000)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SINCE = "since"
    }
}
