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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
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
    sessions: SessionManager,
    onNavigate: (Screen) -> Unit,
    onAskAi: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as WaniApp

    val text by shell.text.collectAsState()
    val running by shell.running.collectAsState()
    val pty by shell.pty.collectAsState()
    val alt by shell.altScreen.collectAsState()
    val images by shell.images.collectAsState()
    val sessionList by sessions.list.collectAsState()
    val sessionIndex by sessions.index.collectAsState()

    // Shortcut bar ki list (Settings se badalti hai)
    val shortcutIds by remember { mutableStateOf(app.settings.shortcutKeys) }

    // Chhupa hua input. Field mein sirf wahi text hota hai jo abhi shell ko nahi gaya.
    var fieldValue by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    // Wo text jo shell ko bhej diya gaya (SENTINEL ke baghair)
    var sent by remember { mutableStateOf("") }
    var ctrlKey by remember { mutableStateOf(false) }
    var altKey by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<TermImage?>(null) }
    var cellOpen by remember { mutableStateOf(false) }
    // Jo user ne is line par type kiya (sirf @ai pakadne ke liye)
    val typedLine = remember { StringBuilder() }

    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val barScroll = rememberScrollState()
    val sessScroll = rememberScrollState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("M", TermStyle).size }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Theme badle to terminal ke purane rang bhi naye ho jayen
    LaunchedEffect(IsDarkTheme) { shell.refreshTheme() }

    // Terminal khuli hai to hi screen render hoti hai
    DisposableEffect(Unit) {
        shell.setViewing(true)
        onDispose { shell.setViewing(false) }
    }

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

    /** Arrow/Home/End: app ke mode (DECCKM) ke hisaab se sequence bhejta hai. */
    fun sendKey(normal: String, application: String? = null) {
        typedLine.clear()
        follow = true
        shell.sendKey(normal, application)
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

    /** Ek DEL (Backspace) shell ko bhejta hai. Field ko nahi chhedta. */
    fun sendDel() {
        if (typedLine.isNotEmpty()) typedLine.deleteCharAt(typedLine.length - 1)
        follow = true
        shell.send("\u007F")
    }

    fun resetField() {
        fieldValue = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
        sent = ""
    }

    /**
     * Field ka naya text pichhle bheje hue text se milata hai. Jo hissa mit gaya uska DEL,
     * jo naya aaya uska text shell ko jaata hai. Ye IME (Gboard) ke saath bhi sahi chalta hai.
     */
    fun syncField(v: TextFieldValue, lostSentinel: Boolean) {
        val newText = v.text.replace(SENTINEL, "")
        val oldText = sent
        var p = 0
        val max = minOf(oldText.length, newText.length)
        while (p < max && oldText[p] == newText[p]) p++
        var dels = oldText.length - p
        // Khaali field par Backspace: marker hatta hai, text nahi. Ye bhi ek DEL hai.
        if (dels == 0 && lostSentinel) dels = 1
        repeat(dels) { sendDel() }
        val added = newText.substring(p)
        if (added.isNotEmpty()) sendTyped(added)
        sent = newText
        fieldValue = when {
            newText.isEmpty() -> TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            v.text.contains(SENTINEL) -> v
            else -> TextFieldValue(SENTINEL + newText, TextRange(SENTINEL.length + newText.length))
        }
    }

    /** Field mein jo bhi pada hai (composing text bhi) use abhi shell ko bhejo. */
    fun flushField() {
        syncField(fieldValue, lostSentinel = false)
    }

    /** Hidden field ka har badlao. Gboard jab word compose kar raha ho to intezaar karta hai. */
    fun onFieldChange(v: TextFieldValue) {
        val hadSentinel = fieldValue.text.contains(SENTINEL)
        fieldValue = v
        if (v.composition != null) return
        syncField(v, lostSentinel = hadSentinel && !v.text.contains(SENTINEL))
    }

    fun sendEnter() {
        flushField()
        typedLine.clear()
        resetField()
        follow = true
        if (pty) shell.send("\r") else shell.send("\n", echo = true)
    }

    /** Enter: agar line "@ai ..." hai to shell ko nahi, AI ko bhejo. */
    fun submitLine() {
        flushField()
        val line = typedLine.toString().trim()
        if (pty && (line == "@ai" || line.startsWith("@ai "))) {
            typedLine.clear()
            resetField()
            shell.send("\u0015") // readline: current line saaf karo
            onAskAi(line.removePrefix("@ai").trim())
            return
        }
        sendEnter()
    }

    /** Shortcut bar ke button ka kaam. */
    fun runShortcut(id: String) {
        when (id) {
            "esc" -> sendRaw("\u001B")
            "tab" -> if (pty) sendRaw("\t") else sendTyped("  ")
            "ctrl" -> ctrlKey = !ctrlKey
            "alt" -> altKey = !altKey
            "up" -> sendKey("\u001B[A", "\u001BOA")
            "down" -> sendKey("\u001B[B", "\u001BOB")
            "right" -> sendKey("\u001B[C", "\u001BOC")
            "left" -> sendKey("\u001B[D", "\u001BOD")
            "enter" -> submitLine()
            "ctrlc" -> if (pty) sendRaw("\u0003") else shell.interrupt()
            "ctrld" -> sendRaw("\u0004")
            "ctrlz" -> sendRaw("\u001A")
            "ctrll" -> sendRaw("\u000C")
            "home" -> sendKey("\u001B[H", "\u001BOH")
            "end" -> sendKey("\u001B[F", "\u001BOF")
            "pgup" -> sendRaw("\u001B[5~")
            "pgdn" -> sendRaw("\u001B[6~")
            "slash" -> sendTyped("/")
            "dash" -> sendTyped("-")
            "pipe" -> sendTyped("|")
            "tilde" -> sendTyped("~")
            "dot" -> sendTyped(".")
            "cell" -> cellOpen = !cellOpen
            "ai" -> onAskAi("")
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // Chhupa hua input (1dp). Ye focus mein rehta hai, isliye keyboard khulta hai.
        BasicTextField(
            value = fieldValue,
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
                            val clip: String = clipboard.getText()?.text ?: ""
                            if (clip.isNotEmpty()) {
                                typedLine.append(clip)
                                follow = true
                                shell.paste(clip)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New session") },
                        onClick = {
                            menuOpen = false
                            sessions.newSession()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Close this session") },
                        onClick = {
                            menuOpen = false
                            sessions.close(sessionIndex)
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
                        text = { Text(if (cellOpen) "Hide AI cell" else "Show AI cell") },
                        onClick = {
                            menuOpen = false
                            cellOpen = !cellOpen
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

        // Session tabs: 1 2 3 ... aur + naya session
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(sessScroll)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sessionList.forEachIndexed { i, _ ->
                KeyChip(label = "${i + 1}", active = i == sessionIndex) { sessions.select(i) }
            }
            KeyChip(label = "+") { sessions.newSession() }
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
                shell.resize(cols, rows)
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

            // AI cell: terminal ke upar hi tairta hai (terminal ka size nahi badalta)
            if (cellOpen) {
                AiCellPanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    onClose = { cellOpen = false }
                )
            }
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
                KeyChip(label = "Clear") { shell.clearImages() }
            }
        }

        // Shortcut bar: Settings se aap jo buttons chunte ho, wahi dikhte hain (scroll bhi hota hai)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(barScroll)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shortcutIds.forEach { id ->
                ShortcutButton(
                    label = ShortcutKeys.labelOf(id),
                    active = (id == "ctrl" && ctrlKey) || (id == "alt" && altKey),
                    accent = id == "ai"
                ) { runShortcut(id) }
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
                    scope.launch {
                        val msg = runCatching {
                            // Android 9 aur pehle par Downloads mein likhne ke liye storage permission chahiye
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !StoragePermission.request(ctx)) {
                                error("storage permission nahi mili")
                            }
                            saveToDownloads(ctx, img.name, img.bytes)
                        }.getOrElse { "Not saved: ${it.message}" }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
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

/** Shortcut bar ka button. Label ke around padding rakhta hai taake tap aasaan ho. */
@Composable
private fun ShortcutButton(label: String, active: Boolean, accent: Boolean, onClick: () -> Unit) {
    val bg = when {
        accent -> Accent
        active -> Accent.copy(alpha = 0.45f)
        else -> Chip
    }
    val fg = if (accent || active) Color.Black else TextMain
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = if (accent || active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** Terminal ke upar chhota AI box: sawal poochho, jawab yahin dikhe, commands terminal mein chalen. */
@Composable
private fun AiCellPanel(modifier: Modifier, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as WaniApp
    val messages by app.ai.messages.collectAsState()
    val busy by app.ai.busy.collectAsState()
    var prompt by remember { mutableStateOf("") }
    val reply = messages.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.ERROR }?.text.orEmpty()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(Panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI cell", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (busy) Text("working...", color = Muted, fontSize = 11.sp)
            TextButton(onClick = onClose) { Text("Close", color = Muted) }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                reply.ifBlank { "Ask anything. AI commands run in the Linux shell and show up in the terminal." },
                color = TextMain,
                fontSize = 13.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask AI...", color = Muted) },
                textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    app.ai.send(prompt)
                    prompt = ""
                }
            ) {
                Text("Run", color = Accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}
