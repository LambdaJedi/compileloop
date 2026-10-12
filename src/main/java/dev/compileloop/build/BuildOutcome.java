package dev.compileloop.build;

public enum BuildOutcome {
    PASS,
    FAIL,
    TIMEOUT,
    START_ERROR,
    CANCELLED,
    INFRA_ERROR
}
