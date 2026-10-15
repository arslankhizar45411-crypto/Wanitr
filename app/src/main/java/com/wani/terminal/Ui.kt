package com.wani.terminal

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import java.io.File

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

/** Live typing ke liye marker. Field mein hamesha ye hota hai, taake Backspace pakda ja sake. */
private const val SENTINEL = "\u200B"

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
        "${state.files} files extract ho chuki hain (taqreeban 5500 hoti hain)",
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
                            maxLines = if (isError) 10 else 3
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

/** Ctrl+letter ka control code. */
private fun ctrlCode(ch: Char): String {
    val u = ch.uppercaseChar()
    return when {
        u in '@'..'_' -> (u.code - 0x40).toChar().toString()
        ch == ' ' -> "\u0000"
        ch == '?' -> "\u007F"
        else -> ch.toString()
    }
}

/** Typed text ko shell ke liye tayyar karta hai (Ctrl/Alt sticky keys ke saath). */
private fun encodeTyped(text: String, ctrl: Boolean, alt: Boolean): String {
    val sb = StringBuilder()
    for ((i, ch) in text.withIndex()) {
        if (i == 0 && alt) sb.append('\u001B')
        when {
            i == 0 && ctrl -> sb.append(ctrlCode(ch))
            ch == '\n' -> sb.append('\r')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Inline image ko Downloads folder mein save karta hai. Result message return karta hai. */
fun saveToDownloads(context: Context, name: String, bytes: ByteArray): String {
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image.png" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads mein entry nahi bani")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("File likhne mein masla")
        return "Downloads/$safe mein save ho gayi"
    }
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    dir.mkdirs()
    val file = File(dir, safe)
    file.writeBytes(bytes)
    return "Saved: ${file.absolutePath}"
}

@Composable
fun TerminalScreen(shell: ShellSession) {
    val text by shell.text.collectAsState()
    val running by shell.running.collectAsState()
    val pty by shell.pty.collectAsState()
    val alt by shell.altScreen.collectAsState()
    val images by shell.images.collectAsState()

    // Chhupa hua field: keyboard isi ke zariye khulta hai. Hamesha SENTINEL par rehta hai.
    var field by remember { mutableStateOf(SENTINEL) }
    var ctrlKey by remember { mutableStateOf(false) }
    var altKey by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<TermImage?>(null) }

    val scroll = rememberScrollState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("M", TermStyle).size }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun showKeyboard() {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Full-screen app (nano/top) ke time upar se dikhao, normal output mein neeche follow karo
    LaunchedEffect(alt) {
        if (alt) scroll.scrollTo(0)
    }
    LaunchedEffect(scroll.maxValue) {
        if (follow && !alt) scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) follow = scroll.value >= scroll.maxValue - 24
        }
    }

    /** Seedha key sequence bhejta hai (Esc, arrows, Ctrl+X waghera). */
    fun sendRaw(seq: String) {
        follow = true
        shell.send(seq)
    }

    /** Typed text bhejta hai, Ctrl/Alt sticky keys ke saath. PTY na ho to local echo bhi hota hai. */
    fun sendTyped(typed: String) {
        val out = encodeTyped(typed, ctrlKey, altKey)
        if (ctrlKey || altKey) {
            ctrlKey = false
            altKey = false
        }
        follow = true
        shell.send(out, echo = !pty)
    }

    fun sendEnter() {
        follow = true
        if (pty) shell.send("\r") else shell.send("\n", echo = true)
    }

    /** Hidden field ka har badlao: naye characters foran shell ko, Backspace DEL bhejta hai. */
    fun onFieldChange(newValue: String) {
        val hadSentinel = newValue.contains(SENTINEL)
        val typed = newValue.replace(SENTINEL, "")
        if (!hadSentinel) shell.send("\u007F")
        if (typed.isNotEmpty()) sendTyped(typed)
        field = SENTINEL
    }

    val extraKeys = listOf(
        "Esc" to "\u001B", "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C",
        "Home" to "\u001B[H", "End" to "\u001B[F", "PgUp" to "\u001B[5~", "PgDn" to "\u001B[6~",
        "Del" to "\u001B[3~", "Ins" to "\u001B[2~"
    )
    val ctrlKeys = listOf(
        "C" to "\u0003", "D" to "\u0004", "Z" to "\u001A", "O" to "\u000F", "X" to "\u0018",
        "S" to "\u0013", "W" to "\u0017", "K" to "\u000B", "G" to "\u0007", "L" to "\u000C",
        "A" to "\u0001", "E" to "\u0005", "R" to "\u0012", "U" to "\u0015", "T" to "\u0014"
    )
    val symbols = listOf(
        "|", "~", "/", "-", "_", "*", "&", "\$", "'", "\"", ";", ":", "\\", "<", ">",
        "!", "?", "#", "@", "%", "=", "+", "(", ")", "[", "]", "{", "}", "`"
    )

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // Chhupa hua input (1dp). Ye focus mein rehta hai, isliye keyboard khulta hai.
        BasicTextField(
            value = field,
            onValueChange = { onFieldChange(it) },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(onSend = { sendEnter() }),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
        )

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
                Text(
                    if (alt) "full-screen app" else "screen par tap karke type karein",
                    color = Muted,
                    fontSize = 11.sp
                )
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
                            val clip = clipboard.getText()?.text ?: ""
                            sendTyped(clip)
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear screen") },
                        onClick = {
                            shell.clear()
                            if (pty) sendRaw("\u000C")
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear graphics") },
                        onClick = {
                            shell.clearImages()
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Install charts (matplotlib)") },
                        onClick = {
                            if (pty) shell.send("wani-install-charts\r")
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

        // Terminal output: tap karne par keyboard khulta hai
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermBg)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showKeyboard() })
                }
        ) {
            val padPx = with(LocalDensity.current) { 10.dp.toPx() }
            val cols = ((constraints.maxWidth - 2 * padPx) / cell.width).toInt().coerceAtLeast(20)
            val rows = ((constraints.maxHeight - 2 * padPx) / cell.height).toInt().coerceAtLeast(5)

            // Size pata chalne ke baad shell start karo, taake PTY ka size sahi ho.
            // Keyboard khulne par size sirf store hota hai; service dobara start nahi hoti.
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

        // Graphics strip: wani-show se aayi images
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                images.forEach { img ->
                    Image(
                        bitmap = img.bitmap,
                        contentDescription = img.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { preview = img }
                    )
                }
                KeyChip("Clear") { shell.clearImages() }
            }
        }

        // Full-screen keys (sirf PTY mode mein)
        if (pty) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeyChip("Ctrl", active = ctrlKey) { ctrlKey = !ctrlKey }
                KeyChip("Alt", active = altKey) { altKey = !altKey }
                extraKeys.forEach { (label, seq) ->
                    KeyChip(label) { sendRaw(seq) }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ctrlKeys.forEach { (letter, seq) ->
                    KeyChip("^$letter") { sendRaw(seq) }
                }
            }
        }

        // Basic keys + symbols (dono modes)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!running) {
                KeyChip("Restart", accent = true) {
                    startTerminalService(ctx)
                    shell.restart()
                }
            }
            KeyChip("⌨") { showKeyboard() }
            KeyChip("Tab") {
                if (pty) sendRaw("\t") else sendTyped("  ")
            }
            KeyChip("⌫") { sendRaw("\u007F") }
            if (!pty) {
                KeyChip("^C") { shell.interrupt() }
            }
            symbols.forEach { s ->
                KeyChip(s) { sendTyped(s) }
            }
        }
    }

    // Image preview + save
    preview?.let { img ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(img.name, fontSize = 14.sp) },
            text = {
                Image(
                    bitmap = img.bitmap,
                    contentDescription = img.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val msg = runCatching { saveToDownloads(ctx, img.name, img.bytes) }
                        .getOrElse { "Save nahi hui: ${it.message}" }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }) {
                    Text("Save to Downloads")
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("Close") }
            }
        )
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
private fun KeyChip(label: String, accent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        accent -> Accent
        active -> Accent.copy(alpha = 0.45f)
        else -> Chip
    }
    val fg = if (accent || active) Color.Black else TextMain
    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (accent || active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
