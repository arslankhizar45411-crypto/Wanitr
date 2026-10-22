package com.collabcode.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class CCColors(
    val bg: Color, val surface: Color, val surface2: Color, val border: Color,
    val text: Color, val muted: Color, val accent: Color, val accent2: Color,
    val green: Color, val red: Color, val yellow: Color, val purple: Color,
    val str: Color, val comment: Color, val lineNo: Color,
)

val DarkCC = CCColors(
    bg = Color(0xFF111318), surface = Color(0xFF1B1D23), surface2 = Color(0xFF23262E),
    border = Color(0xFF2E323C), text = Color(0xFFE6E8EE), muted = Color(0xFF8B909C),
    accent = Color(0xFF6E56F0), accent2 = Color(0xFF8E6CF5), green = Color(0xFF4EC9B0),
    red = Color(0xFFF47174), yellow = Color(0xFFE5C07B), purple = Color(0xFFC792EA),
    str = Color(0xFFCE9178), comment = Color(0xFF6A9955), lineNo = Color(0xFF55596A),
)

val LightCC = CCColors(
    bg = Color(0xFFF7F7FB), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0F2F7),
    border = Color(0xFFDFE3EC), text = Color(0xFF1D2130), muted = Color(0xFF6B7185),
    accent = Color(0xFF6E56F0), accent2 = Color(0xFF8E6CF5), green = Color(0xFF0E7C66),
    red = Color(0xFFC62828), yellow = Color(0xFF9A6700), purple = Color(0xFF7B3FBF),
    str = Color(0xFFA0522D), comment = Color(0xFF5A7F3A), lineNo = Color(0xFFA0A6B5),
)

val LocalCC = staticCompositionLocalOf { DarkCC }

fun ccGradient(c: CCColors): Brush = Brush.linearGradient(listOf(c.accent, c.accent2))

@Composable
fun CollabCodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val cc = if (dark) DarkCC else LightCC
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = cc.accent,
        background = cc.bg,
        surface = cc.surface,
        onSurface = cc.text,
        onBackground = cc.text,
        surfaceVariant = cc.surface2,
        outline = cc.border,
    )
    CompositionLocalProvider(LocalCC provides cc) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
