package com.collabcode.app.core;

/** Single place that decides auto-approval. Only READ may be auto-approved. */
public final class ToolPolicy {
    private ToolPolicy() {}

    public static boolean canAutoApprove(RiskClass risk) {
        return risk == RiskClass.READ;
    }

    public static boolean requiresApproval(RiskClass risk) {
        return !canAutoApprove(risk);
    }
}
