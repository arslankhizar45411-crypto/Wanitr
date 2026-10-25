package com.collabcode.app.core;

import java.util.regex.Pattern;

/** Removes tokens, keys and secret key=value pairs before text is logged or sent to the model. */
public final class Redactor {
    private static final String MASK = "[redacted]";

    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{16,}");
    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SK_KEY =
            Pattern.compile("sk-[A-Za-z0-9_-]{16,}");
    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)\\b(access_token|refresh_token|api_key|apikey|token|password|authorization)(\"?\\s*[:=]\\s*)(\"?)[^\\s\"&,;]+");

    private Redactor() {}

    public static String redact(String text) {
        if (text == null) return "";
        String out = GITHUB_TOKEN.matcher(text).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll("$1" + MASK);
        out = SK_KEY.matcher(out).replaceAll(MASK);
        out = KEY_VALUE.matcher(out).replaceAll("$1$2$3" + MASK);
        return out;
    }
}
