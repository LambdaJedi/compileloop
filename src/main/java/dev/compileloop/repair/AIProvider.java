package dev.compileloop.repair;

import java.time.Duration;

/**
 * Source of repair proposals. Deliberately tiny: it receives a prepared,
 * read-only context and returns a proposal. It gets no access to the project
 * filesystem, a shell, or anything else it could use to act on its own.
 * Whatever it returns is untrusted data until the PatchValidator accepts it.
 */
public interface AIProvider {

    PatchProposal proposeRepair(RepairContext context) throws AIProviderException;

    /**
     * A provider backed by a local Ollama server. Redirects are never
     * followed; the base URL is operator configuration, not something the
     * model controls.
     *
     * <p>Defaults: five-minute request timeout, 200,000-character context
     * limit. For a different budget, use
     * {@link #ollama(String, String, Duration, int)}.
     */
    static AIProvider ollama(String baseUrl, String model) {
        return new OllamaProvider(baseUrl, model, Duration.ofMinutes(5), 200_000);
    }

    /** As {@link #ollama(String, String)}, with an explicit timeout and context limit. */
    static AIProvider ollama(String baseUrl, String model, Duration timeout, int maxContextChars) {
        return new OllamaProvider(baseUrl, model, timeout, maxContextChars);
    }

    /**
     * A provider for any OpenAI-compatible chat completions endpoint — including
     * aggregators such as FreeLLMAPI. The base URL is the API root that ends in
     * {@code /v1} (or any equivalent); {@code /chat/completions} is appended.
     * Pass an empty string for {@code apiKey} when the endpoint does not
     * require one.
     *
     * <p>Defaults: five-minute request timeout, 200,000-character context
     * limit, JSON mode on. For a different budget or to disable JSON mode,
     * use the five-argument overload or construct
     * {@link OpenAICompatibleProvider} directly.
     */
    static AIProvider openAiCompatible(String baseUrl, String apiKey, String model) {
        return new OpenAICompatibleProvider(baseUrl, apiKey, model, Duration.ofMinutes(5), 200_000);
    }

    /** As {@link #openAiCompatible(String, String, String)}, with explicit timeout and context limit. */
    static AIProvider openAiCompatible(String baseUrl, String apiKey, String model,
                                       Duration timeout, int maxContextChars) {
        return new OpenAICompatibleProvider(baseUrl, apiKey, model, timeout, maxContextChars);
    }
}