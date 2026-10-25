package com.collabcode.app.core;

import java.util.ArrayList;

/**
 * A small terminal screen. It accepts text with ANSI escape sequences and keeps the
 * lines and a scrollback. It has no Android classes, so it can be tested on any JVM.
 *
 * Supported: printable text, CR LF BS TAB, colours (16, 256, true colour), bold,
 * underline, inverse, cursor movement, erase line and screen, insert and delete of
 * characters and lines, and the cursor position report (ESC [ 6 n).
 * Ignored on purpose: private modes (for example ESC [ ? 25 h), titles and the
 * alternate screen. Full-screen programs such as nano are therefore not supported.
 */
public final class AnsiScreen {
    public static final int ATTR_BOLD = 1;
    public static final int ATTR_INVERSE = 2;
    public static final int ATTR_UNDERLINE = 4;
    public static final int DEFAULT_COLOR = -1;
    /** Colours with this bit set are 0xRRGGBB values. */
    public static final int RGB_FLAG = 0x1000000;

    public interface Responder {
        void respond(String reply);
    }

    /** An immutable copy of one line, made for drawing. */
    public static final class LineView {
        public final String text;
        public final int[] fg;
        public final int[] bg;
        public final int[] attr;

        LineView(String text, int[] fg, int[] bg, int[] attr) {
            this.text = text;
            this.fg = fg;
            this.bg = bg;
            this.attr = attr;
        }
    }

    private static final class Line {
        char[] ch = new char[0];
        int[] fg = new int[0];
        int[] bg = new int[0];
        int[] attr = new int[0];
        int len = 0;

        void ensure(int n) {
            if (n <= ch.length) return;
            int cap = Math.max(n, Math.max(16, ch.length * 2));
            int old = ch.length;
            ch = java.util.Arrays.copyOf(ch, cap);
            fg = java.util.Arrays.copyOf(fg, cap);
            bg = java.util.Arrays.copyOf(bg, cap);
            attr = java.util.Arrays.copyOf(attr, cap);
            for (int i = old; i < cap; i++) blank(i);
        }

        void blank(int i) {
            ch[i] = ' ';
            fg[i] = DEFAULT_COLOR;
            bg[i] = DEFAULT_COLOR;
            attr[i] = 0;
        }

        void set(int c, char v, int f, int b, int a) {
            ensure(c + 1);
            for (int i = len; i < c; i++) blank(i);
            ch[c] = v;
            fg[c] = f;
            bg[c] = b;
            attr[c] = a;
            if (c + 1 > len) len = c + 1;
        }

        void clearFrom(int c) {
            for (int i = c; i < len; i++) blank(i);
            if (c < len) len = c;
        }

        void clearTo(int c) {
            ensure(c + 1);
            for (int i = 0; i <= c; i++) blank(i);
            if (c + 1 > len) len = c + 1;
        }

        void deleteChars(int c, int n) {
            if (c >= len) return;
            int move = len - c - n;
            if (move > 0) {
                System.arraycopy(ch, c + n, ch, c, move);
                System.arraycopy(fg, c + n, fg, c, move);
                System.arraycopy(bg, c + n, bg, c, move);
                System.arraycopy(attr, c + n, attr, c, move);
                int newLen = len - n;
                for (int i = newLen; i < len; i++) blank(i);
                len = newLen;
            } else {
                clearFrom(c);
            }
        }

        void insertBlanks(int c, int n) {
            if (c >= len) return;
            ensure(len + n);
            int move = len - c;
            System.arraycopy(ch, c, ch, c + n, move);
            System.arraycopy(fg, c, fg, c + n, move);
            System.arraycopy(bg, c, bg, c + n, move);
            System.arraycopy(attr, c, attr, c + n, move);
            for (int i = c; i < c + n; i++) blank(i);
            len += n;
        }

        void eraseChars(int c, int n) {
            int end = Math.min(c + n, len);
            for (int i = c; i < end; i++) blank(i);
        }
    }

    private static final int GROUND = 0;
    private static final int ESC = 1;
    private static final int CSI = 2;
    private static final int OSC = 3;
    private static final int SKIP1 = 4;

