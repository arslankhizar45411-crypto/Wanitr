package com.collabcode.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collabcode.app.model.*
import com.collabcode.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CollabCodeTheme { CollabCodeApp() } }
    }
}

@Composable
fun CollabCodeApp() {
    val app: AppState = viewModel()
    if (app.onboarded) LuminaShell(app) else OnboardingHost(app)
}

@Composable
private fun OnboardingHost(app: AppState) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxSize().background(cc.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        OnboardingScreen(app)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(app: AppState) {
    val cc = LocalCC.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    var showMore by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }

    // Android 13+ asks once for permission to show the "build finished" notification.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    fun go(s: Screen) {
        app.screen = s
        showMore = false
        scope.launch { drawer.close() }
    }
    fun sendPrompt(prompt: String) {
        app.screen = Screen.Chat
        app.sendAsync(prompt)
    }
    fun build() {
        app.screen = Screen.Build
        app.buildAsync()
    }

    BackHandler(enabled = drawer.isOpen || app.screen != Screen.Home || app.selectedRun != null) {
        when {
            drawer.isOpen -> { scope.launch { drawer.close() } }
            app.screen == Screen.Build && app.selectedRun != null -> { app.closeRun() }
            else -> { app.screen = Screen.Home }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f).widthIn(max = 310.dp)) {
                DrawerContent(
                    app = app,
                    onNavigate = { go(it) },
                    onNewFile = { scope.launch { drawer.close() }; showNewFile = true },
                    onSettings = { scope.launch { drawer.close() }; showSettings = true }
                )
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(cc.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(
                title = app.title(),
                onMenu = { scope.launch { drawer.open() } },
                onMore = { showMore = true }
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (app.screen) {
                    Screen.Home -> HomeScreen(
                        onSend = { sendPrompt(it) },
                        onGo = { go(it) },
                        onToast = { toast(it) }
                    )
                    Screen.Chat -> ChatScreen(
                        app = app,
                        onSend = { sendPrompt(it) },
                        onToast = { toast(it) }
                    )
                    Screen.Editor -> EditorScreen(
                        app = app,
                        onPreview = { go(Screen.Preview) },
                        onBuild = { build() }
                    )
                    Screen.Preview -> PreviewScreen(app)
                    Screen.Build -> BuildScreen(app, onBuild = { build() })
                    Screen.Terminal -> TerminalScreen()
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                )
            }
        }
    }

    if (showMore) {
        MoreSheet(
            onDismiss = { showMore = false },
            onBuild = { showMore = false; build() },
            onCommit = {
                showMore = false
                app.screen = Screen.Chat
                app.commitAsync()
            },
            onTerminal = { go(Screen.Terminal) },
            onPreview = { go(Screen.Preview) },
            onSettings = { showMore = false; showSettings = true }
        )
    }

    if (showSettings) {
        SettingsSheet(
            app = app,
            onEditAi = { showSettings = false; showAiSettings = true },
            onSignOut = { showSettings = false; app.signOut() },
            onDismiss = { showSettings = false }
        )
    }

    if (showAiSettings) AiSettingsDialog(app = app, onDismiss = { showAiSettings = false })

    if (showNewFile) {
        NewFileDialog(
            onCreate = { name ->
                val error = app.createFile(name)
                if (error == null) {
                    showNewFile = false
                    go(Screen.Editor)
                }
                error
            },
            onDismiss = { showNewFile = false }
        )
    }
}
