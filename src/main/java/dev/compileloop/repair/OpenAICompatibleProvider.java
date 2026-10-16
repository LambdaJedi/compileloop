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
import java.util.List;
import java.util.Map;

/**
 * Provider backed by any OpenAI-compatible chat completions endpoint, including
 * aggregators such as FreeLLMAPI. It sends one JSON request and reads one JSON
 * response; the response's {@code choices[0].message.content} is handed to the
 * strict {@link ProposalParser} exactly as if it had come from Ollama.
 *
 * <p>Redirects are never followed. The base URL and API key are operator
 * configuration, not something the model controls. The API key is never
 * written to logs, error messages, or the audit trail.
 *
 * <p>JSON mode is requested with {@code response_format: {"type":
 * "json_object"}}. Some endpoints ignore this; some reject it. If a specific
 * endpoint rejects it, construct the provider with {@code useJsonMode = false}
 * and rely on the prompt and {@link ProposalParser} for structure.
 */
public final class OpenAICompatibleProvider implements AIProvider {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxContextChars;
    private final boolean useJsonMode;
    private final HttpClient client;

    public OpenAICompatibleProvider(String baseUrl, String apiKey, String model,
                                    Duration timeout, int maxContextChars) {
        this(baseUrl, apiKey, model, timeout, maxContextChars, true);
    }

    public OpenAICompatibleProvider(String baseUrl, String apiKey, String model,
                                    Duration timeout, int maxContextChars, boolean useJsonMode) {
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("base URL must start with http:// or https://");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("a model name is required");
        }
        if (timeout.isZero() || timeout.isNegative() || maxContextChars < 1) {
            throw new IllegalArgumentException("timeout and context limit must be positive");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = timeout;
        this.maxContextChars = maxContextChars;
        this.useJsonMode = useJsonMode;
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

        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(Json.quote(model))
                .append(",\"messages\":[{\"role\":\"user\",\"content\":").append(Json.quote(prompt)).append("}]")
                .append(",\"temperature\":0");
        if (useJsonMode) {
            body.append(",\"response_format\":{\"type\":\"json_object\"}");
        }
        body.append('}');

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = requestBuilder.build();

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
                            "the response is larger than " + maxBytes + " bytes");
                }
                responseText = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (HttpConnectTimeoutException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "could not connect to " + endpoint + " within " + timeout.toSeconds() + "s", e);
        } catch (HttpTimeoutException e) {
            throw new AIProviderException(AIProviderException.Kind.TIMEOUT,
                    "the endpoint did not answer within " + timeout.toSeconds() + "s", e);
        } catch (ConnectException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "could not connect to " + endpoint + " (is it reachable?)", e);
        } catch (IOException e) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "communication with " + endpoint + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "interrupted while waiting for the endpoint", e);
        }

        if (status != 200) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "HTTP " + status + " from " + endpoint + errorDetail(responseText));
        }

        Object root;
        try {
            root = Json.parse(responseText, 12);
        } catch (Json.ParseException e) {
            throw new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                    "the response was not valid JSON: " + e.getMessage(), e);
        }
        String content = extractContent(root);
        if (content == null) {
            throw new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                    "the response did not contain choices[0].message.content");
        }
        return ProposalParser.parse(content, context.policy());
    }

    /** Walks the OpenAI response shape: {@code choices[0].message.content}. */
    private static String extractContent(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return null;
        }
        if (!(map.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            return null;
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        return message.get("content") instanceof String s ? s : null;
    }

    /** Extracts {@code error.message} from the OpenAI error body, if present. */
    private static String errorDetail(String body) {
        try {
            if (Json.parse(body, 8) instanceof Map<?, ?> m && m.get("error") instanceof Map<?, ?> err
                    && err.get("message") instanceof String msg) {
                return ": " + (msg.length() > 300 ? msg.substring(0, 300) : msg);
            }
        } catch (Json.ParseException ignored) {
            // not JSON, or not the expected shape: no detail
        }
        return "";
    }
}
