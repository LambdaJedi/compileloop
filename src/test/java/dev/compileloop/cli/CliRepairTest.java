package dev.compileloop.cli;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.DiagnosticCategory;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.repair.RepairResult;
import dev.compileloop.repair.RepairResult.AttemptRecord;
import dev.compileloop.repair.RepairResult.Status;
import dev.compileloop.repair.RepairResult.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRepairTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final AtomicReference<RepairOptions> seenOptions = new AtomicReference<>();
    private final AtomicReference<Path> seenPath = new AtomicReference<>();

    private Cli cli(RepairResult result) {
        return new Cli((d, t) -> {
            throw new AssertionError("the plain build must not run for `repair`");
        }, (dir, options) -> {
            seenPath.set(dir);
            seenOptions.set(options);
            return result;
        }, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    private static BuildResult baseline() {
        ParsedBuild parsed = new ParsedBuild(List.of(new ParsedBuild.CompileError("src/main/java/fx/Calc.java", 4, 16,
                "incompatible types", DiagnosticCategory.TYPE_MISMATCH, "raw")), List.of(), Optional.empty());
        return new BuildResult("mvn -B test", BuildOutcome.FAIL, 1, false, Instant.now(), 1000,
                Path.of("log"), "tail", parsed, Path.of("p"), "r", Path.of("rd"));
    }

    private static AttemptRecord attempt(int n, Status s, String summary, String detail) {
        return new AttemptRecord(n, s, 1500, summary, List.of(), "raw", "diff", null, null, null, null, null,
                false, detail);
    }

    private RepairResult result(Verdict v, Path patch, List<AttemptRecord> attempts, List<String> warnings) {
        return new RepairResult(v, "run1", tmp, baseline(), attempts, patch, warnings, "message for " + v);
    }

    @Test
    void parsesAllOptionsAndPassesThemOn() {
        int code = cli(result(Verdict.ALREADY_PASSING, null, List.of(), List.of())).run(new String[]{
                "repair", "proj", "--model", "qwen2.5-coder", "--ollama-url=http://host:1234", "--attempts", "2",
                "--timeout", "60", "--ai-timeout", "90", "--allow-test-changes"});

        assertEquals(Cli.EXIT_PASS, code, err());
        assertEquals(Path.of("proj"), seenPath.get());
        RepairOptions o = seenOptions.get();
        assertEquals("qwen2.5-coder", o.model());
        assertEquals("http://host:1234", o.ollamaUrl());
        assertEquals(2, o.attempts());
        assertEquals(60, o.buildTimeoutSeconds());
        assertEquals(90, o.aiTimeoutSeconds());
        assertTrue(o.allowTestChanges());
    }

    @Test
    void usesSafeDefaults() {
        cli(result(Verdict.ALREADY_PASSING, null, List.of(), List.of())).run(new String[]{"repair", "p", "--model", "m"});

        RepairOptions o = seenOptions.get();
        assertEquals(RepairOptions.DEFAULT_OLLAMA_URL, o.ollamaUrl());
        assertEquals(3, o.attempts());
        assertFalse(o.allowTestChanges(), "test files stay protected unless asked");
    }

    @Test
    void aResponseFileCanReplaceTheModel() {
        cli(result(Verdict.ALREADY_PASSING, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--response-file", "answer.json"});

        assertEquals(Path.of("answer.json"), seenOptions.get().responseFile());
        assertEquals(null, seenOptions.get().model());
    }

    @Test
    void rejectsBadUsage() {
        String[][] bad = {
                {"repair"},
                {"repair", "p"},
                {"repair", "--model", "m"},
                {"repair", "p", "q", "--model", "m"},
                {"repair", "p", "--model"},
                {"repair", "p", "--model", "m", "--response-file", "f"},
                {"repair", "p", "--model", "m", "--attempts", "0"},
                {"repair", "p", "--model", "m", "--attempts", "11"},
                {"repair", "p", "--model", "m", "--attempts", "many"},
                {"repair", "p", "--model", "m", "--timeout", "-5"},
                {"repair", "p", "--model", "m", "--bogus"},
        };
        for (String[] args : bad) {
            outBuf.reset();
            errBuf.reset();
            assertEquals(Cli.EXIT_USAGE, cli(result(Verdict.VERIFIED_CANDIDATE, null, List.of(), List.of())).run(args),
                    String.join(" ", args));
            assertTrue(err().length() > 0, String.join(" ", args));
            assertEquals(null, seenOptions.get() == null ? null : seenOptions.getAndSet(null),
                    "nothing may run on bad usage: " + String.join(" ", args));
        }
    }

    @Test
    void exitCodesFollowTheVerdict() {
        assertEquals(Cli.EXIT_PASS, cli(result(Verdict.VERIFIED_CANDIDATE, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m"}));
        assertEquals(Cli.EXIT_PASS, cli(result(Verdict.ALREADY_PASSING, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m"}));
        assertEquals(Cli.EXIT_FAIL, cli(result(Verdict.NOT_FIXED, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m"}));
        assertEquals(Cli.EXIT_INFRA, cli(result(Verdict.AI_UNAVAILABLE, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m"}));
        assertEquals(Cli.EXIT_INFRA, cli(result(Verdict.INFRA_ERROR, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m"}));
    }

    @Test
    void aFixIsReportedWithTheApplyCommandAndTheHonestSandboxWarning() {
        Path patch = tmp.resolve("fix.patch");
        RepairResult r = result(Verdict.VERIFIED_CANDIDATE, patch,
                List.of(attempt(1, Status.VERIFIED, "return the sum", "The build passes with this patch.")),
                List.of("This patch changes test files. Check that the tests were fixed, not weakened."));

        cli(r).run(new String[]{"repair", "p", "--model", "m"});

        String o = out();
        assertTrue(o.contains("Attempt 1: VERIFIED"), o);
        assertTrue(o.contains("Model says: return the sum"), o);
        assertTrue(o.contains("Result: VERIFIED_CANDIDATE"), o);
        assertTrue(o.contains("git apply"), o);
        assertTrue(o.contains(patch.toString()), o);
        assertTrue(o.contains("WARNING: This patch changes test files"), o);
        assertTrue(o.contains("never changes your working tree"), o);
        assertTrue(o.contains("not a sandbox"), o);
        assertTrue(o.contains("Audit trail:"), o);
    }

    @Test
    void failuresDoNotMentionApplyingAnything() {
        RepairResult r = result(Verdict.NOT_FIXED, null,
                List.of(attempt(1, Status.REJECTED, null, "The patch was rejected: FORBIDDEN_PATH")), List.of());

        cli(r).run(new String[]{"repair", "p", "--model", "m"});

        assertTrue(out().contains("Attempt 1: REJECTED"));
        assertFalse(out().contains("git apply"));
    }

    @Test
    void modelWrittenTextCannotInjectTerminalControlCharacters() {
        RepairResult r = result(Verdict.VERIFIED_CANDIDATE, null,
                List.of(attempt(1, Status.VERIFIED, "\u001b[2J\u001b]0;pwned\u0007looks fine", "ok\u001b[31m")),
                List.of("warn\u001b[0m"));

        cli(r).run(new String[]{"repair", "p", "--model", "m"});

        assertFalse(out().indexOf('\u001b') >= 0, "escape characters must be stripped");
        assertFalse(out().indexOf('\u0007') >= 0);
        assertTrue(out().contains("looks fine"));
    }

    @Test
    void jsonModeEchoesTheReportFile() throws IOException {
        Files.writeString(tmp.resolve("repair.json"), "{\"verdict\": \"NOT_FIXED\"}\n");

        int code = cli(result(Verdict.NOT_FIXED, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m", "--json"});

        assertEquals(Cli.EXIT_FAIL, code);
        assertEquals("{\"verdict\": \"NOT_FIXED\"}\n", out());
    }

    @Test
    void jsonModeStillAnswersWhenNoReportFileExists() {
        cli(result(Verdict.INFRA_ERROR, null, List.of(), List.of()))
                .run(new String[]{"repair", "p", "--model", "m", "--json"});

        assertTrue(out().contains("\"verdict\": \"INFRA_ERROR\""), out());
    }

    @Test
    void aFailingActionIsReportedAsAnInfrastructureProblem() {
        Cli c = new Cli((d, t) -> null, (d, o) -> {
            throw new IOException("boom");
        }, new PrintStream(outBuf, true, StandardCharsets.UTF_8), new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        int code = c.run(new String[]{"repair", "p", "--model", "m"});

        assertEquals(Cli.EXIT_INFRA, code);
        assertTrue(err().contains("boom"));
    }

    @Test
    void helpMentionsRepairAndTheSafetyNotes() {
        cli(result(Verdict.VERIFIED_CANDIDATE, null, List.of(), List.of())).run(new String[]{"--help"});

        assertTrue(out().contains("compileloop repair"));
        assertTrue(out().contains("--allow-test-changes"));
        assertTrue(out().contains("with your permissions"));
        assertNotNull(out());
    }

    @Test
    void plainCliWithoutARepairActionRefusesTheCommand() {
        Cli c = new Cli((d, t) -> null, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        assertEquals(Cli.EXIT_USAGE, c.run(new String[]{"repair", "p", "--model", "m"}));
    }
}