    private final ArrayList<Line> lines = new ArrayList<>();
    private final StringBuilder csi = new StringBuilder();
    private final int maxLines;

    private int cols;
    private int rows;
    private int screenTop = 0;
    private int row = 0;
    private int col = 0;
    private boolean wrapPending = false;
    private boolean lfImpliesCr = false;
    private int savedRow = 0;
    private int savedCol = 0;
    private int state = GROUND;
    private boolean oscEsc = false;
    private int curFg = DEFAULT_COLOR;
    private int curBg = DEFAULT_COLOR;
    private int curAttr = 0;
    private long version = 0;
    private Responder responder;

    public AnsiScreen(int cols, int rows, int maxLines) {
        this.cols = Math.max(10, cols);
        this.rows = Math.max(2, rows);
        this.maxLines = Math.max(this.rows + 1, maxLines);
    }

    public synchronized void setResponder(Responder r) {
        this.responder = r;
    }

    /** When true, a line feed also moves to column 0. Use it when no PTY adds the carriage return. */
    public synchronized void setNewlineMode(boolean crlf) {
        this.lfImpliesCr = crlf;
    }

    public synchronized void resize(int newCols, int newRows) {
        cols = Math.max(10, newCols);
        rows = Math.max(2, newRows);
        if (row > rows - 1) row = rows - 1;
        if (col > cols - 1) col = cols - 1;
    }

    public synchronized int getCols() {
        return cols;
    }

    public synchronized int getRows() {
        return rows;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int lineCount() {
        return lines.size();
    }

    public synchronized int cursorLine() {
        return screenTop + row;
    }

    public synchronized int cursorColumn() {
        return col;
    }

    public synchronized void clear() {
        lines.clear();
        screenTop = 0;
        row = 0;
        col = 0;
        wrapPending = false;
        state = GROUND;
        resetPen();
        version++;
    }

    public synchronized LineView line(int index) {
        if (index < 0 || index >= lines.size()) {
            return new LineView("", new int[0], new int[0], new int[0]);
        }
        Line l = lines.get(index);
        return new LineView(
                new String(l.ch, 0, l.len),
                java.util.Arrays.copyOf(l.fg, l.len),
                java.util.Arrays.copyOf(l.bg, l.len),
                java.util.Arrays.copyOf(l.attr, l.len));
    }

    /** Plain text of the last lines, at most maxChars long. Used for logs. */
    public synchronized String tail(int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0 && sb.length() < maxChars; i--) {
            Line l = lines.get(i);
            String t = new String(l.ch, 0, l.len);
            int end = t.length();
            while (end > 0 && t.charAt(end - 1) == ' ') end--;
            sb.insert(0, t.substring(0, end) + "\n");
        }
        String out = sb.toString();
        return out.length() > maxChars ? out.substring(out.length() - maxChars) : out;
    }

    public synchronized void feed(String text) {
        for (int i = 0; i < text.length(); i++) {
            process(text.charAt(i));
        }
        version++;
    }

    // ---------- parser ----------

    private void process(char c) {
        switch (state) {
            case GROUND:
                ground(c);
                break;
            case ESC:
                escape(c);
                break;
            case CSI:
                csiChar(c);
                break;
            case OSC:
                oscChar(c);
                break;
            default:
                state = GROUND;
                break;
        }
    }

    private void ground(char c) {
        if (c == 0x1B) {
            state = ESC;
        } else if (c == '\n' || c == 0x0B || c == 0x0C) {
            lineFeed();
        } else if (c == '\r') {
            col = 0;
            wrapPending = false;
        } else if (c == '\b') {
            if (col > 0) col--;
            wrapPending = false;
        } else if (c == '\t') {
            col = Math.min(cols - 1, (col / 8 + 1) * 8);
            wrapPending = false;
        } else if (c >= 0x20 && c != 0x7F) {
            put(c);
        }
    }

