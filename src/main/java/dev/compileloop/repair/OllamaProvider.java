package dev.compileloop.repair;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Provider backed by a local Ollama server (POST {base}/api/chat, non-streaming,
 * JSON mode, temperature 0). It only sends text and reads text back; the reply is
 * handed to the strict ProposalParser like any other model output, so nothing the
 * model says can act on its own.
 *
 * Redirects are never followed. The base URL is operator configuration, not
 * something the model controls.
 */
public final class OllamaProvider implements AIProvider {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final int maxContextChars;
    private final HttpClient client;

    public OllamaProvider(String baseUrl, String model, Duration timeout, int maxContextChars) {
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("Ollama base URL must start with http:// or https://");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("a model name is required");
        }
        if (timeout.isZero() || timeout.isNegative() || maxContextChars < 1) {
            throw new IllegalArgumentException("timeout and context limit must be positive");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base + "/api/chat";
        this.model = model;
        this.timeout = timeout;
        this.maxContextChars = maxContextChars;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public PatchProposal proposeRepair(RepairContext context) throws AIProviderException {
        String prompt = context.render();
        if (prompt.length() > maxContextChars) {
            throw new AIProviderException(AIProviderException.Kind.CONTEXT_OVERFLOW,
                    "the prepared context is " + prompt.length() + " characters; the configured limit is "
                            + maxContextChars);
        }

        String body = "{\"model\":" + Json.quote(model)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + Json.quote(prompt) + "}]"
                + ",\"stream\":false,\"format\":\"json\""
                + ",\"options\":{\"temperature\":0,\"seed\":0}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long maxBytes = 4L * context.policy().maxResponseChars() + 65_536;
        int status;
        String responseText;
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                if (bytes.length > maxBytes) {
                    throw new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                            "the Ollama response is larger than " + maxBytes + " bytes");
                }
                responseText = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (HttpConnectTimeoutException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "could not connect to Ollama at " + endpoint + " within " + timeout.toSeconds() + "s", e);
        } catch (HttpTimeoutException e) {
            throw new AIProviderException(AIProviderException.Kind.TIMEOUT,
                    "Ollama did not answer within " + timeout.toSeconds() + "s", e);
        } catch (ConnectException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "could not connect to Ollama at " + endpoint + " (is it running?)", e);
        } catch (IOException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "communication with Ollama failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "interrupted while waiting for Ollama", e);
        }

        if (status != 200) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "Ollama answered HTTP " + status + errorDetail(responseText));
        }

        Object root;
        try {
            root = Json.parse(responseText, 8);
        } catch (Json.ParseException e) {
            throw new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                    "the Ollama response was not valid JSON: " + e.getMessage(), e);
        }
        if (root instanceof Map<?, ?> map && map.get("message") instanceof Map<?, ?> msg
                && msg.get("content") instanceof String content) {
            return ProposalParser.parse(content, context.policy());
        }
        throw new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                "the Ollama response did not contain message.content");
    }

    private static String errorDetail(String body) {
        try {
            if (Json.parse(body, 8) instanceof Map<?, ?> m && m.get("error") instanceof String e) {
                return ": " + (e.length() > 300 ? e.substring(0, 300) : e);
            }
        } catch (Json.ParseException ignored) {
            // not JSON: no detail
        }
        return "";
    }
}
