package com.wani.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AiScreen(ai: AiChat, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as WaniApp
    val messages by ai.messages.collectAsState()
    val busy by ai.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        ai.send(text)
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        BackHeader(title = "Wani AI", onBack = onBack) {
            TextButton(onClick = { switchTheme(app.settings) }) {
                Text(if (IsDarkTheme) "Light" else "Dark", color = Muted)
            }
            TextButton(onClick = { ai.clear() }) { Text("Clear", color = Muted) }
            TextButton(onClick = onOpenSettings) { Text("Settings", color = Muted) }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { m -> ChatBubble(m) }
            if (busy) {
                item { Text("Working…", color = Muted, fontSize = 12.sp) }
            }
        }

        // Quick prompts: sirf input bharte hain, bhejte nahi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Suggestion("Create a command file") {
                input = "Create /root/hello.sh that prints a greeting, make it executable, and run it."
            }
            Suggestion("Explain Downloads/doc.txt") {
                input = "Read /sdcard/Download/doc.txt and explain what it says."
            }
            Suggestion("Ask me with a page") {
                input = "Ask me with a live page which Debian packages I want, then install them in the background."
            }
            Suggestion("System info") {
                input = "Show the Debian version, CPU info and free disk space."
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { scope.launch { FilePicker.pick()?.let { ai.attachFile(it) } } },
                enabled = !busy
            ) {
                Text("Upload", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Wani AI", color = Muted) },
                textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                maxLines = 4,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Button(
                    onClick = { ai.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Color.Black)
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { submit() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) }
    )
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val label = when (m.role) {
        Role.USER -> "You"
        Role.ASSISTANT -> "Wani AI"
        Role.TOOL -> "Shell"
        Role.SYSTEM -> "Info"
        Role.ERROR -> "Error"
    }
    val labelColor = when (m.role) {
        Role.USER -> Accent
        Role.ERROR -> ErrorRed
        Role.TOOL -> Amber
        else -> Muted
    }
    val isUser = m.role == Role.USER
    val mono = m.role == Role.TOOL

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(label, color = labelColor, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Surface(
            color = if (isUser) Chip else Panel,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                m.text,
                color = if (m.role == Role.ERROR) ErrorRed else TextMain,
                fontSize = if (mono) 12.sp else 14.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}
