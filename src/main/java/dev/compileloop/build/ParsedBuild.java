package dev.compileloop.build;

import java.util.List;
import java.util.Optional;

/** Structured view of one Maven build's console output. */
public record ParsedBuild(
        List<CompileError> compileErrors,
        List<TestFailure> testFailures,
        Optional<TestSummary> testSummary,
        List<String> unrecognized
) {

    public ParsedBuild {
        compileErrors = List.copyOf(compileErrors);
        testFailures = List.copyOf(testFailures);
        unrecognized = List.copyOf(unrecognized);
    }

    /** Convenience for callers that do not track unclassified lines. */
    public ParsedBuild(List<CompileError> ce, List<TestFailure> tf, Optional<TestSummary> ts) {
        this(ce, tf, ts, List.of());
    }

    /**
     * One javac error. {@code file} uses forward slashes with no leading slash
     * before a Windows drive letter (Maven prints {@code /C:/Users/...}).
     * {@code message} may span several lines (e.g. "cannot find symbol" plus
     * its symbol/location lines), joined with '\n'. {@code rawExcerpt} is the
     * original log text for this error, unmodified.
     */
    public record CompileError(String file, int line, int column, String message,
                               DiagnosticCategory category, String rawExcerpt) {
    }

    /**
     * One failed or errored test. {@code kind} is "FAILURE" (assertion) or
     * "ERROR" (unexpected exception). {@code testMethod} is empty for
     * class-level failures. {@code line} is the line in the test class where
     * the failure surfaced, or -1 if the stack trace did not show one.
     */
    public record TestFailure(String testClass, String testMethod, String kind,
                              String message, int line,
                              DiagnosticCategory category, String rawExcerpt) {
    }

    public record TestSummary(int run, int failures, int errors, int skipped) {
    }

    public static ParsedBuild empty() {
        return new ParsedBuild(List.of(), List.of(), Optional.empty(), List.of());
    }

    public boolean hasCompileErrors() {
        return !compileErrors.isEmpty();
    }

    public boolean hasTestFailures() {
        return !testFailures.isEmpty();
    }

    public boolean hasUnrecognizedErrors() {
        return !unrecognized.isEmpty();
    }
}
