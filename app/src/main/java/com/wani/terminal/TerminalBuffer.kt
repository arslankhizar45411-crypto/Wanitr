package com.wani.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Terminal output ko styled text mein badalta hai: ANSI colors, bold, underline,
 * \r (line overwrite), backspace. Cursor movement support nahi hai. Thread-safe.
 */
class TerminalBuffer(private val maxChars: Int) {

    private data class Style(
        val fg: Int = 0,
        val bg: Int = 0,
        val bold: Boolean = false,
        val underline: Boolean = false
    )

    private class Run(val style: Style, val sb: StringBuilder)

    private enum class Mode { TEXT, ESC, CSI, OSC, OSC_ESC, SKIP_ONE }

    private val runs = ArrayList<Run>()
    private var total = 0
    private var style = Style()
    private var mode = Mode.TEXT
    private val csi = StringBuilder()
    private var pendingCr = false

    @Synchronized
    fun write(s: String) {
        for (c in s) feed(c)
        trim()
    }

    @Synchronized
    fun clear() {
        runs.clear()
        total = 0
        pendingCr = false
        mode = Mode.TEXT
        csi.setLength(0)
        style = Style()
    }

    @Synchronized
    fun snapshot(): AnnotatedString = buildAnnotatedString {
        for (run in runs) {
            val st = run.style
            if (st == PLAIN) {
                append(run.sb.toString())
            } else {
                val span = SpanStyle(
                    color = if (st.fg != 0) Color(st.fg) else Color.Unspecified,
                    background = if (st.bg != 0) Color(st.bg) else Color.Unspecified,
                    fontWeight = if (st.bold) FontWeight.Bold else null,
                    textDecoration = if (st.underline) TextDecoration.Underline else null
                )
                withStyle(span) { append(run.sb.toString()) }
            }
        }
    }

    private fun feed(c: Char) {
        when (mode) {
            Mode.TEXT -> text(c)
            Mode.ESC -> {
                mode = when (c) {
                    '[' -> {
                        csi.setLength(0)
                        Mode.CSI
                    }
                    ']' -> Mode.OSC
                    '(', ')', '*', '+', '#' -> Mode.SKIP_ONE
                    else -> Mode.TEXT
                }
            }
            Mode.CSI -> {
                if (c in '@'..'~') {
                    mode = Mode.TEXT
                    csiDone(c)
                } else if (csi.length < 64) {
                    csi.append(c)
                } else {
                    mode = Mode.TEXT
                }
            }
            Mode.OSC -> {
                if (c == '\u0007') {
                    mode = Mode.TEXT
                } else if (c == '\u001B') {
                    mode = Mode.OSC_ESC
                }
            }
            Mode.OSC_ESC -> mode = Mode.TEXT
            Mode.SKIP_ONE -> mode = Mode.TEXT
        }
    }

    private fun text(c: Char) {
        when {
            c == '\u001B' -> mode = Mode.ESC
            c == '\n' -> {
                pendingCr = false
                append('\n')
            }
            c == '\r' -> pendingCr = true
            c == '\b' -> if (!pendingCr) deleteLast()
            c == '\t' -> {
                resolveCr()
                append(c)
            }
            c < ' ' || c == '\u007F' -> { /* baaki control chars ignore */ }
            else -> {
                resolveCr()
                append(c)
            }
        }
    }

    private fun resolveCr() {
        if (pendingCr) {
            pendingCr = false
            truncateLine()
        }
    }

    private fun csiDone(fin: Char) {
        val raw = csi.toString()
        csi.setLength(0)
        if (raw.isNotEmpty() && raw[0] in "?>=<!") return
        when (fin) {
            'm' -> sgr(raw)
            'K' -> resolveCr()
            'J' -> {
                val p = raw.toIntOrNull() ?: 0
                if (p == 2 || p == 3) {
                    runs.clear()
                    total = 0
                    pendingCr = false
                }
            }
            else -> { /* cursor movement waghera ignore */ }
        }
    }

    private fun sgr(raw: String) {
        val parts = if (raw.isEmpty()) listOf(0) else raw.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var st = style
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            when {
                p == 0 -> st = Style()
                p == 1 -> st = st.copy(bold = true)
                p == 22 -> st = st.copy(bold = false)
                p == 4 -> st = st.copy(underline = true)
                p == 24 -> st = st.copy(underline = false)
                p in 30..37 -> st = st.copy(fg = ANSI16[p - 30])
                p in 90..97 -> st = st.copy(fg = ANSI16[p - 90 + 8])
                p == 39 -> st = st.copy(fg = 0)
                p in 40..47 -> st = st.copy(bg = ANSI16[p - 40])
                p in 100..107 -> st = st.copy(bg = ANSI16[p - 100 + 8])
                p == 49 -> st = st.copy(bg = 0)
                p == 38 || p == 48 -> {
                    val isFg = p == 38
                    val kind = parts.getOrNull(i + 1)
                    if (kind == 5 && i + 2 < parts.size) {
                        val col = palette256(parts[i + 2])
                        st = if (isFg) st.copy(fg = col) else st.copy(bg = col)
                        i += 2
                    } else if (kind == 2 && i + 4 < parts.size) {
                        val col = rgb(parts[i + 2], parts[i + 3], parts[i + 4])
                        st = if (isFg) st.copy(fg = col) else st.copy(bg = col)
                        i += 4
                    }
                }
            }
            i++
        }
        style = st
    }

    private fun append(c: Char) {
        val last = runs.lastOrNull()
        if (last != null && last.style == style) {
            last.sb.append(c)
        } else {
            runs.add(Run(style, StringBuilder().append(c)))
        }
        total++
    }

    private fun deleteLast() {
        while (true) {
            val last = runs.lastOrNull() ?: return
            if (last.sb.isEmpty()) {
                runs.removeAt(runs.size - 1)
                continue
            }
            if (last.sb[last.sb.length - 1] == '\n') return
            last.sb.setLength(last.sb.length - 1)
            total--
            return
        }
    }

    private fun truncateLine() {
        while (true) {
            val last = runs.lastOrNull() ?: return
            val nl = last.sb.lastIndexOf('\n')
            if (nl >= 0) {
                total -= last.sb.length - (nl + 1)
                last.sb.setLength(nl + 1)
                return
            }
            total -= last.sb.length
            runs.removeAt(runs.size - 1)
        }
    }

    private fun trim() {
        if (total <= maxChars + maxChars / 4) return
        var excess = total - maxChars
        while (excess > 0 && runs.isNotEmpty()) {
            val first = runs[0]
            if (first.sb.length <= excess) {
                excess -= first.sb.length
                total -= first.sb.length
                runs.removeAt(0)
            } else {
                first.sb.delete(0, excess)
                total -= excess
                excess = 0
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun palette256(n: Int): Int = when (n) {
        in 0..15 -> ANSI16[n]
        in 16..231 -> {
            val k = n - 16
            fun lvl(x: Int) = if (x == 0) 0 else 55 + 40 * x
            rgb(lvl(k / 36), lvl((k / 6) % 6), lvl(k % 6))
        }
        in 232..255 -> {
            val g = 8 + (n - 232) * 10
            rgb(g, g, g)
        }
        else -> 0
    }

    companion object {
        private val PLAIN = Style()

        private val ANSI16 = intArrayOf(
            0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF565F89.toInt(), 0xFFFF899D.toInt(), 0xFF9FE044.toInt(), 0xFFFABA4A.toInt(),
            0xFF8DB0FF.toInt(), 0xFFC7A9FF.toInt(), 0xFFA4DAFF.toInt(), 0xFFC0CAF5.toInt()
        )
    }
}
