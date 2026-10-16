package com.wani.terminal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Theme colors state mein hain, taake change hote hi screen update ho.
// Dark = black, Light = white. Terminal bhi isi theme par chalta hai.
internal var IsDarkTheme by mutableStateOf(true)
internal var Bg by mutableStateOf(Color(0xFF000000))
internal var Panel by mutableStateOf(Color(0xFF121317))
internal var Chip by mutableStateOf(Color(0xFF23252D))
internal var Accent by mutableStateOf(Color(0xFF5EE0A8))
internal var Amber by mutableStateOf(Color(0xFFE0AF68))
internal var ErrorRed by mutableStateOf(Color(0xFFF7768E))
internal var TextMain by mutableStateOf(Color(0xFFE6E8EE))
internal var Muted by mutableStateOf(Color(0xFF8A8F9E))

internal var TermBg by mutableStateOf(Color(0xFF000000))
internal var TermFg by mutableStateOf(Color(0xFFE6E8EE))

/** Sirf text measure karne ke liye (cell size). Rang termTextStyle() se aata hai. */
internal val TermStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp
)

internal fun termTextStyle(): TextStyle = TermStyle.copy(color = TermFg)

fun applyTheme(dark: Boolean) {
    IsDarkTheme = dark
    if (dark) {
        Bg = Color(0xFF000000)
        Panel = Color(0xFF121317)
        Chip = Color(0xFF23252D)
        Accent = Color(0xFF5EE0A8)
        Amber = Color(0xFFE0AF68)
        ErrorRed = Color(0xFFF7768E)
        TextMain = Color(0xFFE6E8EE)
        Muted = Color(0xFF8A8F9E)
        TermBg = Color(0xFF000000)
        TermFg = Color(0xFFE6E8EE)
        TermColors.fg = 0xFFE6E8EE.toInt()
        TermColors.bg = 0xFF000000.toInt()
    } else {
        Bg = Color(0xFFFFFFFF)
        Panel = Color(0xFFF2F3F6)
        Chip = Color(0xFFE3E6EC)
        Accent = Color(0xFF0F9D6A)
        Amber = Color(0xFFB7791F)
        ErrorRed = Color(0xFFD64545)
        TextMain = Color(0xFF111318)
        Muted = Color(0xFF6B7280)
        TermBg = Color(0xFFFFFFFF)
        TermFg = Color(0xFF111318)
        TermColors.fg = 0xFF111318.toInt()
        TermColors.bg = 0xFFFFFFFF.toInt()
    }
}

/** Dark <-> light, aur setting mein save. */
fun switchTheme(settings: AppSettings) {
    val dark = !IsDarkTheme
    settings.darkTheme = dark
    applyTheme(dark)
}
