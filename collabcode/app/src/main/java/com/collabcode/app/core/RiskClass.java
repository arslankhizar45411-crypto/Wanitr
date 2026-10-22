package com.collabcode.app.core;

/** Every tool belongs to exactly one class. The class decides whether approval is needed. */
public enum RiskClass {
    READ,
    CHANGE,
    EXEC,
    BUILD,
    COMMIT
}
