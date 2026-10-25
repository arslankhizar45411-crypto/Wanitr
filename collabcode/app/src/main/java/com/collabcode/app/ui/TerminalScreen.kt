package com.collabcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.core.AnsiScreen
import com.collabcode.app.terminal.TerminalEngine
import com.collabcode.app.terminal.TerminalSetup
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0F1115)
private val TermBar = Color(0xFF171A20)
private val TermFg = Color(0xFFE6E8EE)
private val TermMuted = Color(0xFF8B909C)
private val TermGreen = Color(0xFF7FDBCA)
private const val TERM_ROWS = 40

private val Ansi16 = listOf(
    Color(0xFF3B3F46), Color(0xFFCC6666), Color(0xFFB5BD68), Color(0xFFF0C674),
    Color(0xFF81A2BE), Color(0xFFB294BB), Color(0xFF8ABEB7), Color(0xFFC5C8C6),
    Color(0xFF666B73), Color(0xFFFF6B6B), Color(0xFFC2E87D), Color(0xFFFFD866),
    Color(0xFF82AAFF), Color(0xFFD3A6F2), Color(0xFF7FDBCA), Color(0xFFFFFFFF),
)

@Composable
fun TerminalPage() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }
    val charW = remember(density) { measurer.measure("M", style).size.width.toFloat().coerceAtLeast(1f) }
    val padPx = with(density) { 8.dp.toPx() }
    var widthPx by remember { mutableStateOf(0) }
    val cols = if (widthPx > 0) ((widthPx - 2 * padPx) / charW).toInt().coerceIn(20, 200) else 60
    var confirmReset by remember { mutableStateOf(false) }

    val setup = TerminalEngine.setup

    // Opening the page sets Debian up the first time and starts the shell.
    LaunchedEffect(widthPx > 0) {
        if (widthPx > 0) TerminalEngine.open(context, cols, TERM_ROWS)
    }

    Column(Modifier.fillMaxSize().background(TermBg).onSizeChanged { widthPx = it.width }) {
        when (setup) {
            is TerminalSetup.Running -> SetupView(setup)
            is TerminalSetup.Failed -> FailedView(setup.message) {
                TerminalEngine.open(context, cols, TERM_ROWS)
            }
            else -> ShellView(cols = cols, style = style, onReset = { confirmReset = true })
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset Debian?",
            message = "This deletes the Debian system and everything installed inside it. " +
                "Your project files are not deleted. You can set Debian up again.",
            confirmLabel = "Reset",
            onConfirm = { confirmReset = false; TerminalEngine.resetDebian(context) },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun SetupView(state: TerminalSetup.Running) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Setting up Debian", color = TermFg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(state.step, color = TermMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        val f = state.fraction
        if (f != null) {
            LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "This downloads a large file once. Use Wi-Fi if you can. " +
                "You can leave this screen. Setup continues in the background.",
            color = TermMuted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { TerminalEngine.cancelSetup() }) { Text("Cancel") }
    }
}

