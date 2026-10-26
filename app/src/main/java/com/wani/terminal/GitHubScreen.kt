package com.wani.terminal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitHubScreen(settings: AppSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(settings.githubToken) }
    var repo by remember { mutableStateOf(settings.githubRepo) }
    var workflow by remember { mutableStateOf(settings.githubWorkflow) }
    var branch by remember { mutableStateOf(settings.githubBranch) }
    var connected by remember { mutableStateOf(settings.githubToken.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (settings.githubToken.isNotBlank()) "Token saved on this phone." else "Not connected yet.")
    }
    var runUrl by remember { mutableStateOf<String?>(null) }

    /** Background mein action chalata hai. Action (message, optional run URL) return karta hai. */
    fun runAction(label: String, action: () -> Pair<String, String?>) {
        busy = true
        status = "$label..."
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.toString()}" to null
            }
            status = result.first
            // State sirf main thread par badlo (token saved ho to connected)
            connected = settings.githubToken.isNotBlank()
            result.second?.let { runUrl = it }
            busy = false
        }
    }

    /** Inputs check karta hai. Error message ya null. */
    fun checkInputs(needToken: Boolean): String? = when {
        needToken && token.isBlank() -> "Paste your GitHub token first."
        !GitHubApi.REPO_PATTERN.matches(repo.trim()) -> "Repository must look like owner/repo, for example yourname/wani-terminal."
        !GitHubApi.WORKFLOW_PATTERN.matches(workflow.trim()) -> "Workflow file name is not valid, for example build.yml."
        !GitHubApi.BRANCH_PATTERN.matches(branch.trim()) -> "Branch name is not valid."
        else -> null
    }

    fun connect() {
        checkInputs(needToken = true)?.let { status = it; return }
        val tok = token.trim()
        val repoName = repo.trim()
        val wf = workflow.trim()
        val br = branch.trim()
        runAction("Checking token") {
            val login = GitHubApi.login(tok)
            settings.githubToken = tok
            settings.githubRepo = repoName
            settings.githubWorkflow = wf
            settings.githubBranch = br
            "Connected as @$login. Token saved on this phone." to null
        }
    }

    fun startBuild() {
        checkInputs(needToken = true)?.let { status = it; return }
        val tok = token.trim()
        val repoName = repo.trim()
        val wf = workflow.trim()
        val br = branch.trim()
        runAction("Starting build") {
            GitHubApi.triggerBuild(tok, repoName, wf, br)
            "Build started on $repoName. Use Check latest build in a minute to see progress." to null
        }
    }

    fun checkBuild() {
        checkInputs(needToken = true)?.let { status = it; return }
        val tok = token.trim()
        val repoName = repo.trim()
        val wf = workflow.trim()
        runAction("Checking latest build") {
            val run = GitHubApi.latestRun(tok, repoName, wf)
            if (run == null) {
                "No runs yet for $wf." to null
            } else {
                val state = run.conclusion ?: run.status
                "Latest build: $state (started ${run.createdAt}). The APK is in the run's Artifacts section." to run.url
            }
        }
    }

    fun disconnect() {
        settings.githubToken = ""
        token = ""
        connected = false
        runUrl = null
        status = "Disconnected. Token removed from this phone."
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        BackHeader(title = "GitHub build", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("How to connect", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "1. Create a token at GitHub > Settings > Developer settings > Personal access tokens. " +
                    "Give it the repo and workflow scopes.\n" +
                    "2. Paste the token below and enter your repository.\n" +
                    "3. Tap Connect. Then Start APK build. GitHub Actions builds the APK for you.",
                color = Muted,
                fontSize = 12.sp
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub token") },
                placeholder = { Text("ghp_...", color = Muted) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("Repository (owner/repo)") },
                placeholder = { Text("yourname/wani-terminal", color = Muted) },
                singleLine = true,
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = workflow,
                onValueChange = { workflow = it },
                label = { Text("Workflow file") },
                singleLine = true,
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text("Branch") },
                singleLine = true,
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { if (!busy) connect() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect", fontWeight = FontWeight.Bold)
            }

            if (connected) {
                Button(
                    onClick = { if (!busy) startBuild() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start APK build", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { if (!busy) checkBuild() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check latest build")
                }
                runUrl?.let { url ->
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open run on GitHub")
                    }
                }
                OutlinedButton(
                    onClick = { disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect", color = ErrorRed)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                if (busy) "Working..." else status,
                color = if (status.startsWith("Failed") || status.startsWith("Repository") || status.startsWith("Paste")) ErrorRed else TextMain,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
