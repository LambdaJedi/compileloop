package dev.compileloop.cli;

import java.nio.file.Path;

/** What `compileloop repair` was asked to do, after argument parsing. */
public record RepairOptions(
        String provider,
        String model,
        String ollamaUrl,
        String openAiBaseUrl,
        String apiKey,
        String apiKeyEnv,
        Path responseFile,
        int attempts,
        int buildTimeoutSeconds,
        int aiTimeoutSeconds,
        boolean allowTestChanges
) {
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    public static final String DEFAULT_PROVIDER = PROVIDER_OLLAMA;
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final int DEFAULT_ATTEMPTS = 3;
    public static final int DEFAULT_BUILD_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_AI_TIMEOUT_SECONDS = 300;

    public boolean usesOllama() {
        return PROVIDER_OLLAMA.equals(provider);
    }

    public boolean usesOpenAi() {
        return PROVIDER_OPENAI.equals(provider);
    }
}