@Composable
private fun FailedView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Debian setup stopped", color = TermFg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Color(0xFFFF6B6B), fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun ColumnScope.ShellView(cols: Int, style: TextStyle, onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = TerminalEngine.screen
    val tick = TerminalEngine.tick
    val alive = TerminalEngine.shellAlive
    val listState = rememberLazyListState()
    var field by remember { mutableStateOf("") }

    val cursorLine = screen.cursorLine()
    val cursorCol = screen.cursorColumn()
    val count = cursorLine + 1

    fun flush() {
        if (field.isNotEmpty()) {
            TerminalEngine.write(field)
            field = ""
        }
    }

    fun sendLine() {
        TerminalEngine.write(field + "\r")
        field = ""
    }

    // Header
    Row(
        Modifier.fillMaxWidth().background(TermBar).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (alive) "Debian · running" else "Debian · stopped",
            color = TermMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { TerminalEngine.restart(context, cols, TERM_ROWS) }) { Text("Restart") }
        TextButton(onClick = onReset) { Text("Reset") }
    }

    // Screen
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(count) { i ->
                val idx = count - 1 - i
                val view = screen.line(idx)
                Text(
                    text = buildLine(view, if (idx == cursorLine && alive) cursorCol else -1),
                    style = style,
                    color = TermFg,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
        if (listState.firstVisibleItemIndex > 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(36.dp).clip(CircleShape)
                    .background(TermBar).clickable { scope.launch { listState.scrollToItem(0) } },
                contentAlignment = Alignment.Center
            ) { Text("↓", color = TermFg, fontSize = 16.sp) }
        }
    }

    // Keys
    Row(
        Modifier.fillMaxWidth().background(TermBar).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        KeyChip("Tab") { flush(); TerminalEngine.write("\t") }
        KeyChip("Ctrl+C") { flush(); TerminalEngine.write("\u0003") }
        KeyChip("Ctrl+D") { flush(); TerminalEngine.write("\u0004") }
        KeyChip("Ctrl+L") { flush(); TerminalEngine.write("\u000c") }
        KeyChip("Esc") { flush(); TerminalEngine.write("\u001b") }
        KeyChip("↑") { flush(); TerminalEngine.write("\u001b[A") }
        KeyChip("↓") { flush(); TerminalEngine.write("\u001b[B") }
        KeyChip("←") { flush(); TerminalEngine.write("\u001b[D") }
        KeyChip("→") { flush(); TerminalEngine.write("\u001b[C") }
        KeyChip("⌫") { flush(); TerminalEngine.write("\u007f") }
        KeyChip("|") { field += "|" }
        KeyChip("/") { field += "/" }
        KeyChip("-") { field += "-" }
        KeyChip("~") { field += "~" }
    }

    // Input
    Row(
        Modifier.fillMaxWidth().background(TermBar).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$", color = TermGreen, style = style)
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = style.copy(color = TermFg),
            cursorBrush = SolidColor(TermFg),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(onSend = { sendLine() }),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (field.isEmpty()) Text("type a command", color = TermMuted, style = style)
                    inner()
                }
            }
        )
        TextButton(onClick = { sendLine() }) { Text("Send") }
    }

    // Keeps the parser and the list in sync with the width of the screen.
    LaunchedEffect(cols) { screen.resize(cols, TERM_ROWS) }
    // tick is read above so this view redraws when the screen changes.
    if (tick < 0) Spacer(Modifier.height(0.dp))
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.defaultMinSize(minWidth = 40.dp).height(34.dp).clip(shape).background(Color(0xFF232830))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = TermFg, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
}

// ---------- drawing ----------

private fun cube(x: Int): Int = if (x == 0) 0 else 55 + x * 40

private fun paletteColor(code: Int, fallback: Color): Color = when {
    code == AnsiScreen.DEFAULT_COLOR -> fallback
    (code and AnsiScreen.RGB_FLAG) != 0 -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
    code < 16 -> Ansi16[code]
    code < 232 -> {
        val c = code - 16
        Color(cube(c / 36), cube((c / 6) % 6), cube(c % 6))
    }
    else -> {
        val v = 8 + (code - 232) * 10
        Color(v, v, v)
    }
}

private fun cellKey(v: AnsiScreen.LineView, i: Int, cursorCol: Int): Long {
    val fg = if (i < v.fg.size) v.fg[i] else -1
    val bg = if (i < v.bg.size) v.bg[i] else -1
    val at = if (i < v.attr.size) v.attr[i] else 0
    val cur = if (i == cursorCol) 1L else 0L
    return (fg + 1).toLong() or ((bg + 1).toLong() shl 26) or (at.toLong() shl 52) or (cur shl 55)
}

private fun buildLine(view: AnsiScreen.LineView, cursorCol: Int): AnnotatedString {
    val text = if (cursorCol >= 0 && cursorCol >= view.text.length) view.text.padEnd(cursorCol + 1) else view.text
    if (text.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val key = cellKey(view, i, cursorCol)
            var j = i + 1
            while (j < text.length && cellKey(view, j, cursorCol) == key) j++

            val fg = if (i < view.fg.size) view.fg[i] else AnsiScreen.DEFAULT_COLOR
            val bg = if (i < view.bg.size) view.bg[i] else AnsiScreen.DEFAULT_COLOR
            val at = if (i < view.attr.size) view.attr[i] else 0
            var fgC = paletteColor(fg, TermFg)
            var bgC = paletteColor(bg, TermBg)
            val inverse = (at and AnsiScreen.ATTR_INVERSE) != 0
            if (inverse) {
                val t = fgC
                fgC = bgC
                bgC = t
            }
            val isCursor = i == cursorCol
            if (isCursor) {
                fgC = TermBg
                bgC = TermFg
            }
            val showBg = inverse || isCursor || bg != AnsiScreen.DEFAULT_COLOR
            val style = SpanStyle(
                color = fgC,
                background = if (showBg) bgC else Color.Unspecified,
                fontWeight = if ((at and AnsiScreen.ATTR_BOLD) != 0) FontWeight.Bold else null,
                textDecoration = if ((at and AnsiScreen.ATTR_UNDERLINE) != 0) TextDecoration.Underline else null
            )
            withStyle(style) { append(text.substring(i, j)) }
            i = j
        }
    }
}