    private void escape(char c) {
        state = GROUND;
        switch (c) {
            case '[':
                state = CSI;
                csi.setLength(0);
                break;
            case ']':
                state = OSC;
                oscEsc = false;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
            case '#':
                state = SKIP1;
                break;
            case '7':
                savedRow = row;
                savedCol = col;
                break;
            case '8':
                row = Math.min(savedRow, rows - 1);
                col = Math.min(savedCol, cols - 1);
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'M':
                if (row > 0) row--;
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                col = 0;
                lineFeed();
                break;
            case 'c':
                resetPen();
                eraseDisplay(3);
                row = 0;
                col = 0;
                break;
            default:
                break;
        }
    }

    private void csiChar(char c) {
        if (c >= 0x40 && c <= 0x7E) {
            state = GROUND;
            execCsi(c, csi.toString());
        } else if (c == 0x1B) {
            state = ESC;
        } else if (csi.length() < 64) {
            csi.append(c);
        } else {
            state = GROUND;
        }
    }

    private void oscChar(char c) {
        if (c == 0x07) {
            state = GROUND;
        } else if (oscEsc) {
            // ESC followed by backslash ends the string. Any other character cancels it.
            state = GROUND;
            if (c != '\\') ground(c);
        } else if (c == 0x1B) {
            oscEsc = true;
        }
    }

