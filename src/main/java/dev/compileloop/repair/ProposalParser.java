package dev.compileloop.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a model's raw text into a PatchProposal, or refuses. The format is strict
 * by design: exactly one JSON object with the keys summary (string, required),
 * diff (string, required) and assumptions (array of strings, optional). Unknown
 * keys, markdown fences, commentary around the JSON and trailing text are all
 * rejected rather than guessed at, because the model's output is untrusted.
 */
public final class ProposalParser {

    static final int MAX_SUMMARY_CHARS = 500;
    static final int MAX_ASSUMPTIONS = 20;
    static final int MAX_ASSUMPTION_CHARS = 500;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> KEYS = Set.of("summary", "diff", "assumptions");

    private ProposalParser() {
    }

    public static PatchProposal parse(String raw, PatchPolicy policy) throws AIProviderException {
        if (raw == null || raw.isBlank()) {
            throw malformed("the model returned an empty response");
        }
        if (raw.length() > policy.maxResponseChars()) {
            throw malformed("response is too large (" + raw.length() + " characters, limit "
                    + policy.maxResponseChars() + ")");
        }
        Object root;
        try {
            root = Json.parse(raw, MAX_DEPTH);
        } catch (Json.ParseException e) {
            throw malformed("not valid JSON: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw malformed("the top-level value must be a JSON object");
        }
        for (Object key : map.keySet()) {
            if (!KEYS.contains(key)) {
                throw malformed("unexpected key \"" + key + "\" (allowed: summary, diff, assumptions)");
            }
        }

        String summary = requireString(map, "summary");
        if (summary.isBlank()) {
            throw malformed("\"summary\" must not be blank");
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            throw malformed("\"summary\" is longer than " + MAX_SUMMARY_CHARS + " characters");
        }
        String diff = requireString(map, "diff");

        List<String> assumptions = new ArrayList<>();
        if (map.containsKey("assumptions")) {
            if (!(map.get("assumptions") instanceof List<?> list)) {
                throw malformed("\"assumptions\" must be an array of strings");
            }
            if (list.size() > MAX_ASSUMPTIONS) {
                throw malformed("\"assumptions\" has more than " + MAX_ASSUMPTIONS + " entries");
            }
            for (Object o : list) {
                if (!(o instanceof String str)) {
                    throw malformed("\"assumptions\" must contain only strings");
                }
                if (str.length() > MAX_ASSUMPTION_CHARS) {
                    throw malformed("an assumption is longer than " + MAX_ASSUMPTION_CHARS + " characters");
                }
                assumptions.add(str);
            }
        }
        return new PatchProposal(summary, diff, assumptions, raw);
    }

    private static String requireString(Map<?, ?> map, String key) throws AIProviderException {
        if (!map.containsKey(key)) {
            throw malformed("missing required key \"" + key + "\"");
        }
        if (!(map.get(key) instanceof String s)) {
            throw malformed("\"" + key + "\" must be a string");
        }
        return s;
    }

    private static AIProviderException malformed(String reason) {
        return new AIProviderException(AIProviderException.Kind.MALFORMED_RESPONSE,
                "Malformed model response: " + reason);
    }
}
