package com.wani.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Ek hi jagah par saare rang. Icon ke colors (res/) yahan se alag hain aur change nahi hue.
internal val Bg = Color(0xFF0D0E14)
internal val Panel = Color(0xFF171923)
internal val TermBg = Color(0xFF11121A)
internal val Chip = Color(0xFF242736)
internal val Accent = Color(0xFF5EE0A8)
internal val Amber = Color(0xFFE0AF68)
internal val ErrorRed = Color(0xFFF7768E)
internal val TextMain = Color(0xFFD7DAE3)
internal val Muted = Color(0xFF7C8196)

internal val TermStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = TextMain
)
