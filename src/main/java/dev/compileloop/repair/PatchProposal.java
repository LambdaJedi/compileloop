package dev.compileloop.repair;

import java.util.List;

/**
 * A model's proposed repair, parsed against the strict schema. Still untrusted:
 * {@code diff} has not been validated. {@code rawResponse} is the exact text the
 * model returned, kept for the audit trail before anything is interpreted.
 */
public record PatchProposal(String summary, String diff, List<String> assumptions, String rawResponse) {
    public PatchProposal {
        assumptions = List.copyOf(assumptions);
    }
}
