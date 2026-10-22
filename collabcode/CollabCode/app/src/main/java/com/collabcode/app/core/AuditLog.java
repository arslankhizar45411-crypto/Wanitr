package com.collabcode.app.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Keeps the last 500 lines in memory and appends every line to a file. All text is redacted. */
public final class AuditLog {
    public static final int MEMORY_LINES = 500;

    private final Deque<String> lines = new ArrayDeque<>();
    private final File file;

    public AuditLog(File file) {
        this.file = file;
    }

    public synchronized void record(String tool, String status, String detail) {
        String line = Instant.now() + " | " + Redactor.redact(tool) + " | "
                + Redactor.redact(status) + " | " + Redactor.redact(detail);
        lines.addLast(line);
        while (lines.size() > MEMORY_LINES) {
            lines.removeFirst();
        }
        if (file != null) {
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.println(line);
            } catch (IOException e) {
                // The in-memory copy is still kept. Disk problems must not stop the agent.
            }
        }
    }

    public synchronized List<String> recent() {
        return new ArrayList<>(lines);
    }
}
