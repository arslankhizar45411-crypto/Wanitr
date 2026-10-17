package com.wani.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import java.util.Base64

/** Ek character cell: character aur uska rang/style. */
data class Cell(
    val ch: Char = ' ',
    val fg: Int = 0,
    val bg: Int = 0,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false
)

private val BLANK = Cell()

/**
 * Mini xterm emulator. Ye samajhta hai: cursor movement, screen erase, alternate screen
 * (nano, vim, top, htop ke liye), scroll region, SGR colors, cursor position report,
 * aur inline images (OSC 1337 File=). Sab public functions synchronized hain.
 */
class TerminalBuffer(maxChars: Int) {

    private val maxScrollback = (maxChars / 40).coerceIn(200, 5000)

    private var cols = 80
    private var rows = 24
    private var screen: Array<Array<Cell>> = newGrid(rows, cols)
    private val scrollback = ArrayList<Array<Cell>>()

    private var curRow = 0
    private var curCol = 0
    private var pendingWrap = false
    private var style = Cell()
    private var top = 0
    private var bottom = rows - 1
    private var autoWrap = true
    private var cursorVisible = true
    /** DECCKM (?1): nano, vim, htop arrow keys ESC O x maangte hain. */
    private var appCursor = false
    /** Bracketed paste (?2004): paste ko shell ek saath padhta hai. */
    private var bracketedPaste = false

    private var onAlt = false
    private var savedPrimary: Array<Array<Cell>>? = null
    private var saveRow = 0
    private var saveCol = 0
    private var saveStyle = Cell()

    private enum class Mode { TEXT, ESC, CSI, OSC, OSC_ESC, STR, STR_ESC, CHARSET }

    private var mode = Mode.TEXT
    private val csi = StringBuilder()
    private val osc = StringBuilder()
    private var oscOverflow = false

    /** Shell ko jawab bhejne ke liye (jaise cursor position report). */
    var onReply: ((String) -> Unit)? = null

    /** Inline image mili: raw bytes aur file ka naam. */
    var onImage: ((ByteArray, String) -> Unit)? = null

    @Synchronized
    fun write(s: String) {
        for (c in s) feed(c)
    }

    /** Screen aur scrollback dono saaf karta hai. */
    @Synchronized
    fun clear() {
        scrollback.clear()
        resetGrid(cols, rows)
    }

    /** Naya session shuru hone par grid ka size set karta hai. Scrollback rehta hai. */
    @Synchronized
    fun resetGrid(newCols: Int, newRows: Int) {
        cols = newCols.coerceIn(20, 300)
        rows = newRows.coerceIn(5, 100)
        screen = newGrid(rows, cols)
        savedPrimary = null
        onAlt = false
        curRow = 0
        curCol = 0
        pendingWrap = false
        top = 0
        bottom = rows - 1
        style = Cell()
        mode = Mode.TEXT
        autoWrap = true
        cursorVisible = true
        appCursor = false
        bracketedPaste = false
    }

    /**
     * Screen ka size badalta hai (keyboard khulna/band hona, rotation). Content bachta hai:
     * jo lines upar se nikalti hain wo scrollback mein jati hain (sirf main screen par).
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(20, 300)
        val nr = newRows.coerceIn(5, 100)
        if (nc == cols && nr == rows) return
        val oldGrid = screen
        val oldCols = cols
        val oldRows = rows
        val drop = if (curRow >= nr) curRow - nr + 1 else 0
        if (!onAlt) for (r in 0 until drop) pushScrollback(oldGrid[r])
        cols = nc
        rows = nr
        screen = copyGrid(oldGrid, oldRows, oldCols, drop, nr, nc)
        savedPrimary = savedPrimary?.let { copyGrid(it, oldRows, oldCols, 0, nr, nc) }
        curRow = (curRow - drop).coerceIn(0, nr - 1)
        curCol = curCol.coerceIn(0, nc - 1)
        pendingWrap = false
        top = 0
        bottom = nr - 1
    }

    private fun copyGrid(src: Array<Array<Cell>>, srcRows: Int, srcCols: Int, startRow: Int, nr: Int, nc: Int): Array<Array<Cell>> {
        val grid = newGrid(nr, nc)
        for (r in 0 until nr) {
            val sr = r + startRow
            if (sr < 0 || sr >= srcRows) continue
            for (c in 0 until minOf(nc, srcCols)) grid[r][c] = src[sr][c]
        }
        return grid
    }

    /** Arrow keys ka mode: true ho to ESC O A waghera bhejo, warna ESC [ A. */
    @Synchronized
    fun appCursorKeys(): Boolean = appCursor

