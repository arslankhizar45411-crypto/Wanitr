package com.wani.terminal

import android.content.Intent
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

/** Tayyar AI setups. Naam ya model badal sakte hain: provider ki list dekh kar sahi model likhein. */
private data class AiPreset(val name: String, val baseUrl: String, val model: String)

private val AI_PRESETS = listOf(
    AiPreset("OpenRouter (free)", "https://openrouter.ai/api/v1", "openrouter/free"),
    AiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    AiPreset("Groq (fast)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    AiPreset("Local on phone", "http://127.0.0.1:8080/v1", "local-model")
)

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, onOpenGitHub: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(settings.aiBaseUrl) }
    var model by remember { mutableStateOf(settings.aiModel) }
    var apiKey by remember { mutableStateOf(settings.aiApiKey) }
    var customInstructions by remember { mutableStateOf(settings.aiCustomInstructions) }
    var allowCommands by remember { mutableStateOf(settings.aiAllowCommands) }
    var autoApprove by remember { mutableStateOf(settings.aiAutoApprove) }
    var showInTerminal by remember { mutableStateOf(settings.aiShowInTerminal) }
    var shortcuts by remember { mutableStateOf(settings.shortcutKeys) }
    var lightMode by remember { mutableStateOf(!settings.darkTheme) }
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
            Text("Quick setup", color = Muted, fontSize = 12.sp)
            AI_PRESETS.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { p ->
                        OutlinedButton(
                            onClick = {
                                baseUrl = p.baseUrl
                                model = p.model
                                saved = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(p.name, fontSize = 12.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            SettingField("API base URL", baseUrl, { baseUrl = it; saved = false }, "https://openrouter.ai/api/v1")
            SettingField("Model", model, { model = it; saved = false }, "openrouter/free")
            SettingField("API key", apiKey, { apiKey = it; saved = false }, "paste your key", secret = true)
            Text(
                "Works with any OpenAI-compatible API. Model names change often, so check your provider's list. " +
                    "Local server on this phone: http://127.0.0.1:PORT/v1 (key can be empty). The key is stored only on this phone.",
                color = Muted,
                fontSize = 12.sp
            )

            OutlinedTextField(
                value = customInstructions,
                onValueChange = { customInstructions = it; saved = false },
                label = { Text("Extra instructions for the AI") },
                placeholder = {
                    Text("Example: reply in Urdu. Prefer apt-get. Ask me before deleting any file.", color = Muted)
                },
                textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
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
            ToggleRow(
                title = "Show AI actions in terminal",
                subtitle = "AI commands and their output also appear in the terminal screen.",
                checked = showInTerminal,
                onChange = { showInTerminal = it; saved = false }
            )
            ToggleRow(
                title = "Light theme",
                subtitle = "Off = dark theme. The terminal always stays dark.",
                checked = lightMode,
                onChange = {
                    lightMode = it
                    settings.darkTheme = !it
                    applyTheme(!it)
                }
            )

            Button(
                onClick = {
                    settings.aiBaseUrl = baseUrl
                    settings.aiModel = model
                    settings.aiApiKey = apiKey
                    settings.aiCustomInstructions = customInstructions
                    settings.aiAllowCommands = allowCommands
                    settings.aiAutoApprove = autoApprove
                    settings.aiShowInTerminal = showInTerminal
                    baseUrl = settings.aiBaseUrl
                    model = settings.aiModel
                    apiKey = settings.aiApiKey
                    customInstructions = settings.aiCustomInstructions
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
            SectionTitle("Shortcut bar")
            Text(
                "Tap a button to show or hide it at the bottom of the terminal. Shown buttons keep this order. " +
                    "Backspace is not in the bar: use your keyboard's delete key.",
                color = Muted,
                fontSize = 12.sp
            )
            ShortcutKeys.ALL.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { k ->
                        val on = k.id in shortcuts
                        KeyButton(k.label, Modifier.weight(1f), active = on) {
                            val next = if (on) shortcuts - k.id else shortcuts + k.id
                            // Bar khali na ho, isliye kam se kam ek button hamesha rehta hai
                            if (next.isNotEmpty()) {
                                shortcuts = next
                                settings.shortcutKeys = next
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            OutlinedButton(onClick = {
                shortcuts = ShortcutKeys.DEFAULT
                settings.shortcutKeys = ShortcutKeys.DEFAULT
            }) {
                Text("Reset shortcut bar", fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Run 24/7")
            Text(
                "The AI keeps working while the app runs in the background (Wani shows a notification). " +
                    "On some phones, battery saving stops background apps. Set Wani to unrestricted to keep it running.",
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text("Open battery settings")
            }

            Spacer(Modifier.height(8.dp))
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
