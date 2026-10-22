package com.collabcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.model.AppState
import com.collabcode.app.model.Screen

@Composable
fun CircleButton(label: String, size: Dp = 42.dp, onClick: () -> Unit) {
    val cc = LocalCC.current
    Box(
        Modifier.size(size).clip(CircleShape).border(1.dp, cc.border, CircleShape)
            .background(cc.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 17.sp) }
}

@Composable
fun SendButton(onClick: () -> Unit) {
    val cc = LocalCC.current
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(ccGradient(cc)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("➤", color = Color.White, fontSize = 16.sp) }
}

@Composable
fun ChipButton(label: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(36.dp).clip(shape).border(1.dp, cc.border, shape)
            .background(cc.surface2).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
fun TopBar(title: String, onMenu: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircleButton("☰", onClick = onMenu)
        Text(
            title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CircleButton("⋮", onClick = onMore)
    }
}

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    chipLabel: String,
    onPlus: (() -> Unit)? = null,
    onChip: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
) {
    val cc = LocalCC.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .clip(shape)
            .border(1.dp, cc.border, shape)
            .background(cc.surface)
            .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            textStyle = TextStyle(color = cc.text, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(cc.accent),
            maxLines = 6,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = cc.muted, fontSize = 16.sp)
                    inner()
                }
            }
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            onPlus?.let { CircleButton("+", size = 38.dp, onClick = it) }
            Spacer(Modifier.weight(1f))
            ChipButton(chipLabel, onChip)
            onMic?.let { CircleButton("🎤", size = 38.dp, onClick = it) }
            SendButton(onSend)
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val cc = LocalCC.current
    val color = when (status) {
        "success" -> cc.green
        "failed" -> cc.red
        else -> cc.yellow
    }
    Text(
        status,
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        color = color,
        fontSize = 12.sp
    )
}

@Composable
fun DrawerContent(
    app: AppState,
    onNavigate: (Screen) -> Unit,
    onNewFile: () -> Unit,
    onSettings: () -> Unit,
) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            NavItem("⌂", "Home", app.screen == Screen.Home) { onNavigate(Screen.Home) }
            NavItem("💬", "AI chat", app.screen == Screen.Chat) { onNavigate(Screen.Chat) }
            NavItem("✎", "Code editor", app.screen == Screen.Editor) { onNavigate(Screen.Editor) }
            NavItem("▶", "Live preview", app.screen == Screen.Preview) { onNavigate(Screen.Preview) }
            NavItem("⚙", "Build APK", app.screen == Screen.Build) { onNavigate(Screen.Build) }
            NavItem(">_", "Terminal (Codespace)", app.screen == Screen.Terminal) { onNavigate(Screen.Terminal) }

            Text(
                "FILES",
                Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                fontSize = 11.sp,
                color = cc.muted,
                letterSpacing = 1.sp
            )
            app.fileNames().forEach { name ->
                val selected = name == app.activeFile && app.screen == Screen.Editor
                Text(
                    text = name,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { app.open(name); onNavigate(Screen.Editor) }
                        .background(if (selected) cc.surface2 else Color.Transparent)
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
                    color = if (selected) cc.text else cc.muted,
                    fontSize = 14.sp
                )
            }
            NavItem("+", "New file", false, onNewFile)
        }
        NavItem("⚙", "Settings", false, onSettings)
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val cc = LocalCC.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) cc.surface2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, Modifier.width(22.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun SheetOption(icon: String, label: String, hint: String, onClick: () -> Unit) {
    val cc = LocalCC.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, Modifier.width(24.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        if (hint.isNotEmpty()) Text(hint, fontSize = 12.5.sp, color = cc.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    onDismiss: () -> Unit,
    onBuild: () -> Unit,
    onCommit: () -> Unit,
    onTerminal: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetOption("🔧", "Build APK", "GitHub Actions", onBuild)
        SheetOption("✓", "Commit changes", "push to GitHub", onCommit)
        SheetOption(">_", "Terminal", "Codespace", onTerminal)
        SheetOption("▶", "Live preview", "offline", onPreview)
        SheetOption("⚙", "Settings", "account, AI key", onSettings)
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(app: AppState, onEditAi: () -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetOption("👤", "GitHub account", "@${app.githubLogin}", {})
        SheetOption("🔑", "AI provider, model and key", app.aiModel, onEditAi)
        SheetOption("☁", "Repository", app.prefs.repo.orEmpty(), {})
        SheetOption("🔒", "Privacy", "Data stays on device", {})
        SheetOption("⎋", "Sign out", "removes tokens", onSignOut)
        SheetOption("✕", "Close", "", onDismiss)
        Spacer(Modifier.height(16.dp))
    }
}
