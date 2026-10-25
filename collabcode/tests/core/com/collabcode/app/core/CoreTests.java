package com.collabcode.app.core;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class CoreTests {
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    public static void main(String[] args) throws Exception {
        // Redactor
        check("github token removed",
                !Redactor.redact("x ghp_abcdefghijklmnopqrstuvwxyz0123456789 y").contains("ghp_abcdef"));
        check("github_pat removed",
                !Redactor.redact("github_pat_11ABCDEFGHIJKLMNOPQRSTU").contains("11ABCDEF"));
        check("bearer removed",
                !Redactor.redact("Authorization: Bearer abcdefgh12345678").contains("abcdefgh12345678"));
        check("sk key removed",
                !Redactor.redact("key sk-abcdefghijklmnop1234").contains("sk-abcdefghijklmnop1234"));
        check("key=value cut at ampersand",
                Redactor.redact("token=abc123xyz&x=1").equals("token=[redacted]&x=1"));
        check("json token value removed",
                !Redactor.redact("{\"token\": \"secretvalue\"}").contains("secretvalue"));
        check("password removed",
                !Redactor.redact("password: hunter2").contains("hunter2"));
        check("plain text kept", Redactor.redact("hello world").equals("hello world"));
        check("null is safe", Redactor.redact(null).isEmpty());

        // ToolPolicy
        check("READ is auto-approved", ToolPolicy.canAutoApprove(RiskClass.READ));
        check("CHANGE, EXEC, BUILD, COMMIT need approval",
                ToolPolicy.requiresApproval(RiskClass.CHANGE)
                        && ToolPolicy.requiresApproval(RiskClass.EXEC)
                        && ToolPolicy.requiresApproval(RiskClass.BUILD)
                        && ToolPolicy.requiresApproval(RiskClass.COMMIT));

        // EmergencyStop
        EmergencyStop stop = new EmergencyStop();
        stop.checkpoint();
        stop.trigger();
        boolean threw = false;
        try {
            stop.checkpoint();
        } catch (EmergencyStop.StoppedException e) {
            threw = true;
        }
        check("checkpoint throws after trigger", threw);
        stop.reset();
        check("reset clears the stop", !stop.isStopped());

        // AuditLog
        File tmp = File.createTempFile("audit", ".log");
        tmp.deleteOnExit();
        AuditLog log = new AuditLog(tmp);
        for (int i = 0; i < 600; i++) {
            log.record("tool" + i, "ok", "detail token=abc123xyz");
        }
        check("memory keeps 500 lines", log.recent().size() == AuditLog.MEMORY_LINES);
        check("memory line is redacted",
                log.recent().get(0).contains("[redacted]") && !log.recent().get(0).contains("abc123xyz"));
        String onDisk = new String(Files.readAllBytes(tmp.toPath()), "UTF-8");
        check("file has all 600 lines", onDisk.split("\n").length == 600);
        check("file has no secret", !onDisk.contains("abc123xyz"));

        // LineDiff
        LineDiff.Result same = LineDiff.diff("a\nb", "a\nb");
        check("identical text has no changes", same.added == 0 && same.removed == 0);
        LineDiff.Result one = LineDiff.diff("a\nb\nc", "a\nB\nc");
        check("one line replaced", one.added == 1 && one.removed == 1);
        LineDiff.Result grow = LineDiff.diff("", "x\ny");
        check("empty to two lines", grow.added == 2 && grow.removed == 0);
        StringBuilder big1 = new StringBuilder();
        StringBuilder big2 = new StringBuilder();
        for (int i = 0; i < 2001; i++) {
            if (i > 0) {
                big1.append('\n');
                big2.append('\n');
            }
            big1.append("x").append(i);
            big2.append("y").append(i);
        }
        LineDiff.Result fallback = LineDiff.diff(big1.toString(), big2.toString());
        check("large input falls back to full replacement",
                fallback.added == 2001 && fallback.removed == 2001);

        ansiTests();

        System.out.println("passed: " + passed + ", failed: " + failures.size());
        for (String f : failures) {
            System.out.println("FAILED: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static String t(AnsiScreen s, int i) {
        return s.line(i).text;
    }

    private static void ansiTests() {
        AnsiScreen s = new AnsiScreen(80, 24, 1000);
        s.feed("hello\r\nworld");
        check("ansi plain text lines", t(s, 0).equals("hello") && t(s, 1).equals("world") && s.lineCount() == 2);

        s = new AnsiScreen(80, 24, 1000);
        s.feed("abc\rX");
        check("ansi carriage return overwrites", t(s, 0).equals("Xbc"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("ab\bX");
        check("ansi backspace", t(s, 0).equals("aX"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("\u001b[31mred\u001b[0m ok");
        AnsiScreen.LineView v = s.line(0);
        check("ansi red colour then reset",
                v.text.equals("red ok") && v.fg[0] == 1 && v.fg[2] == 1 && v.fg[3] == -1 && v.fg[5] == -1);

        s = new AnsiScreen(80, 24, 1000);
        s.feed("\u001b[1;38;5;196mA\u001b[38;2;10;20;30mB");
        v = s.line(0);
        check("ansi bold and 256 colour", (v.attr[0] & AnsiScreen.ATTR_BOLD) != 0 && v.fg[0] == 196);
        check("ansi true colour", v.fg[1] == (AnsiScreen.RGB_FLAG | (10 << 16) | (20 << 8) | 30));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("hello\u001b[3D\u001b[K");
        check("ansi erase to end of line", t(s, 0).equals("he"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("a\r\nb\r\n\u001b[H\u001b[2Jc");
        check("ansi clear keeps scrollback and starts a new screen",
                t(s, 0).equals("a") && t(s, s.cursorLine()).equals("c"));

        s = new AnsiScreen(20, 5, 10);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("line").append(i).append("\r\n");
        s.feed(many.toString());
        check("ansi scrollback is trimmed", s.lineCount() <= 10 && t(s, s.lineCount() - 2).equals("line49"));

        s = new AnsiScreen(10, 3, 100);
        s.feed("abcdefghijkl");
        check("ansi wraps long lines", t(s, 0).equals("abcdefghij") && t(s, 1).equals("kl"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("\u001b]0;title\u0007hi\u001b]8;;x\u001b\\yo");
        check("ansi titles and links are ignored", t(s, 0).equals("hiyo"));

        s = new AnsiScreen(80, 24, 1000);
        final String[] reply = new String[1];
        s.setResponder(r -> reply[0] = r);
        s.feed("ab\u001b[6n");
        check("ansi cursor position report", "\u001b[1;3R".equals(reply[0]));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("\u001b[?2004hX\u001b[?25l");
        check("ansi private modes are ignored", t(s, 0).equals("X"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("abcdef\u001b[3D\u001b[2P");
        check("ansi delete characters", t(s, 0).equals("abcf"));

        s = new AnsiScreen(80, 24, 1000);
        s.setNewlineMode(true);
        s.feed("a\nb");
        check("ansi newline mode returns to column 0", t(s, 0).equals("a") && t(s, 1).equals("b"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("\u001b[3");
        s.feed("1mX");
        check("ansi sequence split across feeds", t(s, 0).equals("X") && s.line(0).fg[0] == 1);

        s = new AnsiScreen(80, 24, 1000);
        s.feed("a\tb");
        check("ansi tab goes to column 8", t(s, 0).length() == 9 && t(s, 0).charAt(8) == 'b');

        s = new AnsiScreen(80, 24, 1000);
        s.feed("one\r\ntwo\r\n");
        check("ansi tail returns text", s.tail(100).startsWith("one\ntwo"));

        s = new AnsiScreen(80, 24, 1000);
        s.feed("abc\u001b[2@");
        s.feed("\u001b[3D\u001b[2@Z");
        check("ansi insert characters", t(s, 0).startsWith("Z"));
    }
}