    /** true ho to paste ko ESC[200~ ... ESC[201~ mein lapeto. */
    @Synchronized
    fun bracketedPasteMode(): Boolean = bracketedPaste

    @Synchronized
    fun isAltScreen(): Boolean = onAlt

    @Synchronized
    fun snapshot(maxRows: Int = Int.MAX_VALUE): AnnotatedString = buildAnnotatedString {
        if (!onAlt) {
            // Sirf aakhri lines: lambi history ka render UI ko slow karta hai
            val room = (maxRows - rows).coerceAtLeast(0)
            val start = (scrollback.size - room).coerceAtLeast(0)
            for (i in start until scrollback.size) appendRow(this, scrollback[i], -1)
        }
        for (r in 0 until rows) {
            appendRow(this, screen[r], if (cursorVisible && r == curRow) curCol else -1)
        }
    }

    // ------------------------------------------------------------ parser

    private fun feed(c: Char) {
        when (mode) {
            Mode.TEXT -> text(c)
            Mode.ESC -> escape(c)
            Mode.CSI -> {
                if (c in '@'..'~') {
                    mode = Mode.TEXT
                    csiDispatch(c)
                } else if (csi.length < 128) {
                    csi.append(c)
                } else {
                    mode = Mode.TEXT
                    csi.setLength(0)
                }
            }
            Mode.OSC -> {
                if (c == '\u0007') {
                    finishOsc()
                } else if (c == '\u001B') {
                    mode = Mode.OSC_ESC
                } else if (osc.length < OSC_MAX) {
                    osc.append(c)
                } else {
                    oscOverflow = true
                }
            }
            Mode.OSC_ESC -> {
                if (c == '\\') {
                    finishOsc()
                } else {
                    finishOsc()
                    escape(c)
                }
            }
            Mode.STR -> {
                if (c == '\u001B') mode = Mode.STR_ESC
            }
            Mode.STR_ESC -> {
                mode = if (c == '\\') Mode.TEXT else Mode.STR
            }
            Mode.CHARSET -> {
                mode = Mode.TEXT
            }
        }
    }

    private fun escape(c: Char) {
        mode = Mode.TEXT
        when (c) {
            '[' -> {
                csi.setLength(0)
                mode = Mode.CSI
            }
            ']' -> {
                osc.setLength(0)
                oscOverflow = false
                mode = Mode.OSC
            }
            'P', 'X', '^', '_' -> mode = Mode.STR
            '(', ')', '*', '+', '#', '%' -> mode = Mode.CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                curCol = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            'c' -> fullReset()
            else -> Unit
        }
    }

    private fun text(c: Char) {
        when (c) {
            '\u001B' -> mode = Mode.ESC
            '\n', '\u000B', '\u000C' -> {
                curCol = 0
                lineFeed()
            }
            '\r' -> {
                curCol = 0
                pendingWrap = false
            }
            '\b' -> {
                if (curCol > 0) curCol--
                pendingWrap = false
            }
            '\t' -> {
                curCol = (((curCol / 8) + 1) * 8).coerceAtMost(cols - 1)
                pendingWrap = false
            }
            else -> {
                if (c >= ' ' && c != '\u007F') printChar(c)
            }
        }
    }

    private fun csiDispatch(fin: Char) {
        val raw = csi.toString()
        csi.setLength(0)
        if (raw.isNotEmpty() && raw[0] == '?') {
            privateMode(raw.substring(1), fin)
            return
        }
        if (raw.isNotEmpty() && raw[0] in ">=<!") return

        val p: List<Int> = if (raw.isEmpty()) emptyList() else raw.split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int, def: Int): Int = p.getOrNull(i)?.takeIf { it > 0 } ?: def

