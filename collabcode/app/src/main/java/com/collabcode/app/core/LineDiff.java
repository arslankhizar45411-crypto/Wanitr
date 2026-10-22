package com.collabcode.app.core;

import java.util.ArrayList;
import java.util.List;

/** Line diff by longest common subsequence. Very large inputs fall back to full replacement. */
public final class LineDiff {
    public static final long MAX_CELLS = 4_000_000L;

    public static final class Line {
        /** '+' added, '-' removed, ' ' unchanged. */
        public final char kind;
        public final String text;

        Line(char kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    public static final class Result {
        public final List<Line> lines;
        public final int added;
        public final int removed;

        Result(List<Line> lines, int added, int removed) {
            this.lines = lines;
            this.added = added;
            this.removed = removed;
        }
    }

    private LineDiff() {}

    public static Result diff(String oldText, String newText) {
        String[] a = split(oldText);
        String[] b = split(newText);
        List<Line> out = new ArrayList<>();

        if ((long) a.length * (long) b.length > MAX_CELLS) {
            for (String s : a) out.add(new Line('-', s));
            for (String s : b) out.add(new Line('+', s));
            return new Result(out, b.length, a.length);
        }

        int n = a.length;
        int m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int x = n - 1; x >= 0; x--) {
            for (int y = m - 1; y >= 0; y--) {
                lcs[x][y] = a[x].equals(b[y])
                        ? lcs[x + 1][y + 1] + 1
                        : Math.max(lcs[x + 1][y], lcs[x][y + 1]);
            }
        }

        int i = 0;
        int j = 0;
        int added = 0;
        int removed = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new Line(' ', a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line('-', a[i]));
                i++;
                removed++;
            } else {
                out.add(new Line('+', b[j]));
                j++;
                added++;
            }
        }
        while (i < n) {
            out.add(new Line('-', a[i]));
            i++;
            removed++;
        }
        while (j < m) {
            out.add(new Line('+', b[j]));
            j++;
            added++;
        }
        return new Result(out, added, removed);
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\n", -1);
    }
}
