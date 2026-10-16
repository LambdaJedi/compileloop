package dev.compileloop.cli;

import dev.compileloop.build.BuildRunner;
import dev.compileloop.repair.AIProvider;
import dev.compileloop.repair.MockProvider;
import dev.compileloop.repair.OllamaProvider;
import dev.compileloop.repair.RepairEngine;
import dev.compileloop.repair.RepairResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class Main {

    /** Bounded context sent to a local model. Kept small so small models can hold it. */
    private static final int OLLAMA_CONTEXT_CHARS = 60_000;

    /** Same bound for an OpenAI-compatible endpoint. Hosted models accept more,
     *  but a bounded prompt keeps cost and latency predictable. */
    private static final int OPENAI_CONTEXT_CHARS = 60_000;

    private Main() {
    }

    public static void main(String[] args) {
        BuildRunner runner = new BuildRunner();
        Cli cli = new Cli(runner::runMavenTests, Main::repair, System.out, System.err);
        System.exit(cli.run(args));
    }

    static RepairResult repair(Path project, RepairOptions o) throws IOException {
        AIProvider provider;
        if (o.responseFile() != null) {
            // The same canned answer for every attempt, so --attempts behaves predictably.
            String raw = Files.readString(o.responseFile(), StandardCharsets.UTF_8);
            String[] copies = new String[o.attempts()];
            java.util.Arrays.fill(copies, raw);
            provider = MockProvider.returning(copies);
        } else if (o.usesOpenAi()) {
            String key = resolveApiKey(o);
            try {
                provider = AIProvider.openAiCompatible(o.openAiBaseUrl(), key, o.model(),
                        Duration.ofSeconds(o.aiTimeoutSeconds()), OPENAI_CONTEXT_CHARS);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        } else {
            try {
                provider = new OllamaProvider(o.ollamaUrl(), o.model(),
                        Duration.ofSeconds(o.aiTimeoutSeconds()), OLLAMA_CONTEXT_CHARS);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        RepairEngine.Config config = RepairEngine.Config.defaults()
                .withMaxAttempts(o.attempts())
                .withBuildTimeout(Duration.ofSeconds(o.buildTimeoutSeconds()));
        if (o.allowTestChanges()) {
            config = config.allowingTestChanges();
        }
        BuildRunner runner = new BuildRunner();
        return new RepairEngine(runner::runMavenTests, provider, config).repair(project);
    }

    /** Resolve the API key from --api-key or --api-key-env, or fail loudly. */
    private static String resolveApiKey(RepairOptions o) throws IOException {
        if (o.apiKey() != null) {
            return o.apiKey();
        }
        String fromEnv = System.getenv(o.apiKeyEnv());
        if (fromEnv == null || fromEnv.isBlank()) {
            throw new IOException("environment variable " + o.apiKeyEnv()
                    + " is not set or is empty");
        }
        return fromEnv;
    }
}
