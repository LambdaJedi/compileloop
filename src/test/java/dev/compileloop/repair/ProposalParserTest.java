package dev.compileloop.repair;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProposalParserTest {

    private static final PatchPolicy POLICY = PatchPolicy.defaults();

    private static PatchProposal parse(String raw) throws AIProviderException {
        return ProposalParser.parse(raw, POLICY);
    }

    private static void assertMalformed(String raw) {
        assertMalformed(raw, POLICY);
    }

    private static void assertMalformed(String raw, PatchPolicy policy) {
        try {
            ProposalParser.parse(raw, policy);
            fail("expected a malformed-response error for: " + raw);
        } catch (AIProviderException e) {
            assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, e.kind(), e.getMessage());
        }
    }

    @Test
    void acceptsTheDocumentedShape() throws AIProviderException {
        String raw = "{\"summary\":\"Fix null handling\",\"diff\":\"--- a/x\\n+++ b/x\\n\","
                + "\"assumptions\":[\"empty input stays empty\"]}";

        PatchProposal p = parse(raw);

        assertEquals("Fix null handling", p.summary());
        assertEquals("--- a/x\n+++ b/x\n", p.diff());
        assertEquals(List.of("empty input stays empty"), p.assumptions());
        assertEquals(raw, p.rawResponse(), "the raw text is kept exactly for the audit trail");
    }

    @Test
    void assumptionsAreOptional() throws AIProviderException {
        PatchProposal p = parse("{\"summary\":\"s\",\"diff\":\"\"}");

        assertEquals(List.of(), p.assumptions());
    }

    @Test
    void surroundingWhitespaceIsFine() throws AIProviderException {
        parse("\n  {\"summary\":\"s\",\"diff\":\"d\"}  \r\n");
    }

    @Test
    void escapesRoundTrip() throws AIProviderException {
        String diff = "--- a/A.java\n+++ b/A.java\n+String s = \"quote\\backslash\\\";\ttab é \uD83D\uDE00 \u0001\n";
        String raw = "{\"summary\":\"s\",\"diff\":" + Json.quote(diff) + "}";

        assertEquals(diff, parse(raw).diff());
    }

    @Test
    void rejectsNonJsonAndWrongTopLevel() {
        assertMalformed(null);
        assertMalformed("");
        assertMalformed("   \n");
        assertMalformed("I cannot fix this.");
        assertMalformed("[]");
        assertMalformed("\"just a string\"");
        assertMalformed("42");
        assertMalformed("null");
    }

    @Test
    void rejectsWrapperAroundTheJson() {
        String json = "{\"summary\":\"s\",\"diff\":\"d\"}";
        assertMalformed("```json\n" + json + "\n```");
        assertMalformed("Here is the fix:\n" + json);
        assertMalformed(json + "\nHope that helps!");
        assertMalformed(json + json);
    }

    @Test
    void rejectsSchemaViolations() {
        assertMalformed("{\"diff\":\"d\"}");                                   // no summary
        assertMalformed("{\"summary\":\"s\"}");                                // no diff
        assertMalformed("{\"summary\":1,\"diff\":\"d\"}");                     // wrong type
        assertMalformed("{\"summary\":\"s\",\"diff\":null}");
        assertMalformed("{\"summary\":\"s\",\"diff\":[\"d\"]}");
        assertMalformed("{\"summary\":\"   \",\"diff\":\"d\"}");               // blank
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"command\":\"rm -rf /\"}"); // unknown key
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":\"x\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":[1]}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":[null]}");
        assertMalformed("{\"summary\":\"" + "x".repeat(501) + "\",\"diff\":\"d\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":[\"" + "x".repeat(501) + "\"]}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":["
                + "\"a\",".repeat(20) + "\"b\"]}");                              // 21 entries
    }

    @Test
    void rejectsInvalidJsonDetails() {
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",}");                // trailing comma
        assertMalformed("{'summary':'s','diff':'d'}");                         // single quotes
        assertMalformed("{summary:\"s\",\"diff\":\"d\"}");                     // bare key
        assertMalformed("{\"summary\":\"s\",\"summary\":\"t\",\"diff\":\"d\"}"); // duplicate key
        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\"} // note");         // comment
        assertMalformed("{\"summary\":\"s\",\"diff\":\"line1\nline2\"}");      // raw newline in string
        assertMalformed("{\"summary\":\"s\",\"diff\":\"bad \\x escape\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"truncated \\u12\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"lone \\ud83d surrogate\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"lone \\ude00 surrogate\"}");
        assertMalformed("{\"summary\":\"s\",\"diff\":\"unterminated}");
        assertMalformed("{\"summary\":\"s\",\"diff\":");
        assertMalformed("{\"summary\":\"s\",\"diff\":NaN}");
        assertMalformed("{\"summary\":\"s\",\"diff\":01}");
        assertMalformed("\uFEFF{\"summary\":\"s\",\"diff\":\"d\"}");           // BOM
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(200) + "]".repeat(200);

        assertMalformed("{\"summary\":\"s\",\"diff\":\"d\",\"assumptions\":" + deep + "}");
    }

    @Test
    void rejectsOversizedResponses() {
        PatchPolicy tiny = POLICY.withMaxResponseChars(50);

        assertMalformed("{\"summary\":\"s\",\"diff\":\"" + "x".repeat(100) + "\"}", tiny);
    }

    @Test
    void errorMessagesNameTheProblem() {
        try {
            parse("{\"summary\":\"s\",\"diff\":\"d\",\"command\":\"x\"}");
            fail("expected an error");
        } catch (AIProviderException e) {
            assertTrue(e.getMessage().contains("command"), e.getMessage());
        }
    }

    @Test
    void jsonNumbersAndLiteralsAreAcceptedWhereAllowed() throws Json.ParseException {
        Object v = Json.parse("{\"a\":[1,-2,3.5,1e3,true,false,null,\"x\"]}", 8);

        assertTrue(v instanceof java.util.Map<?, ?>);
    }
}
