package dev.compileloop.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextReportTest {

    private static BuildResult result(BuildOutcome outcome, int exit, String tail, ParsedBuild parsed) {
        return new BuildResult("mvn -B test", outcome, exit, outcome == BuildOutcome.TIMEOUT,
                Instant.parse("2026-10-12T11:55:00Z"), 3700,
                Path.of("runs/r1/build.log"), tail, parsed,
                Path.of("C:\\Users\\Mdu\\proj"), "20261012T115500Z-3f9a1c", Path.of("runs/r1"));
    }

    @Test
    void passRendersSummaryAndArtifacts() {
        ParsedBuild parsed =
                BuildOutputParser.parse("[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1\n");

        String text = TextReport.render(result(BuildOutcome.PASS, 0, "", parsed));

        assertTrue(text.contains("CompileLoop: mvn -B test"));
        assertTrue(text.contains("Result: PASS (3.7s)"));
        assertTrue(text.contains("Tests: 3 run, 0 failed, 0 errors, 1 skipped"));
        assertTrue(text.contains("Run:    20261012T115500Z-3f9a1c"));
        assertTrue(text.contains("report.json"));
        assertTrue(text.contains("report.txt"));
    }

    @Test
    void failWithCompileErrorsRendersEachError() {
        ParsedBuild p = new ParsedBuild(
                List.of(new ParsedBuild.CompileError("A.java", 3, 13, "incompatible types",
                        DiagnosticCategory.TYPE_MISMATCH, "raw")),
                List.of(), Optional.empty(), List.of());

        String text = TextReport.render(result(BuildOutcome.FAIL, 1, "", p));

        assertTrue(text.contains("Result: FAIL, exit code 1"));
        assertTrue(text.contains("Compile errors (1):"));
        assertTrue(text.contains("  A.java:3:13"));
        assertTrue(text.contains("    incompatible types"));
    }

    @Test
    void failWithTestFailuresRendersAssertionMessage() {
        ParsedBuild p = new ParsedBuild(
                List.of(),
                List.of(new ParsedBuild.TestFailure("fx.CalcTest", "adds", "FAILURE",
                        "expected: <5> but was: <4>", 5, DiagnosticCategory.ASSERTION_FAILURE, "raw")),
                Optional.of(new ParsedBuild.TestSummary(2, 1, 0, 0)), List.of());

        String text = TextReport.render(result(BuildOutcome.FAIL, 1, "", p));

        assertTrue(text.contains("Test failures (1):"));
        assertTrue(text.contains("  fx.CalcTest.adds:5 [FAILURE]"));
        assertTrue(text.contains("    expected: <5> but was: <4>"));
        assertTrue(text.contains("Tests: 2 run, 1 failed, 0 errors, 0 skipped"));
    }

    @Test
    void failWithNothingRecognisedShowsTail() {
        String text = TextReport.render(
                result(BuildOutcome.FAIL, 1, "line one\nline two\nweird last line", ParsedBuild.empty()));

        assertTrue(text.contains("no structured errors were recognised"));
        assertTrue(text.contains("  weird last line"));
    }

    @Test
    void timeoutRendersWithAndWithoutTimeoutSeconds() {
        BuildResult r = result(BuildOutcome.TIMEOUT, -1, "", ParsedBuild.empty());

        assertTrue(TextReport.render(r).contains("stopped at the time limit"));
        assertTrue(TextReport.render(r, 7).contains("stopped after 7s"));
    }

    @Test
    void startErrorSaysNothingAboutCode() {
        BuildResult r = new BuildResult("mvn -B test", BuildOutcome.START_ERROR, -1, false,
                Instant.parse("2026-10-12T11:55:00Z"), 2, null, "Could not locate mvn",
                ParsedBuild.empty(), Path.of("proj"), null, null);

        String text = TextReport.render(r);

        assertTrue(text.contains("Result: START_ERROR"));
        assertTrue(text.contains("says nothing about the project's code"));
        assertTrue(text.contains("Could not locate mvn"));
    }

    @Test
    void unclassifiedErrorsAreNotedInFailReport() {
        ParsedBuild p = new ParsedBuild(
                List.of(new ParsedBuild.CompileError("A.java", 1, 1, "msg",
                        DiagnosticCategory.OTHER_COMPILE_ERROR, "raw")),
                List.of(), Optional.empty(),
                List.of("[ERROR] something weird", "[ERROR] another weird thing"));

        String text = TextReport.render(result(BuildOutcome.FAIL, 1, "", p));

        assertTrue(text.contains("Also present: 2 unclassified [ERROR] line(s)"));
    }
}
