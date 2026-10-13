package dev.compileloop.repair;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real Ollama is not needed: a fake local HTTP server stands in for it. */
class OllamaProviderTest {

    private static final String PROPOSAL = "{\"summary\":\"fix it\",\"diff\":\"--- a/x\\n+++ b/x\\n\",\"assumptions\":[\"a\"]}";

    /** A fake server answering every request with the given status and body. */
    private static final class Fake implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> contentType = new AtomicReference<>();
        final AtomicInteger hits = new AtomicInteger();

        Fake(int status, String body) throws IOException {
            this(status, body, 0);
        }

        Fake(int status, String body, long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", ex -> {
                hits.incrementAndGet();
                method.set(ex.getRequestMethod());
                path.set(ex.getRequestURI().getPath());
                contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
                requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
                    if (b.length > 0) {
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(b);
                        }
                    }
                } catch (IOException ignored) {
                    // client gave up (timeout test)
                } finally {
                    ex.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String chat(String content) {
        return "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":" + Json.quote(content) + "},\"done\":true}";
    }

    private static OllamaProvider provider(Fake f) {
        return new OllamaProvider(f.url(), "qwen2.5-coder", Duration.ofSeconds(5), 100_000);
    }

    private static AIProviderException failure(OllamaProvider p) {
        return assertThrows(AIProviderException.class, () -> p.proposeRepair(MockProviderTest.context()));
    }

    @Test
    void returnsTheParsedProposalAndSendsASafeRequest() throws Exception {
        try (Fake f = new Fake(200, chat(PROPOSAL))) {
            PatchProposal p = provider(f).proposeRepair(MockProviderTest.context());

            assertEquals("fix it", p.summary());
            assertEquals(PROPOSAL, p.rawResponse());
            assertEquals("POST", f.method.get());
            assertEquals("/api/chat", f.path.get());
            assertTrue(f.contentType.get().startsWith("application/json"));
            String body = f.requestBody.get();
            assertTrue(body.contains("\"model\":\"qwen2.5-coder\""), body);
            assertTrue(body.contains("\"stream\":false"), body);
            assertTrue(body.contains("\"format\":\"json\""), body);
            assertTrue(body.contains("\"temperature\":0"), body);
            assertTrue(body.contains("exactly one JSON object"), "the rendered prompt is sent");
        }
    }

    @Test
    void trailingSlashInTheBaseUrlIsFine() throws Exception {
        try (Fake f = new Fake(200, chat(PROPOSAL))) {
            new OllamaProvider(f.url() + "/", "m", Duration.ofSeconds(5), 100_000)
                    .proposeRepair(MockProviderTest.context());

            assertEquals("/api/chat", f.path.get());
        }
    }

    @Test
    void unknownModelIsReportedAsUnavailableWithOllamasMessage() throws Exception {
        try (Fake f = new Fake(404, "{\"error\":\"model 'nope' not found\"}")) {
            AIProviderException e = failure(provider(f));

            assertEquals(AIProviderException.Kind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage().contains("404"));
            assertTrue(e.getMessage().contains("model 'nope' not found"), e.getMessage());
        }
    }

    @Test
    void serverErrorIsUnavailableEvenWithoutAJsonBody() throws Exception {
        try (Fake f = new Fake(500, "<html>oops</html>")) {
            AIProviderException e = failure(provider(f));

            assertEquals(AIProviderException.Kind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage().contains("500"));
        }
    }

    @Test
    void modelOutputThatBreaksTheFormatIsMalformed() throws Exception {
        try (Fake f = new Fake(200, chat("Sure! Here is the fix:\n```diff\n--- a/x\n```"))) {
            assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, failure(provider(f)).kind());
        }
    }

    @Test
    void aResponseThatIsNotJsonIsMalformed() throws Exception {
        try (Fake f = new Fake(200, "definitely not json")) {
            assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, failure(provider(f)).kind());
        }
    }

    @Test
    void aResponseWithoutMessageContentIsMalformed() throws Exception {
        for (String body : new String[]{"{}", "{\"message\":{}}", "{\"message\":{\"content\":5}}", "[]", "\"x\""}) {
            try (Fake f = new Fake(200, body)) {
                assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, failure(provider(f)).kind(), body);
            }
        }
    }

    @Test
    void anEmptyBodyIsMalformed() throws Exception {
        try (Fake f = new Fake(200, "")) {
            assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, failure(provider(f)).kind());
        }
    }

    @Test
    void slowModelTimesOut() throws Exception {
        try (Fake f = new Fake(200, chat(PROPOSAL), 3_000)) {
            OllamaProvider p = new OllamaProvider(f.url(), "m", Duration.ofMillis(300), 100_000);

            assertEquals(AIProviderException.Kind.TIMEOUT, failure(p).kind());
        }
    }

    @Test
    void nothingListeningIsUnavailable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        OllamaProvider p = new OllamaProvider("http://127.0.0.1:" + port, "m", Duration.ofSeconds(2), 100_000);

        assertEquals(AIProviderException.Kind.UNAVAILABLE, failure(p).kind());
    }

    @Test
    void oversizedContextIsRefusedWithoutContactingTheServer() throws Exception {
        try (Fake f = new Fake(200, chat(PROPOSAL))) {
            OllamaProvider p = new OllamaProvider(f.url(), "m", Duration.ofSeconds(5), 50);

            assertEquals(AIProviderException.Kind.CONTEXT_OVERFLOW, failure(p).kind());
            assertEquals(0, f.hits.get());
        }
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        try (Fake target = new Fake(200, chat(PROPOSAL))) {
            HttpServer redirector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            redirector.createContext("/", ex -> {
                ex.getResponseHeaders().add("Location", target.url() + "/api/chat");
                ex.sendResponseHeaders(307, -1);
                ex.close();
            });
            redirector.start();
            try {
                OllamaProvider p = new OllamaProvider("http://127.0.0.1:" + redirector.getAddress().getPort(),
                        "m", Duration.ofSeconds(5), 100_000);

                AIProviderException e = failure(p);

                assertEquals(AIProviderException.Kind.UNAVAILABLE, e.kind());
                assertEquals(0, target.hits.get(), "the redirect target must never be contacted");
            } finally {
                redirector.stop(0);
            }
        }
    }

    @Test
    void absurdlyLargeResponsesAreCutOffAsMalformed() throws Exception {
        PatchPolicy tiny = PatchPolicy.defaults().withMaxResponseChars(1_000);
        RepairContext ctx = new RepairContext("java", "maven", dev.compileloop.build.ParsedBuild.empty(),
                java.util.List.of(), tiny, java.util.List.of(), "ok");
        try (Fake f = new Fake(200, chat("x".repeat(400_000)))) {
            AIProviderException e = assertThrows(AIProviderException.class, () -> provider(f).proposeRepair(ctx));

            assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
        }
    }

    @Test
    void constructorRejectsBadConfiguration() {
        Duration ok = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new OllamaProvider("localhost:11434", "m", ok, 10));
        assertThrows(IllegalArgumentException.class, () -> new OllamaProvider("file:///etc/passwd", "m", ok, 10));
        assertThrows(IllegalArgumentException.class, () -> new OllamaProvider("http://x", " ", ok, 10));
        assertThrows(IllegalArgumentException.class, () -> new OllamaProvider("http://x", "m", Duration.ZERO, 10));
        assertThrows(IllegalArgumentException.class, () -> new OllamaProvider("http://x", "m", ok, 0));
        assertFalse(OllamaProvider.DEFAULT_BASE_URL.isBlank());
    }
}
