package com.collabcode.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.collabcode.app.agent.AGENT_DEFAULT_SYS
import com.collabcode.app.agent.AgentConfig
import com.collabcode.app.agent.AgentEngine
import com.collabcode.app.agent.Ai
import com.collabcode.app.agent.Chat
import com.collabcode.app.agent.Me
import com.collabcode.app.agent.Note
import com.collabcode.app.agent.ToolCard
import com.collabcode.app.agent.ToolItem
import com.collabcode.app.agent.Ui
import kotlinx.coroutines.launch

private val WELCOME_CHIPS = listOf(
    "Explain this repository" to "Explain what this repository does. Start by listing its files.",
    "Find and fix bugs" to "Find and fix bugs in the main source files, then show me the diff before committing.",
    "Add a feature and build" to "Add a splash screen, commit it and start a build.",
    "Check the latest build" to "Show the status of the latest build and explain any failure.",
)

/** The whole screen of the design: sidebar, header, chat, composer and settings. */
@Composable
fun AgentRoot(engine: AgentEngine) {
    val cfg = engine.cfg
    val dark = when (cfg.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val pal = if (dark) DarkAgent else LightAgent
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snack = remember { SnackbarHostState() }
    var page by remember { mutableStateOf("chat") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(engine.toast) {
        val t = engine.toast
        if (t != null) {
            engine.toast = null
            snack.showSnackbar(t)
        }
    }
    LaunchedEffect(engine.settingsRequest) {
        if (engine.settingsRequest > 0) showSettings = true
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        engine.addFiles(uris)
    }

    fun export() {
        val f = engine.exportFile() ?: return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Export chat"))
    }

    CompositionLocalProvider(LocalAgent provides pal) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(280.dp),
                    drawerContainerColor = pal.sf,
                    drawerContentColor = pal.tx,
                ) {
                    Sidebar(
                        engine = engine,
                        dark = dark,
                        onChat = { page = "chat"; scope.launch { drawer.close() } },
                        onTerminal = { page = "terminal"; scope.launch { drawer.close() } },
                        onSettings = { scope.launch { drawer.close() }; showSettings = true },
                        onExport = { export() },
                        onTheme = { engine.setTheme(if (dark) "light" else "dark") },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Header(
                    title = if (page == "terminal") "Terminal" else (engine.cur?.title?.takeIf { it != "New chat" } ?: "CollabAgent"),
                    model = if (cfg.key.isNotBlank()) cfg.model else "No API key",
                    onMenu = { scope.launch { drawer.open() } },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (page == "terminal") {
                        TerminalPage()
                    } else {
                        ChatBody(
                            engine = engine,
                            onAttach = { pickFiles.launch("*/*") },
                        )
                    }
                    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp))
                }
            }
        }

        if (showSettings) {
            SettingsDialog(engine = engine, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun Header(title: String, model: String, onMenu: () -> Unit) {
    val pal = LocalAgent.current
    Column {
        Row(
            Modifier.fillMaxWidth().background(pal.sf).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentButton("≡", onClick = onMenu)
            Text(
                title,
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AgentPill(model, "")
        }
        HorizontalDivider(color = pal.bd)
    }
}

@Composable
private fun Sidebar(
    engine: AgentEngine,
    dark: Boolean,
    onChat: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onTheme: () -> Unit,
) {
    val pal = LocalAgent.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentButton("+ New chat", variant = "pr", modifier = Modifier.fillMaxWidth(), enabled = !engine.busy) {
            engine.newChat()
            onChat()
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(engine.chats) { chat ->
                val selected = chat === engine.cur
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.title,
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) pal.s2 else Color.Transparent)
                            .clickable(enabled = !engine.busy) {
                                engine.select(chat)
                                onChat()
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "×",
                        Modifier.clickable(enabled = !engine.busy) { engine.deleteChat(chat) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = pal.mu,
                        fontSize = 18.sp,
                    )
                }
            }
        }
        Text("Repository (owner/name)", fontSize = 12.sp, color = pal.mu)
        RepoField(engine)
        AgentButton("Terminal (Debian)", modifier = Modifier.fillMaxWidth(), onClick = onTerminal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentButton("⚙  Settings", modifier = Modifier.weight(1f), onClick = onSettings)
            AgentButton("⤓", onClick = onExport)
            AgentButton(if (dark) "☀" else "◐", onClick = onTheme)
        }
    }
}

