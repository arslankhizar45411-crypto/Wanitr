package com.collabcode.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.model.AppState
import com.collabcode.app.model.AssistantMsg
import com.collabcode.app.model.NoticeMsg
import com.collabcode.app.model.UserMsg

enum class MainTab { Home, Explore, History, Settings }

private enum class Page { None, Chat, Editor, Build, Preview }

data class ChatMode(val label: String, val seed: String, val hint: String)

val CHAT_MODES = listOf(
    ChatMode("Ask Anything", "", "Ask about your project, your code or a build"),
    ChatMode("Create image", "Write a detailed image prompt for: ", "Turn an idea into a clear image prompt"),
    ChatMode("Code", "Write Kotlin code for: ", "Write or change code in your project"),
    ChatMode("Analyze", "Analyze this and tell me what you find: ", "Review files, logs or a build failure"),
    ChatMode("Brainstorm", "Brainstorm ideas for: ", "Generate options before you decide"),
)

@Composable
fun LuminaShell(app: AppState) {
    val cc = LocalCC.current
    var tab by remember { mutableStateOf(MainTab.Home) }
    var page by remember { mutableStateOf(Page.None) }
    var seed by remember { mutableStateOf("") }
    var showAi by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }

    fun openChat(text: String) {
        seed = text
        page = Page.Chat
    }

    fun backFromBuild() {
        if (app.selectedRun != null) app.closeRun() else page = Page.None
    }

    BackHandler(enabled = page != Page.None || tab != MainTab.Home) {
        when {
            page == Page.Build && app.selectedRun != null -> { app.closeRun() }
            page != Page.None -> { page = Page.None }
            else -> { tab = MainTab.Home }
        }
    }

    Column(Modifier.fillMaxSize().background(cc.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                Page.Chat -> ChatPage(
                    app = app,
                    seed = seed,
                    onSeedUsed = { seed = "" },
                    onBack = { page = Page.None },
                    onEditAi = { showAi = true },
                )
                Page.Editor -> SubPage(app.activeFile.ifEmpty { "Code" }, { page = Page.None }) {
                    EditorScreen(
                        app = app,
                        onPreview = { page = Page.Preview },
                        onBuild = { page = Page.Build; app.buildAsync() },
                    )
                }
                Page.Build -> SubPage("Build", { backFromBuild() }) {
                    BuildScreen(app, onBuild = { app.buildAsync() })
                }
                Page.Preview -> SubPage("Live preview", { page = Page.None }) {
                    PreviewScreen(app)
                }
                Page.None -> when (tab) {
                    MainTab.Home -> HomeTab(
                        app = app,
                        onOpenChat = { openChat(it) },
                        onOpenEditor = { page = Page.Editor },
                        onOpenBuild = { page = Page.Build },
                    )
                    MainTab.Explore -> ExploreTab(
                        onOpenChat = { openChat(it) },
                        onOpenEditor = { page = Page.Editor },
                        onOpenBuild = { page = Page.Build },
                        onNewFile = { showNewFile = true },
                        onCommit = { app.commitAsync(); openChat("") },
                    )
                    MainTab.History -> HistoryTab(app = app, onOpenChat = { openChat(it) })
                    MainTab.Settings -> SettingsTab(app = app, onEditAi = { showAi = true })
                }
            }
        }
        if (page == Page.None) {
            BottomNav(current = tab, onSelect = { tab = it })
        }
    }

    if (showAi) AiSettingsDialog(app = app, onDismiss = { showAi = false })

    if (showNewFile) {
        NewFileDialog(
            onCreate = { name ->
                val error = app.createFile(name)
                if (error == null) {
                    showNewFile = false
                    page = Page.Editor
                }
                error
            },
            onDismiss = { showNewFile = false },
        )
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackBar(title, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleButton("‹", size = 40.dp, onClick = onBack)
        Text(
            title,
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(40.dp))
    }
}

// ---------- Home ----------

@Composable
private fun HomeTab(
    app: AppState,
    onOpenChat: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenBuild: () -> Unit,
) {
    val cc = LocalCC.current
    val recent = recentPrompts(app)
    val promptCount = app.chat.count { it is UserMsg }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            HeaderRow(app)
            Text(
                "Hi ${app.githubLogin.ifEmpty { "there" }}, how can I help you today?",
                Modifier.fillMaxWidth().padding(top = 26.dp),
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                lineHeight = 32.sp
            )
            Text(
                "Build, debug and ship your projects from your phone",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = cc.muted
            )
            ModeChips(onPick = { onOpenChat(it.seed) })

            Spacer(Modifier.height(26.dp))
            Text("Workspace", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cc.muted)
            Spacer(Modifier.height(8.dp))
            WorkspaceRow("▣", "Work", "${app.files.size} files", onOpenEditor)
            WorkspaceRow("✎", "Learning", "$promptCount prompts") { onOpenChat("") }
            WorkspaceRow("▤", "Content", "${app.builds.size} builds", onOpenBuild)

            if (recent.isNotEmpty()) {
                Text(
                    "Recent prompts",
                    Modifier.padding(top = 22.dp, bottom = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cc.muted
                )
                recent.forEach { text -> PromptRow(text) { onOpenChat(text) } }
            }
            Spacer(Modifier.height(96.dp))
        }
        Fab(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)) { onOpenChat("") }
    }
}

