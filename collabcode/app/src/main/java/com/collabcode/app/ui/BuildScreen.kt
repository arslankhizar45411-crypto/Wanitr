package com.collabcode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.data.ApkInstaller
import com.collabcode.app.data.GhStep
import com.collabcode.app.model.AppState
import com.collabcode.app.model.BuildRun

@Composable
fun BuildScreen(app: AppState, onBuild: () -> Unit) {
    val run = app.selectedRun
    if (run == null) RunList(app, onBuild) else RunDetail(app, run)
}

@Composable
private fun RunList(app: AppState, onBuild: () -> Unit) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Button(
            onClick = onBuild,
            enabled = !app.buildStarting && app.runningRunId == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (app.buildStarting) "Starting..." else "Build APK") }

        app.buildMessage?.let {
            Text(it, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = cc.muted)
        }
        if (app.builds.isEmpty()) {
            Text(
                "No builds yet. Tap Build APK to compile your project on GitHub.",
                Modifier.padding(top = 18.dp),
                fontSize = 14.sp,
                color = cc.muted
            )
        }
        app.builds.forEach { run ->
            Row(
                Modifier.fillMaxWidth().clickable { app.openRun(run) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Debug APK #${run.number}", fontSize = 15.sp)
                    Text("Tap for steps, log and the APK", fontSize = 12.sp, color = cc.muted)
                }
                StatusPill(run.status)
            }
            HorizontalDivider(color = cc.border)
        }
    }
}

@Composable
private fun RunDetail(app: AppState, run: BuildRun) {
    val cc = LocalCC.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Debug APK #${run.number}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            StatusPill(run.status)
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.closeRun() }, modifier = Modifier.weight(1f)) { Text("Back") }
            OutlinedButton(onClick = { uriHandler.openUri(run.url) }, modifier = Modifier.weight(1f)) {
                Text("Open on GitHub")
            }
        }

        if (run.status == "success") {
            val apk = app.readyApk
            val ready = apk != null && app.readyApkRunId == run.id
            Button(
                onClick = {
                    if (ready && apk != null) ApkInstaller.install(context, apk) else app.prepareApkAsync(run)
                },
                enabled = !app.apkBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(
                    when {
                        app.apkBusy -> "Downloading..."
                        ready -> "Install APK"
                        else -> "Download APK"
                    }
                )
            }
            app.apkMessage?.let {
                Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = cc.muted)
            }
        }

        if (app.detailLoading) {
            Text("Loading...", Modifier.padding(top = 14.dp), fontSize = 13.sp, color = cc.muted)
        }
        app.detailError?.let {
            Text(it, Modifier.padding(top = 14.dp), fontSize = 13.sp, color = cc.red)
        }

        app.jobs.forEach { job ->
            Text(
                job.name,
                Modifier.padding(top = 16.dp, bottom = 4.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            job.steps.forEach { step ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(stepIcon(step), Modifier.width(22.dp), color = stepColor(step, cc), fontSize = 13.sp)
                    Text(step.name, fontSize = 13.sp)
                }
            }
        }

        val log = app.logTail
        if (log != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Log (last lines)", fontSize = 13.sp, color = cc.muted)
                TextButton(onClick = { clipboard.setText(AnnotatedString(log)) }) { Text("Copy log") }
            }
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, cc.border, shape)
                    .background(cc.surface).horizontalScroll(rememberScrollState()).padding(10.dp)
            ) {
                Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }
    }
}

private fun stepIcon(step: GhStep): String = when (step.conclusion) {
    "success" -> "✓"
    "failure" -> "✕"
    "skipped", "cancelled" -> "–"
    null -> if (step.status == "in_progress") "…" else "·"
    else -> "·"
}

private fun stepColor(step: GhStep, cc: CCColors): Color = when (step.conclusion) {
    "success" -> cc.green
    "failure" -> cc.red
    else -> cc.muted
}
