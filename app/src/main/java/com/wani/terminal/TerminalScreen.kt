package com.wani.terminal

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import java.io.File

/** Chhupa hua marker: keyboard isi ke zariye khulta hai aur Backspace pakda jata hai. */
private const val SENTINEL = "\u200B"

fun startTerminalService(context: Context) {
    ContextCompat.startForegroundService(context, android.content.Intent(context, TerminalService::class.java))
}

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
            ?: throw IllegalStateException("Could not create a Downloads entry")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open the file for writing")
        return "Saved to Downloads/$safe"
    }
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    dir.mkdirs()
    val file = File(dir, safe)
    file.writeBytes(bytes)
    return "Saved: ${file.absolutePath}"
}

@Composable
fun TerminalScreen(
    shell: ShellSession,
    onNavigate: (Screen) -> Unit,
    onAskAi: (String) -> Unit
) {
    val text by shell.text.collectAsState()
    val running by shell.running.collectAsState()
    val pty by shell.pty.collectAsState()
    val alt by shell.altScreen.collectAsState()
    val images by shell.images.collectAsState()

    // Chhupa hua input. Hamesha SENTINEL par rehta hai.
    var field by remember { mutableStateOf(SENTINEL) }
    var ctrlKey by remember { mutableStateOf(false) }
    var altKey by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<TermImage?>(null) }
    // Jo user ne is line par type kiya (sirf @ai pakadne ke liye)
    val typedLine = remember { StringBuilder() }

    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val barScroll = rememberScrollState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("M", TermStyle).size }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Theme badle to terminal ke purane rang bhi naye ho jayen
    LaunchedEffect(IsDarkTheme) { shell.refreshTheme() }

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

    /** Control/escape sequence bhejta hai (Esc, arrows, Ctrl+X waghera). */
    fun sendRaw(seq: String) {
        if (seq.startsWith("\u001B") || (seq.length == 1 && seq[0] < ' ')) typedLine.clear()
        follow = true
        shell.send(seq)
    }

    /** Typed text bhejta hai, Ctrl/Alt sticky keys ke saath. PTY na ho to local echo bhi hota hai. */
    fun sendTyped(typed: String) {
        val modified = ctrlKey || altKey
        val out = encodeTyped(typed, ctrlKey, altKey)
        ctrlKey = false
        altKey = false
        if (!modified) typedLine.append(typed)
        follow = true
        shell.send(out, echo = !pty)
    }

    fun sendBackspace() {
        if (typedLine.isNotEmpty()) typedLine.deleteCharAt(typedLine.length - 1)
        follow = true
        shell.send("\u007F")
    }

    fun sendEnter() {
        typedLine.clear()
        follow = true
        if (pty) shell.send("\r") else shell.send("\n", echo = true)
    }

    /** Enter: agar line "@ai ..." hai to shell ko nahi, AI ko bhejo. */
    fun submitLine() {
        val line = typedLine.toString().trim()
        if (pty && (line == "@ai" || line.startsWith("@ai "))) {
            typedLine.clear()
            shell.send("\u0015") // readline: current line saaf karo
            onAskAi(line.removePrefix("@ai").trim())
            return
        }
        sendEnter()
    }

    /** Hidden field ka har badlao: naye characters shell ko, khaali field par Backspace. */
    fun onFieldChange(newValue: String) {
        val hadSentinel = newValue.contains(SENTINEL)
        val typed = newValue.replace(SENTINEL, "")
        if (!hadSentinel) sendBackspace()
        if (typed.isNotEmpty()) sendTyped(typed)
        field = SENTINEL
    }

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
            keyboardActions = KeyboardActions(onSend = { submitLine() }),
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
                    if (alt) "Full-screen app" else "Tap the screen to type",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            Box(modifier = Modifier.clickable { if (!running) { startTerminalService(ctx); shell.restart() } }) {
                StatusPill(running)
            }
            TextButton(onClick = { onAskAi("") }) {
                Text("AI", color = Accent, fontWeight = FontWeight.Bold)
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("⋮", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy all output") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(text.text))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = {
                            menuOpen = false
                            sendTyped(clipboard.getText()?.text ?: "")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear screen") },
                        onClick = {
                            menuOpen = false
                            shell.clear()
                            if (pty) sendRaw("\u000C")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear graphics") },
                        onClick = {
                            menuOpen = false
                            shell.clearImages()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Install charts (matplotlib)") },
                        onClick = {
                            menuOpen = false
                            if (pty) shell.send("wani-install-charts\r")
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
                    DropdownMenuItem(
                        text = { Text("AI settings") },
                        onClick = {
                            menuOpen = false
                            onNavigate(Screen.SETTINGS)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("GitHub build") },
                        onClick = {
                            menuOpen = false
                            onNavigate(Screen.GITHUB)
                        }
                    )
                }
            }
        }

        // Terminal output: tap karne par keyboard khulta hai. Lambi lines side scroll hoti hain.
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

            // Size pata chalne ke baad shell start karo, taake PTY ka size sahi ho
            LaunchedEffect(cols, rows) {
                shell.setSize(cols, rows)
                if (!shell.running.value) startTerminalService(ctx)
            }

            Text(
                text = text,
                style = termTextStyle(),
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
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

        // Sirf 7 buttons, ek hi line mein, barabar size ke
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton("Esc", Modifier.weight(1f)) { sendRaw("\u001B") }
            KeyButton("Tab", Modifier.weight(1f)) { if (pty) sendRaw("\t") else sendTyped("  ") }
            KeyButton("Ctrl", Modifier.weight(1f), active = ctrlKey) { ctrlKey = !ctrlKey }
            KeyButton("↑", Modifier.weight(1f)) { sendRaw("\u001B[A") }
            KeyButton("↓", Modifier.weight(1f)) { sendRaw("\u001B[B") }
            KeyButton("⌫", Modifier.weight(1f)) { sendBackspace() }
            KeyButton("AI", Modifier.weight(1f), accent = true) { onAskAi("") }
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
                        .getOrElse { "Not saved: ${it.message}" }
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
