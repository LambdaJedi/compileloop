package dev.compileloop.cli;

import java.nio.file.Path;

/** What `compileloop repair` was asked to do, after argument parsing. */
public record RepairOptions(
        String model,
        String ollamaUrl,
        Path responseFile,
        int attempts,
        int buildTimeoutSeconds,
        int aiTimeoutSeconds,
        boolean allowTestChanges
) {
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final int DEFAULT_ATTEMPTS = 3;
    public static final int DEFAULT_BUILD_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_AI_TIMEOUT_SECONDS = 300;
}
