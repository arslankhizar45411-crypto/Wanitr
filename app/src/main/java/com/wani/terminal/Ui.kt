package com.wani.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** App ki screens. */
enum class Screen { TERMINAL, AI, SETTINGS, GITHUB }

@Composable
fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as WaniApp
    val setup by app.rootfs.state.collectAsState()
    val approval by Approvals.current.collectAsState()
    val live by LivePages.current.collectAsState()
    var screen by remember { mutableStateOf(Screen.TERMINAL) }

    // Pehli dafa app khulne par setup khud shuru ho jata hai
    LaunchedEffect(Unit) {
        if (app.rootfs.state.value == SetupState.NotInstalled) {
            app.appScope.launch { app.rootfs.install() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (setup == SetupState.Ready) {
            when (screen) {
                Screen.TERMINAL -> {
                    val sessionList by app.sessions.list.collectAsState()
                    val sessionIndex by app.sessions.index.collectAsState()
                    val current = sessionList[sessionIndex.coerceIn(0, sessionList.lastIndex)]
                    // Session badle to poora terminal naye sire se bane (naye shell ke saath)
                    key(current.id) {
                        TerminalScreen(
                            shell = current,
                            sessions = app.sessions,
                            onNavigate = { screen = it },
                            onAskAi = { prompt ->
                                if (prompt.isNotEmpty()) app.ai.send(prompt)
                                screen = Screen.AI
                            }
                        )
                    }
                }
                Screen.AI -> AiScreen(
                    ai = app.ai,
                    onBack = { screen = Screen.TERMINAL },
                    onOpenSettings = { screen = Screen.SETTINGS }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = app.settings,
                    onBack = { screen = Screen.TERMINAL },
                    onOpenGitHub = { screen = Screen.GITHUB }
                )
                Screen.GITHUB -> GitHubScreen(
                    settings = app.settings,
                    onBack = { screen = Screen.SETTINGS }
                )
            }
        } else {
            SetupScreen(setup) {
                app.appScope.launch { app.rootfs.install() }
            }
        }

        live?.let { LivePageOverlay(it) }
        approval?.let { ApprovalDialog(it) }
    }
}

// ---------------------------------------------------------------- Setup screen

private data class StepInfo(val title: String, val detail: String, val progress: Float?)

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1048576.0)

private fun describe(state: SetupState): StepInfo = when (state) {
    is SetupState.Downloading -> {
        if (state.totalBytes > 0) {
            StepInfo(
                "Downloading Debian",
                "${mb(state.doneBytes)} / ${mb(state.totalBytes)} MB",
                (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
            )
        } else {
            StepInfo("Downloading Debian", "${mb(state.doneBytes)} MB", null)
        }
    }
    is SetupState.Verifying -> StepInfo("Verifying download", "Checking SHA-256 checksum", null)
    is SetupState.Extracting -> StepInfo("Extracting files", "${state.files} files extracted", null)
    is SetupState.Bootstrapping -> StepInfo("Configuring", state.message, null)
    is SetupState.NotInstalled -> StepInfo("Preparing", "Starting setup", null)
    is SetupState.Ready -> StepInfo("Opening terminal", "", 1f)
    is SetupState.Error -> StepInfo("Setup failed", state.message, null)
}

@Composable
fun SetupScreen(state: SetupState, onRetry: () -> Unit) {
    val info = describe(state)
    val isError = state is SetupState.Error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "❯_",
            color = Accent,
            fontSize = 36.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text("Wani Terminal", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                info.title,
                color = if (isError) ErrorRed else TextMain,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (!isError) {
                Spacer(Modifier.height(12.dp))
                val p = info.progress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Chip
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Chip
                    )
                }
            }
            if (info.detail.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    info.detail,
                    color = if (isError) TextMain else Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
            if (isError) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                ) {
                    Text("Retry", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            if (isError) "Fix the problem above, then tap Retry."
            else "This takes a few minutes. The terminal opens automatically.",
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------- Approval dialog

@Composable
private fun ApprovalDialog(req: ApprovalRequest) {
    AlertDialog(
        onDismissRequest = { Approvals.respond(false) },
        title = { Text(req.title, fontSize = 16.sp, color = TextMain) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    req.detail,
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { Approvals.respond(true) }) {
                Text("Yes", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { Approvals.respond(false) }) {
                Text("No", color = Muted)
            }
        }
    )
}
