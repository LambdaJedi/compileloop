package dev.compileloop.repair;

import dev.compileloop.build.ParsedBuild;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockProviderTest {

    static RepairContext context() {
        return new RepairContext("java", "maven", ParsedBuild.empty(), List.of(), PatchPolicy.defaults(),
                List.of(), "tests pass");
    }

    private static final String OK = "{\"summary\":\"fix\",\"diff\":\"--- a/x\\n+++ b/x\\n\"}";

    @Test
    void replaysResponsesInOrderThenRunsOut() throws AIProviderException {
        MockProvider m = MockProvider.returning(OK, "{\"summary\":\"second\",\"diff\":\"\"}");

        assertEquals("fix", m.proposeRepair(context()).summary());
        assertEquals("second", m.proposeRepair(context()).summary());
        AIProviderException e = assertThrows(AIProviderException.class, () -> m.proposeRepair(context()));
        assertEquals(AIProviderException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    void cannedTextGoesThroughTheStrictParser() {
        MockProvider m = MockProvider.returning("```json\n" + OK + "\n```");

        AIProviderException e = assertThrows(AIProviderException.class, () -> m.proposeRepair(context()));

        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void canSimulateEachFailureKind() {
        for (AIProviderException.Kind kind : AIProviderException.Kind.values()) {
            MockProvider m = MockProvider.failing(kind, "boom");
            AIProviderException e = assertThrows(AIProviderException.class, () -> m.proposeRepair(context()));
            assertEquals(kind, e.kind());
            assertEquals("boom", e.getMessage());
        }
    }

    @Test
    void contextLimitProducesOverflowWithoutConsumingAResponse() throws AIProviderException {
        MockProvider m = MockProvider.returning(OK).withContextLimit(10);

        AIProviderException e = assertThrows(AIProviderException.class, () -> m.proposeRepair(context()));

        assertEquals(AIProviderException.Kind.CONTEXT_OVERFLOW, e.kind());
        assertTrue(e.getMessage().contains("characters"));
    }

    @Test
    void remembersEveryContextItWasGiven() throws AIProviderException {
        MockProvider m = MockProvider.returning(OK);
        RepairContext c = context();

        m.proposeRepair(c);

        assertEquals(1, m.receivedContexts().size());
        assertTrue(m.receivedContexts().get(0) == c);
    }
}