private fun recentPrompts(app: AppState): List<String> =
    app.chat.filterIsInstance<UserMsg>().map { it.text }.distinct().takeLast(3).reversed()

@Composable
private fun HeaderRow(app: AppState) {
    val cc = LocalCC.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(ccGradient(cc)), contentAlignment = Alignment.Center) {
            Text("✦", color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("CollabAgent", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Avatar(app.githubLogin)
    }
}

@Composable
private fun Avatar(login: String) {
    val cc = LocalCC.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).border(1.dp, cc.border, CircleShape).background(cc.surface2),
        contentAlignment = Alignment.Center
    ) {
        Text(login.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun ModeChips(onPick: (ChatMode) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CHAT_MODES.take(3).forEach { mode -> ModeChip(mode) { onPick(mode) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CHAT_MODES.drop(3).forEach { mode -> ModeChip(mode) { onPick(mode) } }
        }
    }
}

@Composable
private fun ModeChip(mode: ChatMode, onClick: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape).border(1.dp, cc.border, shape).background(cc.surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(mode.label, fontSize = 12.5.sp)
    }
}

@Composable
private fun WorkspaceRow(icon: String, title: String, detail: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, cc.border, shape)
            .background(cc.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(cc.surface2), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 14.sp, color = cc.accent)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 15.sp)
        Text(detail, fontSize = 12.5.sp, color = cc.muted)
    }
}

