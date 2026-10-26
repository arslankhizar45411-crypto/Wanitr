package com.collabcode.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The colours of the design (light and dark), as in its CSS variables. */
data class AgentPalette(
    val bg: Color, val sf: Color, val s2: Color, val bd: Color,
    val tx: Color, val mu: Color, val ac: Color, val ok: Color, val wn: Color, val er: Color,
)

val LightAgent = AgentPalette(
    bg = Color(0xFFF4F6F8), sf = Color(0xFFFFFFFF), s2 = Color(0xFFECEFF3), bd = Color(0xFFD3D9E0),
    tx = Color(0xFF1B2026), mu = Color(0xFF586270), ac = Color(0xFF0B62D6), ok = Color(0xFF17782F),
    wn = Color(0xFF945F00), er = Color(0xFFC4202B),
)

val DarkAgent = AgentPalette(
    bg = Color(0xFF0C1016), sf = Color(0xFF151A22), s2 = Color(0xFF1E2530), bd = Color(0xFF2D3643),
    tx = Color(0xFFE8EDF3), mu = Color(0xFF92A0B0), ac = Color(0xFF5AA9FF), ok = Color(0xFF43BD5A),
    wn = Color(0xFFD6A02A), er = Color(0xFFFF6B63),
)

val LocalAgent = staticCompositionLocalOf { LightAgent }