        when (fin) {
            'A' -> setCursor(curRow - arg(0, 1), curCol)
            'B' -> setCursor(curRow + arg(0, 1), curCol)
            'C' -> setCursor(curRow, curCol + arg(0, 1))
            'D' -> setCursor(curRow, curCol - arg(0, 1))
            'E' -> setCursor(curRow + arg(0, 1), 0)
            'F' -> setCursor(curRow - arg(0, 1), 0)
            'G', '`' -> setCursor(curRow, arg(0, 1) - 1)
            'd' -> setCursor(arg(0, 1) - 1, curCol)
            'H', 'f' -> setCursor(arg(0, 1) - 1, arg(1, 1) - 1)
            'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseLine(p.getOrNull(0) ?: 0)
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            '@' -> insertChars(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'r' -> setScrollRegion(arg(0, 1) - 1, (p.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1)
            'm' -> sgr(raw.replace(':', ';'))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> {
                if (p.getOrNull(0) == 6) onReply?.invoke("\u001B[${curRow + 1};${curCol + 1}R")
            }
            'c' -> {
                if (raw.isEmpty() || raw == "0") onReply?.invoke("\u001B[?1;2c")
            }
            else -> Unit
        }
    }

    private fun privateMode(params: String, fin: Char) {
        if (fin != 'h' && fin != 'l') return
        val on = fin == 'h'
        for (m in params.split(';')) {
            when (m.toIntOrNull()) {
                1049, 1047, 47 -> {
                    if (on) enterAlt() else leaveAlt()
                }
                1 -> {
                    appCursor = on
                }
                2004 -> {
                    bracketedPaste = on
                }
                7 -> {
                    autoWrap = on
                }
                25 -> {
                    cursorVisible = on
                }
                else -> Unit
            }
        }
    }

    private fun sgr(raw: String) {
        val parts = if (raw.isEmpty()) listOf(0) else raw.split(';').map { it.toIntOrNull() ?: 0 }
        var st = style
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            st = when (p) {
                0 -> Cell()
                1 -> st.copy(bold = true)
                4 -> st.copy(underline = true)
                7 -> st.copy(inverse = true)
                22 -> st.copy(bold = false)
                24 -> st.copy(underline = false)
                27 -> st.copy(inverse = false)
                in 30..37 -> st.copy(fg = ANSI16[p - 30])
                in 90..97 -> st.copy(fg = ANSI16[p - 90 + 8])
                39 -> st.copy(fg = 0)
                in 40..47 -> st.copy(bg = ANSI16[p - 40])
                in 100..107 -> st.copy(bg = ANSI16[p - 100 + 8])
                49 -> st.copy(bg = 0)
                38, 48 -> {
                    val kind = parts.getOrNull(i + 1)
                    val color: Int? = when {
                        kind == 5 && i + 2 < parts.size -> {
                            i += 2
                            palette256(parts[i])
                        }
                        kind == 2 && i + 4 < parts.size -> {
                            i += 4
                            rgb(parts[i - 2], parts[i - 1], parts[i])
                        }
                        else -> null
                    }
                    if (color == null) st else if (p == 38) st.copy(fg = color) else st.copy(bg = color)
                }
                else -> st
            }
            i++
        }
        style = st
    }

    // ------------------------------------------------------------ screen ops