@Composable
private fun PromptRow(text: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, cc.border, shape)
            .background(cc.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("💬", fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingRow(title: String, detail: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, cc.border, shape)
            .background(cc.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (detail.isNotEmpty()) {
            Text(detail, fontSize = 12.5.sp, color = cc.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, cc.border, shape)
            .background(cc.surface).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, fontSize = 12.5.sp, color = cc.muted)
        Text(value, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Fab(modifier: Modifier, onClick: () -> Unit) {
    val cc = LocalCC.current
    Box(
        modifier.size(58.dp).clip(CircleShape).background(ccGradient(cc)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("✦", color = Color.White, fontSize = 22.sp)
    }
}

// ---------- Explore ----------

@Composable
private fun ExploreTab(
    onOpenChat: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenBuild: () -> Unit,
    onNewFile: () -> Unit,
    onCommit: () -> Unit,
) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text("Explore", fontFamily = FontFamily.Serif, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(
            "Pick a mode to start a chat",
            Modifier.padding(top = 2.dp, bottom = 10.dp),
            fontSize = 13.sp,
            color = cc.muted
        )
        CHAT_MODES.forEach { mode -> SettingRow(mode.label, mode.hint) { onOpenChat(mode.seed) } }

        Text(
            "Project",
            Modifier.padding(top = 22.dp, bottom = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = cc.muted
        )
        SettingRow("Code editor", "Edit files on this phone") { onOpenEditor() }
        SettingRow("Build APK", "Compile on GitHub Actions") { onOpenBuild() }
        SettingRow("New file", "Add a file to the project") { onNewFile() }
        SettingRow("Commit changes", "Save changes to your GitHub repository") { onCommit() }
        Spacer(Modifier.height(90.dp))
    }
}

// ---------- History ----------

@Composable
private fun HistoryTab(app: AppState, onOpenChat: (String) -> Unit) {
    val cc = LocalCC.current
    var confirmClear by remember { mutableStateOf(false) }
    val prompts = app.chat.filterIsInstance<UserMsg>().map { it.text }.reversed()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "History",
                Modifier.weight(1f),
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium
            )
            if (prompts.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (prompts.isEmpty()) {
            Text(
                "No history yet. Start a chat and it will show up here.",
                Modifier.padding(top = 12.dp),
                fontSize = 14.sp,
                color = cc.muted
            )
        }
        prompts.forEach { text -> PromptRow(text) { onOpenChat(text) } }
        Spacer(Modifier.height(90.dp))
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = "All chat messages on this phone will be deleted. Your GitHub repository is not affected.",
            confirmLabel = "Clear",
            onConfirm = { confirmClear = false; app.clearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

// ---------- Settings ----------

@Composable
private fun SettingsTab(app: AppState, onEditAi: () -> Unit) {
    val cc = LocalCC.current
    var confirmOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text("Settings", fontFamily = FontFamily.Serif, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        InfoRow("GitHub account", "@${app.githubLogin}")
        InfoRow("Repository", app.prefs.repo.orEmpty())
        SettingRow("AI model", "${app.aiModel} · tap to change the model or key", onEditAi)
        InfoRow("Privacy", "Data stays on this phone. Tokens and keys are encrypted with the Android Keystore.")
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = { confirmOut = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out", color = cc.red)
        }
        Spacer(Modifier.height(90.dp))
    }
    if (confirmOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Tokens and the AI key are removed from this phone. Your code on GitHub is not deleted.",
            confirmLabel = "Sign out",
            onConfirm = { confirmOut = false; app.signOut() },
            onDismiss = { confirmOut = false },
        )
    }
}

// ---------- Chat ----------

@Composable
private fun ChatPage(
    app: AppState,
    seed: String,
    onSeedUsed: () -> Unit,
    onBack: () -> Unit,
    onEditAi: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val empty = app.chat.isEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        app.addAttachments(uris)
    }

    LaunchedEffect(seed) {
        if (seed.isNotEmpty()) {
            input = seed
            onSeedUsed()
        }
    }
    LaunchedEffect(app.chat.size) {
        if (app.chat.isNotEmpty()) listState.animateScrollToItem(app.chat.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(if (empty) "New Chat" else "Chat", onBack)

        if (empty) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
            ) {
                Text(
                    "Hi ${app.githubLogin.ifEmpty { "there" }}, how can I help you today?",
                    Modifier.padding(top = 18.dp),
                    fontFamily = FontFamily.Serif,
                    fontSize = 26.sp,
                    lineHeight = 32.sp
                )
                ModeChips(onPick = { input = it.seed })
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(app.chat) { item ->
                    when (item) {
                        is UserMsg -> UserBubble(item.text)
                        is AssistantMsg -> AssistantCard(
                            msg = item,
                            onAccept = { app.accept(item) },
                            onReject = { app.reject(item) },
                        )
                        is NoticeMsg -> NoticeCard(item)
                    }
                }
                if (app.busy) {
                    item { GeneratingRow() }
                }
            }
        }

        AttachmentRow(app)
        if (app.busy) {
            StopBar(onStop = { app.stopSend() })
        } else {
            Composer(
                onPlus = { picker.launch("*/*") },
                value = input,
                onValueChange = { input = it },
                placeholder = "Ask me anything...",
                chipLabel = "Model",
                onChip = onEditAi,
                onSend = {
                    val p = input.trim()
                    if (p.isNotEmpty()) {
                        input = ""
                        app.sendAsync(p)
                    }
                },
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun GeneratingRow() {
    val cc = LocalCC.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✦", color = cc.accent, fontSize = 13.sp)
        Text("Generating...", color = cc.muted, fontSize = 13.sp)
    }
}

@Composable
private fun StopBar(onStop: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)
            .clip(shape).border(1.dp, cc.border, shape).background(cc.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Generating...", Modifier.weight(1f), color = cc.muted, fontSize = 14.sp)
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(cc.text).clickable(onClick = onStop),
            contentAlignment = Alignment.Center
        ) {
            Text("■", color = cc.bg, fontSize = 12.sp)
        }
    }
}

// ---------- Bottom navigation ----------

@Composable
private fun BottomNav(current: MainTab, onSelect: (MainTab) -> Unit) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxWidth().background(cc.surface)) {
        HorizontalDivider(color = cc.border)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            TabItem("⌂", "Home", current == MainTab.Home) { onSelect(MainTab.Home) }
            TabItem("◎", "Explore", current == MainTab.Explore) { onSelect(MainTab.Explore) }
            TabItem("◷", "History", current == MainTab.History) { onSelect(MainTab.History) }
            TabItem("⚙", "Settings", current == MainTab.Settings) { onSelect(MainTab.Settings) }
        }
    }
}

@Composable
private fun TabItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val cc = LocalCC.current
    val color = if (selected) cc.accent else cc.muted
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 18.sp, color = color)
        Text(label, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun AttachmentRow(app: AppState) {
    if (app.attachments.isEmpty()) return
    val cc = LocalCC.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        app.attachments.toList().forEach { item ->
            Row(
                Modifier.clip(shape).border(1.dp, cc.border, shape).background(cc.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.name, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text("×", fontSize = 14.sp, color = cc.muted, modifier = Modifier.clickable { app.removeAttachment(item) })
            }
        }
    }
}
