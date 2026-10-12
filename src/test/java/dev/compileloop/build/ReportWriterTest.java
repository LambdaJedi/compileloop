package dev.compileloop.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = ReportWriterTest.class.getResourceAsStream("/maven/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BuildResult result(BuildOutcome outcome, int exit, String tail, ParsedBuild parsed) {
        return new BuildResult("mvn -B test", outcome, exit, outcome == BuildOutcome.TIMEOUT,
                Instant.parse("2026-10-12T11:55:00Z"), 3700, Path.of("runs/r1/build.log"), tail, parsed,
                Path.of("C:\\Users\\Mdu\\proj"), "20261012T115500Z-3f9a1c", Path.of("runs/r1"));
    }

    @Test
    void compileFailureReportHasStructuredDiagnostics() throws IOException {
        String log = resource("compile-error.log");

        String json = ReportWriter.toJson(result(BuildOutcome.FAIL, 1, log, BuildOutputParser.parse(log)));

        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"runId\": \"20261012T115500Z-3f9a1c\""));
        assertTrue(json.contains("\"outcome\": \"FAIL\""));
        assertTrue(json.contains("\"exitCode\": 1"));
        assertTrue(json.contains("\"logFile\": \"build.log\""));
        assertTrue(json.contains("\"category\": \"TYPE_MISMATCH\""));
        assertTrue(json.contains("\"category\": \"SYMBOL_NOT_FOUND\""));
        assertTrue(json.contains("\"line\": 3"));
        assertTrue(json.contains("\"column\": 13"));
        assertTrue(json.contains("\"testSummary\": null"));
        assertTrue(json.contains("\"notes\": []"));
        // Windows backslashes in the project path must be escaped
        assertTrue(json.contains("C:\\\\Users\\\\Mdu\\\\proj"));
    }

    @Test
    void testFailureReportHasLocationAndSummary() throws IOException {
        String log = resource("failing-test.log");

        String json = ReportWriter.toJson(result(BuildOutcome.FAIL, 1, log, BuildOutputParser.parse(log)));

        assertTrue(json.contains("\"category\": \"ASSERTION_FAILURE\""));
        assertTrue(json.contains("\"testClass\": \"fx.CalcTest\""));
        assertTrue(json.contains("\"testMethod\": \"adds\""));
        assertTrue(json.contains("\"run\": 2"));
        assertTrue(json.contains("\"failures\": 1"));
    }

    @Test
    void unrecognisedFailureIsStatedHonestly() {
        String json = ReportWriter.toJson(result(BuildOutcome.FAIL, 1, "weird", ParsedBuild.empty()));

        assertTrue(json.contains("no structured diagnostics were recognised"));
        assertTrue(json.contains("\"compileErrors\": []"));
    }

    @Test
    void infrastructureFailureIsNotPresentedAsACodeProblem() {
        BuildResult r = new BuildResult("mvn -B test", BuildOutcome.START_ERROR, -1, false,
                Instant.parse("2026-10-12T11:55:00Z"), 2, null, "Could not locate mvn", ParsedBuild.empty(),
                Path.of("proj"), null, null);

        String json = ReportWriter.toJson(r);

        assertTrue(json.contains("\"infrastructureFailure\": true"));
        assertTrue(json.contains("says nothing about the project's code"));
        assertTrue(json.contains("\"detail\": \"Could not locate mvn\""));
        assertTrue(json.contains("\"runId\": null"));
    }

    @Test
    void specialCharactersAreEscaped() {
        ParsedBuild parsed = new ParsedBuild(
                java.util.List.of(new ParsedBuild.CompileError("A.java", 1, 2,
                        "say \"hi\"\nline2\ttab \\ back \u0001", DiagnosticCategory.OTHER_COMPILE_ERROR, "raw")),
                java.util.List.of(), java.util.Optional.empty());

        String json = ReportWriter.toJson(result(BuildOutcome.FAIL, 1, "", parsed));

        assertTrue(json.contains("say \\\"hi\\\"\\nline2\\ttab \\\\ back \\u0001"));
        assertFalse(json.contains("\u0001"), "raw control characters must never appear");
    }

    @Test
    void timeoutReportSaysDiagnosticsAreUnavailable() {
        String json = ReportWriter.toJson(result(BuildOutcome.TIMEOUT, -1, "", ParsedBuild.empty()));

        assertTrue(json.contains("\"timedOut\": true"));
        assertTrue(json.contains("stopped at the time limit"));
    }
}