    private static int[] parseParams(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.replace(':', ';').split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t.isEmpty() || t.length() > 6) {
                out[i] = -1;
            } else {
                try {
                    out[i] = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    out[i] = -1;
                }
            }
        }
        return out;
    }

    private static int def(int[] p, int i, int d) {
        return (i < p.length && p[i] >= 0) ? p[i] : d;
    }

    private void execCsi(char fin, String params) {
        // Private modes, intermediates and anything unusual are ignored.
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (!((ch >= '0' && ch <= '9') || ch == ';' || ch == ':')) return;
        }
        int[] p = parseParams(params);
        int n = Math.max(1, def(p, 0, 1));
        switch (fin) {
            case 'm':
                sgr(p);
                break;
            case 'A':
                row = Math.max(0, row - n);
                wrapPending = false;
                break;
            case 'B':
                row = Math.min(rows - 1, row + n);
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'C':
                col = Math.min(cols - 1, col + n);
                wrapPending = false;
                break;
            case 'D':
                col = Math.max(0, col - n);
                wrapPending = false;
                break;
            case 'E':
                row = Math.min(rows - 1, row + n);
                col = 0;
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'F':
                row = Math.max(0, row - n);
                col = 0;
                wrapPending = false;
                break;
            case 'G':
            case '`':
                col = clamp(def(p, 0, 1) - 1, 0, cols - 1);
                wrapPending = false;
                break;
            case 'H':
            case 'f':
                row = clamp(def(p, 0, 1) - 1, 0, rows - 1);
                col = clamp(def(p, 1, 1) - 1, 0, cols - 1);
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'd':
                row = clamp(def(p, 0, 1) - 1, 0, rows - 1);
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'J':
                eraseDisplay(def(p, 0, 0));
                break;
            case 'K':
                eraseLine(def(p, 0, 0));
                break;
            case 'P':
                lineAt(screenTop + row).deleteChars(col, n);
                break;
            case '@':
                lineAt(screenTop + row).insertBlanks(col, n);
                break;
            case 'X':
                lineAt(screenTop + row).eraseChars(col, n);
                break;
            case 'L':
                insertLines(n);
                break;
            case 'M':
                deleteLines(n);
                break;
            case 's':
                savedRow = row;
                savedCol = col;
                break;
            case 'u':
                row = Math.min(savedRow, rows - 1);
                col = Math.min(savedCol, cols - 1);
                wrapPending = false;
                lineAt(screenTop + row);
                break;
            case 'n':
                if (def(p, 0, 0) == 6 && responder != null) {
                    responder.respond("\u001b[" + (row + 1) + ";" + (col + 1) + "R");
                }
                break;
            default:
                break;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void sgr(int[] p) {
        if (p.length == 0) {
            resetPen();
            return;
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i] < 0 ? 0 : p[i];
            if (v == 0) {
                resetPen();
            } else if (v == 1) {
                curAttr |= ATTR_BOLD;
            } else if (v == 4) {
                curAttr |= ATTR_UNDERLINE;
            } else if (v == 7) {
                curAttr |= ATTR_INVERSE;
            } else if (v == 22) {
                curAttr &= ~ATTR_BOLD;
            } else if (v == 24) {
                curAttr &= ~ATTR_UNDERLINE;
            } else if (v == 27) {
                curAttr &= ~ATTR_INVERSE;
            } else if (v >= 30 && v <= 37) {
                curFg = v - 30;
            } else if (v == 39) {
                curFg = DEFAULT_COLOR;
            } else if (v >= 40 && v <= 47) {
                curBg = v - 40;
            } else if (v == 49) {
                curBg = DEFAULT_COLOR;
            } else if (v >= 90 && v <= 97) {
                curFg = v - 90 + 8;
            } else if (v >= 100 && v <= 107) {
                curBg = v - 100 + 8;
            } else if (v == 38 || v == 48) {
                int color = DEFAULT_COLOR;
                if (i + 2 < p.length && p[i + 1] == 5) {
                    color = clamp(p[i + 2], 0, 255);
                    i += 2;
                } else if (i + 4 < p.length && p[i + 1] == 2) {
                    int r = clamp(p[i + 2], 0, 255);
                    int g = clamp(p[i + 3], 0, 255);
                    int b = clamp(p[i + 4], 0, 255);
                    color = RGB_FLAG | (r << 16) | (g << 8) | b;
                    i += 4;
                } else {
                    break;
                }
                if (v == 38) curFg = color;
                else curBg = color;
            }
        }
    }

    private void resetPen() {
        curFg = DEFAULT_COLOR;
        curBg = DEFAULT_COLOR;
        curAttr = 0;
    }

    // ---------- screen operations ----------

    private Line lineAt(int abs) {
        while (lines.size() <= abs) lines.add(new Line());
        return lines.get(abs);
    }

    private void put(char c) {
        if (wrapPending) {
            col = 0;
            lineFeed();
            wrapPending = false;
        }
        lineAt(screenTop + row).set(col, c, curFg, curBg, curAttr);
        if (col >= cols - 1) {
            wrapPending = true;
        } else {
            col++;
        }
    }

    private void lineFeed() {
        wrapPending = false;
        if (lfImpliesCr) col = 0;
        if (row < rows - 1) {
            row++;
        } else {
            screenTop++;
        }
        lineAt(screenTop + row);
        int extra = lines.size() - maxLines;
        if (extra > 0) {
            lines.subList(0, extra).clear();
            screenTop = Math.max(0, screenTop - extra);
        }
    }

    private void eraseLine(int mode) {
        Line l = lineAt(screenTop + row);
        if (mode == 0) {
            l.clearFrom(col);
        } else if (mode == 1) {
            l.clearTo(col);
        } else {
            l.clearFrom(0);
        }
    }

    private void eraseDisplay(int mode) {
        int abs = screenTop + row;
        if (mode == 0) {
            lineAt(abs).clearFrom(col);
            for (int i = abs + 1; i < Math.min(lines.size(), screenTop + rows); i++) {
                lines.get(i).clearFrom(0);
            }
        } else if (mode == 1) {
            for (int i = screenTop; i < abs && i < lines.size(); i++) {
                lines.get(i).clearFrom(0);
            }
            lineAt(abs).clearTo(col);
        } else if (mode == 2) {
            // Keep what was on the screen as scrollback, then start on fresh lines.
            screenTop = lines.size();
            lineAt(screenTop + row);
        } else {
            lines.clear();
            screenTop = 0;
            lineAt(row);
        }
    }

    private void insertLines(int n) {
        int abs = screenTop + row;
        lineAt(abs);
        for (int i = 0; i < n; i++) lines.add(abs, new Line());
        while (lines.size() > screenTop + rows) lines.remove(lines.size() - 1);
    }

    private void deleteLines(int n) {
        int abs = screenTop + row;
        lineAt(abs);
        for (int i = 0; i < n && abs < lines.size(); i++) lines.remove(abs);
        lineAt(abs);
    }
}
