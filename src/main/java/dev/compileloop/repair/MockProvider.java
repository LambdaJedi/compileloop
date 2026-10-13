package dev.compileloop.repair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic provider for tests and offline runs: replays canned raw model
 * responses in order. Each one goes through the same strict ProposalParser a real
 * model's output would, so malformed canned text fails exactly as it would live.
 */
public final class MockProvider implements AIProvider {

    private final Deque<String> responses = new ArrayDeque<>();
    private final List<RepairContext> received = new ArrayList<>();
    private AIProviderException.Kind failWith;
    private String failMessage;
    private int contextLimit = Integer.MAX_VALUE;

    private MockProvider() {
    }

    public static MockProvider returning(String... rawResponses) {
        MockProvider m = new MockProvider();
        m.responses.addAll(List.of(rawResponses));
        return m;
    }

    public static MockProvider failing(AIProviderException.Kind kind, String message) {
        MockProvider m = new MockProvider();
        m.failWith = kind;
        m.failMessage = message;
        return m;
    }

    /** Simulate a model whose context window is {@code maxChars} characters. */
    public MockProvider withContextLimit(int maxChars) {
        this.contextLimit = maxChars;
        return this;
    }

    @Override
    public synchronized PatchProposal proposeRepair(RepairContext context) throws AIProviderException {
        received.add(context);
        if (failWith != null) {
            throw new AIProviderException(failWith, failMessage);
        }
        int size = context.render().length();
        if (size > contextLimit) {
            throw new AIProviderException(AIProviderException.Kind.CONTEXT_OVERFLOW,
                    "context is " + size + " characters, the model accepts " + contextLimit);
        }
        String raw = responses.poll();
        if (raw == null) {
            throw new AIProviderException(AIProviderException.Kind.UNAVAILABLE,
                    "the mock provider has no more canned responses");
        }
        return ProposalParser.parse(raw, context.policy());
    }

    /** Every context this provider was asked about, in order. */
    public synchronized List<RepairContext> receivedContexts() {
        return List.copyOf(received);
    }
}
