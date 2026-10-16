package dev.compileloop.repair;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.compileloop.build.ParsedBuild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider is tested against a real HTTP server on localhost. Same pattern
 * as {@code OllamaProviderTest}: no mocking library, no fake transport — a real
 * socket, a real request, a real response.
 */
class OpenAICompatibleProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respondWith(int status, String body) {
        server.createContext("/chat/completions", (HttpExchange ex) -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                lastAuth.set(auth);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static RepairContext context() {
        return new RepairContext("java", "maven",
                new ParsedBuild(List.of(), List.of(), Optional.empty()),
                List.of(), PatchPolicy.defaults(), List.of(),
                "`mvn -B test` must pass with no failing tests.");
    }

    private static String proposalContent() {
        return "{\"summary\":\"fix it\",\"diff\":\"--- a/A.java\\n+++ b/A.java\\n@@ -1,1 +1,1 @@\\n-a\\n+b\\n\",\"assumptions\":[]}";
    }

    private static String successBody(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
    }

    @Test
    void sendsTheExpectedRequestAndReturnsTheProposal() throws Exception {
        respondWith(200, successBody(proposalContent()));

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "secret-key", "gpt-4o-mini", Duration.ofSeconds(10), 100_000);

        PatchProposal result = p.proposeRepair(context());

        assertEquals("fix it", result.summary());
        assertTrue(result.diff().contains("--- a/A.java"));
        assertEquals("Bearer secret-key", lastAuth.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""), body);
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"), body);
        assertTrue(body.contains("\"temperature\":0"), body);
    }

    @Test
    void omitsTheAuthorizationHeaderWhenTheKeyIsBlank() throws Exception {
        respondWith(200, successBody(proposalContent()));

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "", "gpt-4o-mini", Duration.ofSeconds(10), 100_000);

        p.proposeRepair(context());

        assertEquals(null, lastAuth.get(), "no Authorization header when the key is blank");
    }

    @Test
    void jsonModeCanBeDisabled() throws Exception {
        respondWith(200, successBody(proposalContent()));

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "gpt-4o-mini", Duration.ofSeconds(10), 100_000, false);

        p.proposeRepair(context());

        assertFalse(lastBody.get().contains("response_format"), lastBody.get());
    }

    @Test
    void httpErrorIsReportedAsUnavailableWithTheServerMessage() throws Exception {
        respondWith(401, "{\"error\":{\"message\":\"invalid api key\",\"type\":\"auth\"}}");

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "wrong", "gpt-4o-mini", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid api key"), e.getMessage());
    }

    @Test
    void missingChoicesIsMalformedResponse() throws Exception {
        respondWith(200, "{\"id\":\"x\",\"object\":\"chat.completion\"}");

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "m", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void emptyChoicesIsMalformedResponse() throws Exception {
        respondWith(200, "{\"choices\":[]}");

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "m", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void contentThatIsNotAProposalIsMalformedResponse() throws Exception {
        respondWith(200, successBody("Sure! Here is a fix: ..."));

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "m", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void nonJsonBodyIsMalformedResponse() throws Exception {
        respondWith(200, "<html>not json</html>");

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "m", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void contextOverflowIsDetectedBeforeTheRequest() {
        server.createContext("/chat/completions", (HttpExchange ex) -> {
            throw new AssertionError("the request must not be sent");
        });

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "k", "m", Duration.ofSeconds(10), 10);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.CONTEXT_OVERFLOW, e.kind());
    }

    @Test
    void invalidBaseUrlIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAICompatibleProvider("ftp://x", "k", "m", Duration.ofSeconds(1), 100));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAICompatibleProvider("http://x", "k", "", Duration.ofSeconds(1), 100));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAICompatibleProvider("http://x", "k", "m", Duration.ZERO, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAICompatibleProvider("http://x", "k", "m", Duration.ofSeconds(1), 0));
    }

    @Test
    void theApiKeyNeverAppearsInAnErrorMessage() throws Exception {
        respondWith(500, "{\"error\":{\"message\":\"upstream failure\"}}");

        OpenAICompatibleProvider p = new OpenAICompatibleProvider(
                baseUrl, "super-secret-token", "m", Duration.ofSeconds(10), 100_000);

        AIProviderException e = assertThrows(AIProviderException.class, () -> p.proposeRepair(context()));
        assertFalse(e.getMessage().contains("super-secret-token"), e.getMessage());
    }
}
