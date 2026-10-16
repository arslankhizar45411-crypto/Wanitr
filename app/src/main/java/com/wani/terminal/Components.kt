package com.wani.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shortcut key ka chip (single line mein). */
@Composable
internal fun KeyChip(
    label: String,
    accent: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        accent -> Accent
        active -> Accent.copy(alpha = 0.45f)
        else -> Chip
    }
    val fg = if (accent || active) Color.Black else TextMain
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (accent || active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
internal fun StatusPill(running: Boolean) {
    val c = if (running) Accent else Amber
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(c)
        )
        Spacer(Modifier.width(6.dp))
        Text(if (running) "running" else "stopped", color = c, fontSize = 11.sp)
    }
}

/** Screen ka header: Back button, title, aur optional right side buttons. */
@Composable
internal fun BackHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text("< Back", color = Accent)
        }
        Text(
            title,
            color = TextMain,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** Shortcut button: barabar size (weight ke saath), ek hi line mein. */
@Composable
internal fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        accent -> Accent
        active -> Accent.copy(alpha = 0.45f)
        else -> Chip
    }
    val fg = if (accent || active) Color.Black else TextMain
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = if (accent || active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
