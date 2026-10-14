package com.wani.terminal

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0D0E14)
private val Panel = Color(0xFF171923)
private val TermBg = Color(0xFF11121A)
private val Chip = Color(0xFF242736)
private val Accent = Color(0xFF5EE0A8)
private val Amber = Color(0xFFE0AF68)
private val ErrorRed = Color(0xFFF7768E)
private val TextMain = Color(0xFFD7DAE3)
private val Muted = Color(0xFF7C8196)

private val TermStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = TextMain
)

fun startTerminalService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java))
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WaniApp
    val setup by app.rootfs.state.collectAsState()
    val ready = setup == SetupState.Ready

    // Pehli dafa app khulne par setup khud shuru ho jata hai
    LaunchedEffect(Unit) {
        if (app.rootfs.state.value == SetupState.NotInstalled) {
            app.appScope.launch { app.rootfs.install() }
        }
    }

    if (ready) {
        TerminalScreen(app.shell)
    } else {
        SetupScreen(setup) {
            app.appScope.launch { app.rootfs.install() }
        }
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
    is SetupState.Extracting -> StepInfo(
        "Extracting files",
        "${state.files} files done. This can take a few minutes",
        null
    )
    is SetupState.Bootstrapping -> StepInfo("Configuring system", state.message, null)
    is SetupState.NotInstalled -> StepInfo("Getting ready", "", null)
    is SetupState.Ready -> StepInfo("Done", "", 1f)
    is SetupState.Error -> StepInfo("Setup failed", state.message, null)
}

@Composable
fun SetupScreen(state: SetupState, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "❯_",
                color = Accent,
                fontSize = 44.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text("Wani Terminal", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Setting up your Linux environment", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))

            val info = describe(state)
            val isError = state is SetupState.Error

            Surface(
                color = Panel,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        info.title,
                        color = if (isError) ErrorRed else TextMain,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (!isError) {
                        Spacer(Modifier.height(14.dp))
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
                        Spacer(Modifier.height(12.dp))
                        Text(
                            info.detail,
                            color = if (isError) TextMain else Muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (isError) 8 else 2
                        )
                    }
                    if (isError) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Terminal screen

@Composable
fun TerminalScreen(shell: ShellSession) {
    val text by shell.text.collectAsState()
    val running by shell.running.collectAsState()
    val pty by shell.pty.collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("M", TermStyle).size }

    // Output badhe to neeche scroll karo, lekin agar user ne upar scroll kiya ho to nahi
    LaunchedEffect(scroll.maxValue) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) follow = scroll.value >= scroll.maxValue - 24
        }
    }

    fun submit() {
        val line = input
        input = ""
        follow = true
        shell.send(line + "\n", echo = true)
    }

    // Likha hua text pehle shell ko bhejo, phir special key (taake Tab completion waghera sahi chale)
    fun key(seq: String) {
        if (input.isNotEmpty()) {
            shell.send(input)
            input = ""
        }
        follow = true
        shell.send(seq)
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "❯_",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Wani Terminal", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("by Mecotina", color = Muted, fontSize = 11.sp)
            }
            StatusPill(running)
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("⋮", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy all output") },
                        onClick = {
                            clipboard.setText(AnnotatedString(text.text))
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = {
                            val clip = clipboard.getText()?.text
                            if (clip != null) input += clip.replace("\r", "").replace("\n", " ")
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear screen") },
                        onClick = {
                            shell.clear()
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Restart shell") },
                        onClick = {
                            menuOpen = false
                            startTerminalService(ctx)
                            shell.restart()
                        }
                    )
                }
            }
        }

        // Terminal output
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermBg)
        ) {
            val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
            val cols = ((constraints.maxWidth - 2 * padPx) / cell.width).toInt().coerceAtLeast(20)
            val rows = ((constraints.maxHeight - 2 * padPx) / cell.height).toInt().coerceAtLeast(5)

            // Size pata chalne ke baad hi shell start karo, taake PTY ka size sahi ho.
            // Keyboard khulne/band hone par rows badalte hain: sirf size update hota hai,
            // service dobara start nahi hoti (pehle har dafa start hoti thi).
            LaunchedEffect(cols, rows) {
                shell.setSize(cols, rows)
                if (!shell.running.value) startTerminalService(ctx)
            }

            Text(
                text = text,
                style = TermStyle,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(10.dp)
            )
        }

        // Special keys
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!running) {
                KeyChip("Restart", accent = true) {
                    startTerminalService(ctx)
                    shell.restart()
                }
            }
            if (pty) KeyChip("Esc") { key("\u001B") }
            KeyChip("Tab") {
                if (pty) {
                    key("\t")
                } else {
                    input += "  "
                }
            }
            KeyChip("Ctrl+C") {
                input = ""
                shell.interrupt()
            }
            if (pty) KeyChip("Ctrl+D") { key("\u0004") }
            KeyChip("Ctrl+L") {
                shell.clear()
                if (pty) shell.send("\u000C")
            }
            if (pty) {
                KeyChip("↑") { key("\u001B[A") }
                KeyChip("↓") { key("\u001B[B") }
                KeyChip("←") { key("\u001B[D") }
                KeyChip("→") { key("\u001B[C") }
                KeyChip("Home") { key("\u001B[H") }
                KeyChip("End") { key("\u001B[F") }
            }
            KeyChip("⌫") {
                if (input.isNotEmpty()) {
                    input = input.dropLast(1)
                } else if (pty) {
                    key("\u007F")
                }
            }
            listOf(
                "|", "~", "/", "-", "_", "*", "&", "\$", "'", "\"", ";", ":",
                "\\", "<", ">", "!", "?", "#", "@", "%", "=", "+", "(", ")",
                "[", "]", "{", "}"
            ).forEach { ch ->
                KeyChip(ch) { input += ch }
            }
        }

        // Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Type a command", color = Muted) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TextMain
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Chip,
                    cursorColor = Accent,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { submit() },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
            ) {
                Text("Send", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val c = if (running) Accent else Amber
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(c)
        )
        Spacer(Modifier.width(6.dp))
        Text(if (running) "running" else "stopped", color = c, fontSize = 11.sp)
    }
}

@Composable
private fun KeyChip(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) Accent else Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (accent) Color.Black else TextMain,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal
        )
    }
}
