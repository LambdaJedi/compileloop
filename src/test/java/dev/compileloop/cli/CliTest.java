package dev.compileloop.cli;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildOutputParser;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.ParsedBuild;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final AtomicReference<Duration> seenTimeout = new AtomicReference<>();
    private final AtomicReference<Path> seenPath = new AtomicReference<>();

    private Cli cli(Cli.BuildAction action) {
        return new Cli(action,
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    }

    private Cli cliReturning(BuildResult result) {
        return cli((dir, timeout) -> {
            seenPath.set(dir);
            seenTimeout.set(timeout);
            return result;
        });
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CliTest.class.getResourceAsStream("/maven/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BuildResult result(BuildOutcome outcome, int exit, String tail, ParsedBuild parsed) {
        return new BuildResult("mvn -B test", outcome, exit, outcome == BuildOutcome.TIMEOUT,
                Instant.now(), 3700, Path.of("target/compileloop/build.log"), tail, parsed);
    }

    // ---- usage ------------------------------------------------------------

    @Test
    void noArgumentsIsUsageError() {
        assertEquals(Cli.EXIT_USAGE, cliReturning(null).run(new String[]{}));
        assertTrue(err().contains("Usage:"));
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        assertEquals(0, cliReturning(null).run(new String[]{"--help"}));
        assertTrue(out().contains("compileloop test <project-dir>"));
    }

    @Test
    void unknownCommandAndOptionAreUsageErrors() {
        assertEquals(Cli.EXIT_USAGE, cliReturning(null).run(new String[]{"build", "x"}));
        assertEquals(Cli.EXIT_USAGE, cliReturning(null).run(new String[]{"test", "x", "--nope"}));
        assertEquals(Cli.EXIT_USAGE, cliReturning(null).run(new String[]{"test"}));
        assertEquals(Cli.EXIT_USAGE, cliReturning(null).run(new String[]{"test", "a", "b"}));
    }

    @Test
    void timeoutOptionIsValidatedAndPassedThrough() {
        BuildResult pass = result(BuildOutcome.PASS, 0, "", ParsedBuild.empty());

        assertEquals(0, cliReturning(pass).run(new String[]{"test", "proj", "--timeout", "45"}));
        assertEquals(Duration.ofSeconds(45), seenTimeout.get());
        assertEquals(Path.of("proj"), seenPath.get());

        assertEquals(0, cliReturning(pass).run(new String[]{"test", "--timeout=10", "proj"}));
        assertEquals(Duration.ofSeconds(10), seenTimeout.get());

        assertEquals(0, cliReturning(pass).run(new String[]{"test", "proj"}));
        assertEquals(Duration.ofSeconds(300), seenTimeout.get());

        assertEquals(Cli.EXIT_USAGE, cliReturning(pass).run(new String[]{"test", "proj", "--timeout", "abc"}));
        assertEquals(Cli.EXIT_USAGE, cliReturning(pass).run(new String[]{"test", "proj", "--timeout", "0"}));
        assertEquals(Cli.EXIT_USAGE, cliReturning(pass).run(new String[]{"test", "proj", "--timeout"}));
    }

    // ---- reporting --------------------------------------------------------

    @Test
    void passReportsSummaryAndExitsZero() {
        ParsedBuild parsed = BuildOutputParser.parse("[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1\n");

        int code = cliReturning(result(BuildOutcome.PASS, 0, "", parsed)).run(new String[]{"test", "p"});

        assertEquals(0, code);
        assertTrue(out().contains("Result: PASS"));
        assertTrue(out().contains("Tests: 3 run, 0 failed, 0 errors, 1 skipped"));
        assertTrue(out().contains("Full log:"));
    }

    @Test
    void compileFailureListsErrorsFromRealOutput() throws IOException {
        String log = resource("compile-error.log");
        BuildResult r = result(BuildOutcome.FAIL, 1, log, BuildOutputParser.parse(log));

        int code = cliReturning(r).run(new String[]{"test", "p"});

        assertEquals(Cli.EXIT_FAIL, code);
        String o = out();
        assertTrue(o.contains("Result: FAIL, exit code 1"));
        assertTrue(o.contains("Compile errors (2):"));
        assertTrue(o.contains("C:/Users/Mdu/samples/src/main/java/fx/Broken.java:3:13"));
        assertTrue(o.contains("    incompatible types: java.lang.String cannot be converted to int"));
        assertTrue(o.contains("    symbol:   method undefinedMethod()"));
        assertFalse(o.contains("no structured errors"));
    }

    @Test
    void testFailureListsFailureFromRealOutput() throws IOException {
        String log = resource("failing-test.log");
        BuildResult r = result(BuildOutcome.FAIL, 1, log, BuildOutputParser.parse(log));

        int code = cliReturning(r).run(new String[]{"test", "p"});

        assertEquals(Cli.EXIT_FAIL, code);
        String o = out();
        assertTrue(o.contains("Test failures (1):"));
        assertTrue(o.contains("fx.CalcTest.adds:5 [FAILURE]"));
        assertTrue(o.contains("expected: <5> but was: <4>"));
        assertTrue(o.contains("Tests: 2 run, 1 failed, 0 errors, 0 skipped"));
    }

    @Test
    void failWithNothingRecognisedShowsTailInstead() {
        BuildResult r = result(BuildOutcome.FAIL, 1, "weird line one\nweird line two\n", ParsedBuild.empty());

        int code = cliReturning(r).run(new String[]{"test", "p"});

        assertEquals(Cli.EXIT_FAIL, code);
        assertTrue(out().contains("no structured errors were recognised"));
        assertTrue(out().contains("  weird line two"));
    }

    @Test
    void timeoutHasItsOwnExitCode() {
        int code = cliReturning(result(BuildOutcome.TIMEOUT, -1, "", ParsedBuild.empty()))
                .run(new String[]{"test", "p", "--timeout", "7"});

        assertEquals(Cli.EXIT_TIMEOUT, code);
        assertTrue(out().contains("stopped after 7s"));
    }

    @Test
    void startErrorIsInfrastructureNotCodeFailure() {
        BuildResult r = new BuildResult("mvn -B test", BuildOutcome.START_ERROR, -1, false,
                Instant.now(), 2, null, "Could not locate mvn, mvnw, or mvnw.cmd.", ParsedBuild.empty());

        int code = cliReturning(r).run(new String[]{"test", "p"});

        assertEquals(Cli.EXIT_INFRA, code);
        assertTrue(out().contains("says nothing about the project's code"));
        assertTrue(out().contains("Could not locate mvn"));
        assertFalse(out().contains("Full log:"));
    }

    @Test
    void ioExceptionFromRunnerIsInfrastructure() {
        Cli c = cli((dir, timeout) -> {
            throw new IOException("disk on fire");
        });

        assertEquals(Cli.EXIT_INFRA, c.run(new String[]{"test", "p"}));
        assertTrue(err().contains("disk on fire"));
    }
}
