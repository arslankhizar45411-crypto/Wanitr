package com.collabcode.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collabcode.app.data.AiClient
import com.collabcode.app.data.ApiException
import com.collabcode.app.data.GitHub
import com.collabcode.app.data.REPO_NAME
import com.collabcode.app.data.RepoState
import com.collabcode.app.model.AppState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(app: AppState) {
    val cc = LocalCC.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var pendingLogin by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf(app.prefs.aiBaseUrl) }
    var model by remember { mutableStateOf(app.prefs.aiModel) }
    var aiKey by remember { mutableStateOf("") }

    fun finishGitHub(login: String) {
        app.prefs.githubToken = token.trim()
        app.prefs.githubLogin = login
        app.githubLogin = login
        step = 2
    }

    fun verifyGitHub() {
        scope.launch {
            busy = true
            error = null
            try {
                val gh = GitHub(token.trim())
                val login = gh.login()
                when (gh.ensureRepo(login)) {
                    RepoState.OTHER -> pendingLogin = login
                    else -> finishGitHub(login)
                }
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not reach GitHub. Check your connection."
            } finally {
                busy = false
            }
        }
    }

    fun verifyAi() {
        scope.launch {
            busy = true
            error = null
            try {
                AiClient(baseUrl.trim(), aiKey.trim(), model.trim()).checkKey()
                app.prefs.aiBaseUrl = baseUrl.trim()
                app.prefs.aiModel = model.trim()
                app.prefs.aiKey = aiKey.trim()
                app.aiModel = model.trim()
                app.onboarded = true
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not reach the AI provider."
            } finally {
                busy = false
            }
        }
    }

    pendingLogin?.let { login ->
        AlertDialog(
            onDismissRequest = { pendingLogin = null },
            title = { Text("Repository already exists") },
            text = {
                Text(
                    "$login/$REPO_NAME already exists and was not created by CollabCode. " +
                        "CollabCode will add and overwrite files in it, including " +
                        ".github/workflows/build.yml. Use it anyway?"
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingLogin = null; finishGitHub(login) }) { Text("Use it") }
            },
            dismissButton = { TextButton(onClick = { pendingLogin = null }) { Text("Cancel") } }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Step ${step + 1} of 3", fontSize = 12.sp, color = cc.muted)

        when (step) {
            0 -> {
                Text(
                    "Build Android apps from your phone",
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp
                )
                Text(
                    "CollabCode saves your Kotlin code to your own GitHub repository and builds it " +
                        "with GitHub Actions. Your tokens stay on this phone, encrypted.",
                    fontSize = 15.sp, color = cc.muted, lineHeight = 22.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
            }

            1 -> {
                Text("Connect GitHub", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Create a classic token with the repo and workflow scopes. CollabCode only uses " +
                        "$REPO_NAME, but GitHub gives the token access to all your repositories.",
                    fontSize = 14.sp, color = cc.muted, lineHeight = 21.sp
                )
                OutlinedButton(
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/settings/tokens/new?scopes=repo,workflow&description=CollabCode"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open token page") }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = cc.red, fontSize = 13.sp) }
                Button(
                    onClick = { verifyGitHub() },
                    enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Checking..." else "Continue") }
            }

            else -> {
                Text("Connect AI", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Any OpenAI-compatible provider works. OpenRouter is the default. " +
                        "Usage is billed by your provider, using your own key.",
                    fontSize = 14.sp, color = cc.muted, lineHeight = 21.sp
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = cc.red, fontSize = 13.sp) }
                Button(
                    onClick = { verifyAi() },
                    enabled = !busy && aiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Checking..." else "Finish setup") }
            }
        }
    }
}
