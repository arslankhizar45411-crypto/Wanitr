package com.wani.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, onOpenGitHub: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(settings.aiBaseUrl) }
    var model by remember { mutableStateOf(settings.aiModel) }
    var apiKey by remember { mutableStateOf(settings.aiApiKey) }
    var allowCommands by remember { mutableStateOf(settings.aiAllowCommands) }
    var autoApprove by remember { mutableStateOf(settings.aiAutoApprove) }
    var storageOk by remember { mutableStateOf(StoragePermission.isGranted(ctx)) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        BackHeader(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("AI assistant")
            SettingField("API base URL", baseUrl, { baseUrl = it; saved = false }, "https://api.openai.com/v1")
            SettingField("Model", model, { model = it; saved = false }, "gpt-4o-mini")
            SettingField("API key", apiKey, { apiKey = it; saved = false }, "paste your key", secret = true)
            Text(
                "Works with any OpenAI-compatible API. The key is stored only on this phone.",
                color = Muted,
                fontSize = 12.sp
            )

            ToggleRow(
                title = "Allow AI to run commands",
                subtitle = "The AI can run shell commands in the Linux environment.",
                checked = allowCommands,
                onChange = { allowCommands = it; saved = false }
            )
            ToggleRow(
                title = "Run without asking",
                subtitle = "Off (recommended): you approve each command with Yes or No.",
                checked = autoApprove,
                onChange = { autoApprove = it; saved = false }
            )

            SectionTitle("Storage")
            Text(
                if (storageOk) {
                    "Phone storage is allowed. In the shell, run wani-setup-storage to create ~/storage/downloads."
                } else {
                    "Phone storage is not allowed yet. Allow it so the terminal and AI can read your Downloads folder."
                },
                color = Muted,
                fontSize = 12.sp
            )
            if (!storageOk) {
                Button(
                    onClick = { scope.launch { storageOk = StoragePermission.request(ctx) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                ) {
                    Text("Allow storage", fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = {
                    settings.aiBaseUrl = baseUrl
                    settings.aiModel = model
                    settings.aiApiKey = apiKey
                    settings.aiAllowCommands = allowCommands
                    settings.aiAutoApprove = autoApprove
                    baseUrl = settings.aiBaseUrl
                    model = settings.aiModel
                    apiKey = settings.aiApiKey
                    saved = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
            ) {
                Text("Save settings", fontWeight = FontWeight.Bold)
            }
            if (saved) {
                Text("Saved.", color = Accent, fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("GitHub")
            Text(
                "Connect your GitHub token to start the APK build workflow from this phone.",
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = onOpenGitHub) {
                Text("Open GitHub build page")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Muted) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
