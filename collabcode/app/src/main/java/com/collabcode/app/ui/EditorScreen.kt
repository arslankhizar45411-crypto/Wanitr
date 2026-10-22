package com.collabcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.model.AppState

@Composable
fun EditorScreen(app: AppState, onPreview: () -> Unit, onBuild: () -> Unit) {
    val cc = LocalCC.current
    val name = app.activeFile

    if (name.isEmpty() || !app.files.containsKey(name)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No file open. Pick one from the menu or create a new file.",
                Modifier.padding(24.dp),
                color = cc.muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val stored = app.files[name].orEmpty()
    var value by remember(name) { mutableStateOf(TextFieldValue(stored)) }
    val undo = remember(name) { ArrayDeque<TextFieldValue>() }
    var lastPush by remember(name) { mutableStateOf(0L) }
    var confirmDelete by remember { mutableStateOf(false) }

    // When something else changes the file (an accepted AI edit), keep the old text on the undo stack.
    LaunchedEffect(stored) {
        if (stored != value.text) {
            undo.addLast(value)
            value = TextFieldValue(stored, TextRange(stored.length))
        }
    }

    val transform: VisualTransformation = remember(cc, name) {
        if (name.endsWith(".kt")) KotlinVisualTransformation(cc) else VisualTransformation.None
    }

    fun change(next: TextFieldValue) {
        if (next.text != value.text) {
            // Typing is grouped: one undo step per pause of about 0.7 seconds.
            val now = System.currentTimeMillis()
            if (undo.isEmpty() || now - lastPush > 700) {
                undo.addLast(value)
                if (undo.size > 100) undo.removeFirst()
            }
            lastPush = now
        }
        value = next
        app.updateFile(name, next.text)
    }

    fun doUndo() {
        if (undo.isNotEmpty()) {
            val previous = undo.removeLast()
            value = previous
            app.updateFile(name, previous.text)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Tabs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(cc.surface)) {
            app.openFiles.toList().forEach { f ->
                val active = f == name
                Column(Modifier.width(IntrinsicSize.Max)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f,
                            Modifier.clickable { app.activeFile = f }
                                .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                            fontSize = 13.sp,
                            color = if (active) cc.text else cc.muted
                        )
                        Text(
                            "×",
                            Modifier.clickable { app.closeTab(f) }
                                .padding(start = 2.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                            fontSize = 15.sp,
                            color = cc.muted
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (active) cc.accent else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(color = cc.border)

        // Code with line numbers
        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)
        ) {
            Column(Modifier.width(38.dp).padding(end = 12.dp), horizontalAlignment = Alignment.End) {
                repeat(value.text.count { it == '\n' } + 1) { i ->
                    Text(
                        "${i + 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = cc.lineNo,
                        textAlign = TextAlign.End
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = { change(it) },
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = cc.text
                ),
                cursorBrush = SolidColor(cc.accent),
                visualTransformation = transform
            )
        }

        // Symbol bar
        val keys = listOf("Undo", "Tab", "{", "}", "(", ")", "[", "]", "\"", "=", ";", "<", ">", "←", "→", "↑", "↓")
        LazyRow(
            Modifier.fillMaxWidth().background(cc.surface).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(keys) { k ->
                KeyButton(k) {
                    when (k) {
                        "Undo" -> doUndo()
                        "←" -> value = value.copy(
                            selection = TextRange((value.selection.start - 1).coerceAtLeast(0))
                        )
                        "→" -> value = value.copy(
                            selection = TextRange((value.selection.end + 1).coerceAtMost(value.text.length))
                        )
                        "↑" -> value = moveVertical(value, -1)
                        "↓" -> value = moveVertical(value, 1)
                        "Tab" -> change(insertAtCursor(value, "    "))
                        else -> change(insertAtCursor(value, k))
                    }
                }
            }
        }

        // Run / Build / Delete bar
        Row(
            Modifier.fillMaxWidth().background(cc.surface).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f)) { Text("▶  Preview") }
            OutlinedButton(onClick = onBuild, modifier = Modifier.weight(1f)) { Text("🔧  Build APK") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("🗑") }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete $name?",
            message = "This removes the file from this phone. The next commit removes it from GitHub too.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; app.deleteFile(name) },
            onDismiss = { confirmDelete = false }
        )
    }
}

private fun insertAtCursor(v: TextFieldValue, s: String): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end)
    val end = maxOf(v.selection.start, v.selection.end)
    return TextFieldValue(v.text.replaceRange(start, end, s), TextRange(start + s.length))
}

/** Moves the cursor one line up (dir = -1) or down (dir = 1), keeping the column when it can. */
private fun moveVertical(v: TextFieldValue, dir: Int): TextFieldValue {
    val text = v.text
    val pos = v.selection.end.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
    val col = pos - lineStart
    val newPos: Int
    if (dir < 0) {
        if (lineStart == 0) return v
        val prevEnd = lineStart - 1
        val prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1
        newPos = prevStart + minOf(col, prevEnd - prevStart)
    } else {
        val nextBreak = text.indexOf('\n', pos)
        if (nextBreak == -1) return v
        val nextStart = nextBreak + 1
        val nextEnd = text.indexOf('\n', nextStart).let { if (it == -1) text.length else it }
        newPos = nextStart + minOf(col, nextEnd - nextStart)
    }
    return v.copy(selection = TextRange(newPos))
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    Box(
        Modifier.defaultMinSize(minWidth = 40.dp).height(36.dp)
            .clip(RoundedCornerShape(8.dp)).background(cc.surface2)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
}