    private fun printChar(c: Char) {
        if (pendingWrap) {
            pendingWrap = false
            curCol = 0
            lineFeed()
        }
        screen[curRow][curCol] = Cell(c, style.fg, style.bg, style.bold, style.underline, style.inverse)
        if (curCol < cols - 1) {
            curCol++
        } else if (autoWrap) {
            pendingWrap = true
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (curRow == bottom) {
            scrollUp(1)
        } else if (curRow < rows - 1) {
            curRow++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (curRow == top) {
            scrollDown(1)
        } else if (curRow > 0) {
            curRow--
        }
    }

    private fun setCursor(row: Int, col: Int) {
        curRow = row.coerceIn(0, rows - 1)
        curCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun setScrollRegion(t: Int, b: Int) {
        val nt = t.coerceIn(0, rows - 1)
        val nb = b.coerceIn(0, rows - 1)
        if (nt < nb) {
            top = nt
            bottom = nb
        }
        setCursor(0, 0)
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            val removed = screen[top]
            for (r in top until bottom) screen[r] = screen[r + 1]
            screen[bottom] = blankRow()
            if (top == 0 && !onAlt) pushScrollback(removed)
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in bottom downTo top + 1) screen[r] = screen[r - 1]
            screen[top] = blankRow()
        }
    }

    private fun pushScrollback(row: Array<Cell>) {
        scrollback.add(row)
        if (scrollback.size > maxScrollback) scrollback.removeAt(0)
    }

    private fun eraseLine(how: Int) {
        val row = screen[curRow]
        val from = if (how == 1 || how == 2) 0 else curCol
        val to = if (how == 0 || how == 2) cols - 1 else curCol
        for (i in from..to) row[i] = erased()
    }

    private fun eraseDisplay(how: Int) {
        when (how) {
            0 -> {
                eraseLine(0)
                for (r in curRow + 1 until rows) screen[r] = blankRow()
            }
            1 -> {
                for (r in 0 until curRow) screen[r] = blankRow()
                eraseLine(1)
            }
            2 -> {
                for (r in 0 until rows) screen[r] = blankRow()
            }
            3 -> scrollback.clear()
            else -> Unit
        }
    }

    private fun insertLines(n: Int) {
        if (curRow < top || curRow > bottom) return
        repeat(n.coerceAtMost(bottom - curRow + 1)) {
            for (r in bottom downTo curRow + 1) screen[r] = screen[r - 1]
            screen[curRow] = blankRow()
        }
        curCol = 0
    }

    private fun deleteLines(n: Int) {
        if (curRow < top || curRow > bottom) return
        repeat(n.coerceAtMost(bottom - curRow + 1)) {
            for (r in curRow until bottom) screen[r] = screen[r + 1]
            screen[bottom] = blankRow()
        }
        curCol = 0
    }

    private fun deleteChars(n: Int) {
        val row = screen[curRow]
        val cnt = n.coerceIn(1, cols - curCol)
        for (i in curCol until cols) {
            row[i] = if (i + cnt < cols) row[i + cnt] else BLANK
        }
    }

    private fun insertChars(n: Int) {
        val row = screen[curRow]
        val cnt = n.coerceIn(1, cols - curCol)
        for (i in cols - 1 downTo curCol + cnt) row[i] = row[i - cnt]
        for (i in curCol until curCol + cnt) row[i] = BLANK
    }

    private fun eraseChars(n: Int) {
        val row = screen[curRow]
        val end = minOf(cols, curCol + n.coerceAtLeast(1))
        for (i in curCol until end) row[i] = erased()
    }

    private fun saveCursor() {
        saveRow = curRow
        saveCol = curCol
        saveStyle = style
    }

    private fun restoreCursor() {
        setCursor(saveRow, saveCol)
        style = saveStyle
    }

    private fun enterAlt() {
        if (onAlt) return
        saveCursor()
        savedPrimary = screen
        onAlt = true
        screen = newGrid(rows, cols)
        top = 0
        bottom = rows - 1
        setCursor(0, 0)
    }

    private fun leaveAlt() {
        if (!onAlt) return
        onAlt = false
        screen = savedPrimary ?: newGrid(rows, cols)
        savedPrimary = null
        top = 0
        bottom = rows - 1
        restoreCursor()
    }

    private fun fullReset() {
        screen = newGrid(rows, cols)
        top = 0
        bottom = rows - 1
        style = Cell()
        autoWrap = true
        cursorVisible = true
        setCursor(0, 0)
    }

    // ------------------------------------------------------------ OSC (images, title)

    private fun finishOsc() {
        mode = Mode.TEXT
        val s = osc.toString()
        osc.setLength(0)
        if (!oscOverflow && s.startsWith("1337;File=")) {
            handleInlineFile(s.substring(10))
        }
        oscOverflow = false
    }

    /** Format: 1337;File=inline=1;name=BASE64NAME:BASE64DATA */
    private fun handleInlineFile(body: String) {
        val colon = body.indexOf(':')
        if (colon < 0) return
        val params = body.substring(0, colon).split(';')
        if (!params.contains("inline=1")) return
        var name = "image"
        for (kv in params) {
            if (kv.startsWith("name=")) {
                name = runCatching { String(Base64.getDecoder().decode(kv.substring(5))) }.getOrDefault("image")
            }
        }
        val bytes = runCatching { Base64.getMimeDecoder().decode(body.substring(colon + 1)) }.getOrNull() ?: return
        onImage?.invoke(bytes, name)
    }

    // ------------------------------------------------------------ helpers

    private fun appendRow(b: AnnotatedString.Builder, row: Array<Cell>, cursorCol: Int) {
        var end = row.size
        while (end > 0 && row[end - 1] == BLANK && end - 1 != cursorCol) end--
        var i = 0
        while (i < end) {
            val cell = row[i]
            val isCursor = i == cursorCol
            var j = i + 1
            if (!isCursor) {
                while (j < end && j != cursorCol && sameStyle(row[j], cell)) j++
            }
            val text = StringBuilder()
            for (k in i until j) text.append(row[k].ch)
            val span = spanFor(cell, isCursor)
            if (span == null) {
                b.append(text.toString())
            } else {
                b.withStyle(span) { append(text.toString()) }
            }
            i = j
        }
        b.append('\n')
    }

    private fun spanFor(c: Cell, cursor: Boolean): SpanStyle? {
        var fg = if (c.fg != 0) c.fg else TermColors.fg
        var bg = c.bg
        if (c.inverse != cursor) {
            val t = fg
            fg = if (bg != 0) bg else TermColors.bg
            bg = t
        }
        if (fg == TermColors.fg && bg == 0 && !c.bold && !c.underline) return null
        return SpanStyle(
            color = Color(fg),
            background = if (bg != 0) Color(bg) else Color.Unspecified,
            fontWeight = if (c.bold) FontWeight.Bold else null,
            textDecoration = if (c.underline) TextDecoration.Underline else null
        )
    }

    private fun sameStyle(a: Cell, b: Cell): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.bold == b.bold && a.underline == b.underline && a.inverse == b.inverse

    private fun newGrid(r: Int, c: Int): Array<Array<Cell>> = Array(r) { Array(c) { BLANK } }

    private fun blankRow(): Array<Cell> = Array(cols) { erased() }

    /** Erase ke waqt cell ka rang: current background (BCE), foreground/style nahi. */
    private fun erased(): Cell = if (style.bg == 0) BLANK else Cell(' ', 0, style.bg, false, false, false)

    private fun palette256(n: Int): Int = when (n) {
        in 0..15 -> ANSI16[n]
        in 16..231 -> {
            val k = n - 16
            rgb(level(k / 36), level((k / 6) % 6), level(k % 6))
        }
        in 232..255 -> {
            val g = 8 + (n - 232) * 10
            rgb(g, g, g)
        }
        else -> TermColors.fg
    }

    private fun level(x: Int): Int = if (x == 0) 0 else 55 + 40 * x

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    companion object {
        private const val OSC_MAX = 12_000_000

        private val ANSI16 = intArrayOf(
            0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF565F89.toInt(), 0xFFFF899D.toInt(), 0xFF9FE044.toInt(), 0xFFFABA4A.toInt(),
            0xFF8DB0FF.toInt(), 0xFFC7A9FF.toInt(), 0xFFA4DAFF.toInt(), 0xFFC0CAF5.toInt()
        )
    }
}

/** Terminal ke default rang. Theme badalne par TerminalScreen inhe update karta hai. */
object TermColors {
    @Volatile var fg: Int = 0xFFE6E8EE.toInt()
    @Volatile var bg: Int = 0xFF000000.toInt()
}