@Composable
private fun RepoField(engine: AgentEngine) {
    val pal = LocalAgent.current
    var text by remember(engine.cfg.repo) { mutableStateOf(engine.cfg.repo) }
    Column {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                engine.setRepo(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = pal.tx, fontSize = 14.sp),
            cursorBrush = SolidColor(pal.ac),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, pal.bd, RoundedCornerShape(10.dp))
                .background(pal.bg)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("yourname/project", color = pal.mu, fontSize = 14.sp)
                    inner()
                }
            },
        )
        engine.repos
            .filter { it.contains(text, ignoreCase = true) && it != text }
            .take(6)
            .forEach { r ->
                Text(
                    r,
                    Modifier.fillMaxWidth()
                        .clickable {
                            text = r
                            engine.setRepo(r)
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                    color = pal.mu,
                )
            }
    }
}

@Composable
private fun ChatBody(engine: AgentEngine, onAttach: () -> Unit) {
    val pal = LocalAgent.current
    val cfg = engine.cfg
    val chat = engine.cur
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val count = chat?.ui?.size ?: 0

    LaunchedEffect(count, engine.busy) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        val t = input
        if (engine.send(t)) input = ""
    }

    Column(Modifier.fillMaxSize()) {
        if (chat == null || chat.ui.isEmpty()) {
            Welcome(modifier = Modifier.weight(1f), onPick = { engine.send(it) })
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(chat.ui) { item ->
                    ChatItemView(item, engine, cfg.fs)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .background(pal.sf)
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
        ) {
            if (engine.files.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    itemsIndexed(engine.files) { i, f ->
                        Row(
                            Modifier.clip(RoundedCornerShape(99.dp))
                                .border(1.dp, pal.bd, RoundedCornerShape(99.dp))
                                .background(pal.s2)
                                .padding(start = 10.dp, top = 3.dp, end = 4.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.name, fontSize = 12.sp)
                            Text(
                                "×",
                                Modifier.clickable { engine.removeFile(i) }.padding(horizontal = 6.dp),
                                color = pal.mu,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, pal.bd, RoundedCornerShape(18.dp))
                    .background(pal.bg)
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentButton("📎", modifier = Modifier.size(44.dp), onClick = onAttach)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f)
                        .heightIn(min = 60.dp, max = 240.dp)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = pal.tx, fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(pal.ac),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text("Describe the task for the agent", color = pal.mu, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                )
                AgentButton(
                    if (engine.busy) "■" else "↑",
                    variant = "pr",
                    modifier = Modifier.size(44.dp),
                ) {
                    if (engine.busy) engine.stop() else submit()
                }
            }
            Row(
                Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Enter sends. Shift+Enter adds a new line.", fontSize = 11.sp, color = pal.mu)
                Text(if (input.isEmpty()) "" else "${input.length} characters", fontSize = 11.sp, color = pal.mu)
            }
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onPick: (String) -> Unit) {
    val pal = LocalAgent.current
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("CollabAgent", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Reads your repository, edits files, commits, starts builds and calls APIs. Every change asks first.",
            Modifier.padding(top = 4.dp, bottom = 18.dp).widthIn(max = 540.dp),
            color = pal.mu,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WELCOME_CHIPS.forEach { (label, prompt) ->
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, pal.bd, RoundedCornerShape(12.dp))
                        .background(pal.sf)
                        .clickable { onPick(prompt) }
                        .padding(12.dp),
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatItemView(item: Ui, engine: AgentEngine, fs: Int) {
    val size = fs.sp
    when (item) {
        is Me -> Bubble(me = true, text = item.text, fs = size, copy = false)
        is Ai -> Bubble(me = false, text = item.text, fs = size, copy = item.text.isNotBlank())
        is Note -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(item.text, fontStyle = FontStyle.Italic, color = LocalAgent.current.mu, fontSize = size)
        }
        is ToolItem -> ToolView(item.card, engine)
    }
}

@Composable
private fun Bubble(me: Boolean, text: String, fs: TextUnit, copy: Boolean) {
    val pal = LocalAgent.current
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 780.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (me) pal.mu else pal.ac),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (me) "You" else "CA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                if (text.isEmpty() && !me) {
                    Text("Thinking", fontStyle = FontStyle.Italic, color = pal.mu, fontSize = fs)
                } else {
                    AgentMarkdown(text, fs, pal) { clipboard.setText(AnnotatedString(it)) }
                }
                if (copy) {
                    Text(
                        "Copy reply",
                        Modifier.padding(top = 4.dp).clickable { clipboard.setText(AnnotatedString(text)) },
                        color = pal.ac,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolView(card: ToolCard, engine: AgentEngine) {
    val pal = LocalAgent.current
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 780.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(shape)
                .border(1.dp, pal.bd, shape)
                .background(pal.sf),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(pal.ac))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(card.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    AgentPill(card.status, card.kind)
                }
                Text(
                    card.sub,
                    Modifier.padding(top = 2.dp, bottom = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = pal.mu,
                )
                if (card.out.isNotEmpty()) {
                    Text(
                        card.out,
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, pal.bd, RoundedCornerShape(8.dp))
                            .background(pal.bg)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                if (card.pending) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AgentButton("Deny", variant = "dn") { engine.decide(card, false) }
                        Spacer(Modifier.width(8.dp))
                        AgentButton("Allow", variant = "ok") { engine.decide(card, true) }
                    }
                }
            }
        }
    }
}

// ---------- markdown: fenced code, headings, bullets, **bold**, `code`, links ----------

private class Block(val code: Boolean, val lang: String, val body: String)

private val FENCE = Regex("```(\\w*)\\n?([\\s\\S]*?)(```|$)")
private val INLINE = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    var last = 0
    for (m in FENCE.findAll(text)) {
        if (m.range.first > last) out.add(Block(false, "", text.substring(last, m.range.first)))
        out.add(Block(true, m.groupValues[1].ifEmpty { "code" }, m.groupValues[2].removeSuffix("\n")))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(Block(false, "", text.substring(last)))
    return out
}

private fun inline(s: String, pal: AgentPalette): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val code = m.groups[1]
        val bold = m.groups[2]
        val link = m.groups[3]
        val url = m.groups[4]
        when {
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = pal.s2)) { append(code.value) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.value) }
            link != null && url != null -> withLink(
                LinkAnnotation.Url(url.value, TextLinkStyles(SpanStyle(color = pal.ac, textDecoration = TextDecoration.Underline)))
            ) { append(link.value) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

@Composable
private fun AgentMarkdown(text: String, fs: TextUnit, pal: AgentPalette, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in splitBlocks(text)) {
            if (b.code) {
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, pal.bd, shape)) {
                    Row(
                        Modifier.fillMaxWidth().background(pal.s2).padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(b.lang, fontSize = 12.sp, color = pal.mu)
                        Text(
                            "Copy",
                            Modifier.clickable { onCopy(b.body) }.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = pal.ac,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        b.body,
                        Modifier.fillMaxWidth()
                            .background(pal.sf)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            } else {
                for (line in b.body.split("\n")) {
                    when {
                        line.isBlank() -> Spacer(Modifier.height(4.dp))
                        line.startsWith("#") -> Text(
                            inline(line.trimStart('#', ' '), pal),
                            fontWeight = FontWeight.Bold,
                            fontSize = fs * 1.05f,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        line.startsWith("- ") || line.startsWith("* ") -> Row {
                            Text("•  ", fontSize = fs)
                            Text(inline(line.substring(2), pal), fontSize = fs)
                        }
                        else -> Text(inline(line, pal), fontSize = fs, lineHeight = fs * 1.5f)
                    }
                }
            }
        }
    }
}

// ---------- shared small controls ----------

@Composable
private fun AgentButton(
    label: String,
    variant: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val pal = LocalAgent.current
    val shape = RoundedCornerShape(10.dp)
    val bg = when (variant) {
        "pr" -> pal.ac
        "ok" -> pal.ok
        else -> pal.s2
    }
    val border = when (variant) {
        "pr" -> pal.ac
        "ok" -> pal.ok
        else -> pal.bd
    }
    val tx = when (variant) {
        "pr", "ok" -> Color.White
        "dn" -> pal.er
        else -> pal.tx
    }
    Box(
        modifier.clip(shape)
            .border(1.dp, border, shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tx else tx.copy(alpha = 0.45f),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun AgentPill(text: String, kind: String) {
    val pal = LocalAgent.current
    val c = when (kind) {
        "ok" -> pal.ok
        "warn" -> pal.wn
        "err" -> pal.er
        else -> pal.mu
    }
    val borderColor = if (kind.isEmpty()) pal.bd else c
    val shape = RoundedCornerShape(99.dp)
    Text(
        text,
        Modifier.clip(shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        color = c,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

@Composable
private fun SettingsDialog(engine: AgentEngine, onClose: () -> Unit) {
    val pal = LocalAgent.current
    val c0 = engine.cfg
    var key by remember { mutableStateOf(c0.key) }
    var base by remember { mutableStateOf(c0.base) }
    var model by remember { mutableStateOf(c0.model) }
    var temp by remember { mutableStateOf(c0.temp.toString()) }
    var maxTok by remember { mutableStateOf(c0.maxTok.toString()) }
    var sys by remember { mutableStateOf(c0.sys) }
    var gt by remember { mutableStateOf(c0.gt) }
    var br by remember { mutableStateOf(c0.br) }
    var wf by remember { mutableStateOf(c0.wf) }
    var askW by remember { mutableStateOf(c0.askW) }
    var askB by remember { mutableStateOf(c0.askB) }
    var askH by remember { mutableStateOf(c0.askH) }
    var fs by remember { mutableStateOf(c0.fs) }
    var confirmClear by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 440.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, pal.bd, RoundedCornerShape(16.dp))
                .background(pal.sf)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            SectionLabel("Model")
            SettingField("API key", key, password = true) { key = it }
            SettingField("Base URL", base) { base = it }
            SettingField("Model ID", model) { model = it }
            SettingField("Temperature (0 to 2)", temp, number = true) { temp = it }
            SettingField("Max output tokens", maxTok, number = true) { maxTok = it }
            SettingField("System prompt", sys, lines = 4) { sys = it }
            SectionLabel("GitHub")
            SettingField("Personal access token (scopes: repo, workflow)", gt, password = true) { gt = it }
            SettingField("Branch", br) { br = it }
            SettingField("Build workflow file", wf) { wf = it }
            SectionLabel("Approvals")
            CheckRow("Ask before committing files", askW) { askW = it }
            CheckRow("Ask before starting builds", askB) { askB = it }
            CheckRow("Ask before non-GET API calls", askH) { askH = it }
            SectionLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(14 to "Small", 15 to "Normal", 17 to "Large").forEach { (v, label) ->
                    AgentButton(label, variant = if (fs == v) "pr" else "", modifier = Modifier.weight(1f)) { fs = v }
                }
            }
            SectionLabel("Data")
            AgentButton("Delete all chats", variant = "dn", modifier = Modifier.fillMaxWidth()) { confirmClear = true }
            Text(
                "Keys and tokens stay in this browser or app only. They are sent only to the model URL and to api.github.com. " +
                    "Run this file from the app or a local file: hosted previews often block network calls.",
                fontSize = 12.sp,
                color = pal.mu,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                AgentButton("Cancel", onClick = onClose)
                Spacer(Modifier.width(8.dp))
                AgentButton("Save", variant = "pr") {
                    engine.saveConfig(
                        AgentConfig(
                            key = key.trim(),
                            base = base.trim().ifEmpty { "https://api.openai.com/v1" },
                            model = model.trim().ifEmpty { "gpt-4o" },
                            temp = temp.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.0,
                            maxTok = maxTok.toIntOrNull()?.takeIf { it > 0 } ?: 2048,
                            sys = sys.trim().ifEmpty { AGENT_DEFAULT_SYS },
                            gt = gt.trim(),
                            repo = engine.cfg.repo,
                            br = br.trim().ifEmpty { "main" },
                            wf = wf.trim().ifEmpty { "build.yml" },
                            askW = askW,
                            askB = askB,
                            askH = askH,
                            fs = fs,
                            theme = engine.cfg.theme,
                        )
                    )
                    onClose()
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Delete all chats?",
            message = "All chats on this phone are deleted. Your GitHub repository is not affected.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmClear = false
                engine.clearAll()
                onClose()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    password: Boolean = false,
    number: Boolean = false,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    val pal = LocalAgent.current
    val shape = RoundedCornerShape(10.dp)
    Column {
        Text(label, fontSize = 12.sp, color = pal.mu)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    number -> KeyboardType.Number
                    password -> KeyboardType.Password
                    else -> KeyboardType.Text
                }
            ),
            textStyle = TextStyle(color = pal.tx, fontSize = 15.sp),
            cursorBrush = SolidColor(pal.ac),
            modifier = Modifier.fillMaxWidth()
                .clip(shape)
                .border(1.dp, pal.bd, shape)
                .background(pal.bg)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalAgent.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp)
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = pal.ac),
        )
    }
}
