package com.collabcode.app.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.collabcode.app.model.*
import com.collabcode.app.data.*
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal fun UserBubble(text: String) {
    val cc = LocalCC.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp))
                .background(cc.surface2)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun AssistantCard(
    msg: AssistantMsg,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRunCommand: (PendingCommand) -> Unit = {},
    onRejectCommand: (PendingCommand) -> Unit = {},
    onStop: () -> Unit = {},
) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxWidth()) {
        Text("ASSISTANT", fontSize = 11.sp, color = cc.muted, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(4.dp))
        msg.tool?.let { ToolCard(it, msg.toolArg.orEmpty()) }
        Text(msg.text, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
        msg.edits.forEach { DiffBlock(it) }
        msg.commands.forEach { c -> CommandBlock(c, { onRunCommand(c) }, { onRejectCommand(c) }, onStop) }
        if (msg.edits.isNotEmpty() && msg.status == EditStatus.Pending) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
        val note = when (msg.status) {
            EditStatus.Pending -> null
            EditStatus.Accepted -> "Accepted. Saved to ${msg.edits.joinToString { it.file }} on this device. Commit when ready."
            EditStatus.Rejected -> "Rejected. No change made."
        }
        note?.let {
            Text(
                it,
                Modifier.padding(top = 6.dp),
                fontSize = 12.5.sp,
                color = if (msg.status == EditStatus.Accepted) cc.green else cc.muted
            )
        }
    }
}

@Composable
internal fun NoticeCard(n: NoticeMsg) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxWidth()) {
        Text("COMMIT", fontSize = 11.sp, color = cc.muted, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(4.dp))
        ToolCard(n.tool, n.arg)
        Text(n.note, Modifier.padding(top = 6.dp), fontSize = 12.5.sp, color = cc.yellow)
    }
}

@Composable
private fun ToolCard(tool: String, arg: String) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(8.dp)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = cc.yellow, fontWeight = FontWeight.SemiBold)) { append(tool) }
            append(" $arg")
        },
        Modifier.fillMaxWidth().clip(shape).border(1.dp, cc.border, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = cc.muted
    )
}

@Composable
private fun DiffBlock(edit: PendingEdit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(10.dp)
    val added = edit.diff.count { it.kind == DiffKind.ADD }
    val removed = edit.diff.count { it.kind == DiffKind.DEL }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, cc.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(cc.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("edit_file · ${edit.file}", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = cc.muted)
            Text("+$added −$removed", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = cc.muted)
        }
        edit.diff.filter { it.kind != DiffKind.SAME }.forEach { line ->
            val isAdd = line.kind == DiffKind.ADD
            val color = if (isAdd) cc.green else cc.red
            val prefix = if (isAdd) "+ " else "- "
            Text(
                prefix + line.text,
                Modifier.fillMaxWidth().background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 2.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                color = color
            )
        }
    }
}


// ---------- PREVIEW ----------

@Suppress("DEPRECATION")
@Composable
fun PreviewScreen(app: AppState) {
    val cc = LocalCC.current
    val html = app.files["index.html"].orEmpty()

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
        )
        HorizontalDivider(color = cc.border)
        Text(
            "Sandboxed preview. Runs offline in the app.",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = cc.muted
        )
    }
}

// ---------- TERMINAL ----------

@Composable
fun TerminalScreen() {
    val cc = LocalCC.current
    val mono = FontFamily.Monospace
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = cc.green)) { append("codespace ~$ ") }
                append("ls")
            },
            fontFamily = mono, fontSize = 13.sp, lineHeight = 20.sp
        )
        Text("main.kt  utils.kt  README.md  build.gradle.kts", fontFamily = mono, fontSize = 13.sp, lineHeight = 20.sp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = cc.green)) { append("codespace ~$ ") }
                withStyle(SpanStyle(color = cc.muted)) { append("_") }
            },
            Modifier.padding(top = 8.dp),
            fontFamily = mono, fontSize = 13.sp
        )
        Text(
            "Terminal runs in your own Codespace. Demo only.",
            Modifier.padding(top = 14.dp),
            fontSize = 13.sp,
            color = cc.muted
        )
    }
}

@Composable
private fun CommandBlock(cmd: PendingCommand, onRun: () -> Unit, onReject: () -> Unit, onStop: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(10.dp)
    val label = when (cmd.state) {
        CmdState.Pending -> "needs approval"
        CmdState.Running -> "running"
        CmdState.Done -> "done"
        CmdState.Failed -> "failed"
        CmdState.Rejected -> "rejected"
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, cc.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(cc.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("run_command · terminal", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = cc.muted)
            Text(label, fontSize = 12.5.sp, color = if (cmd.state == CmdState.Failed) cc.red else cc.muted)
        }
        Text(
            "$ ${cmd.command}",
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp
        )
        when (cmd.state) {
            CmdState.Pending -> Row(
                Modifier.fillMaxWidth().background(cc.surface2).padding(9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
                Button(onClick = onRun, modifier = Modifier.weight(1f)) { Text("Run") }
            }
            CmdState.Running -> Row(
                Modifier.fillMaxWidth().background(cc.surface2).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Running...", fontSize = 13.sp, color = cc.muted)
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            else -> if (cmd.output.isNotEmpty()) {
                Text(
                    cmd.output.take(4000),
                    Modifier.fillMaxWidth().background(cc.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (cmd.state == CmdState.Failed) cc.red else cc.muted
                )
            }
        }
    }
}
