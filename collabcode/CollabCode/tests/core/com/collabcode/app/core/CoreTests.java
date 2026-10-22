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

        System.out.println("passed: " + passed + ", failed: " + failures.size());
        for (String f : failures) {
            System.out.println("FAILED: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
