package dev.compileloop.build;

import java.util.List;
import java.util.Optional;

/** Structured view of one Maven build's console output. */
public record ParsedBuild(
        List<CompileError> compileErrors,
        List<TestFailure> testFailures,
        Optional<TestSummary> testSummary
) {

    /**
     * One javac error. {@code file} uses forward slashes with no leading slash
     * before a Windows drive letter (Maven prints {@code /C:/Users/...}).
     * {@code message} may span several lines (e.g. "cannot find symbol" plus
     * its symbol/location lines), joined with '\n'.
     */
    public record CompileError(String file, int line, int column, String message) {
    }

    /**
     * One failed or errored test. {@code kind} is "FAILURE" (assertion) or
     * "ERROR" (unexpected exception). {@code testMethod} is empty for
     * class-level failures. {@code line} is the line in the test class where
     * the failure surfaced, or -1 if the stack trace did not show one.
     */
    public record TestFailure(String testClass, String testMethod, String kind,
                              String message, int line) {
    }

    public record TestSummary(int run, int failures, int errors, int skipped) {
    }

    public boolean hasCompileErrors() {
        return !compileErrors.isEmpty();
    }

    public boolean hasTestFailures() {
        return !testFailures.isEmpty();
    }
}
