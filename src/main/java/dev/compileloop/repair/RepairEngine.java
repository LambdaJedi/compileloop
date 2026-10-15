package dev.compileloop.repair;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.BuildRunner;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.repair.RepairResult.AttemptRecord;
import dev.compileloop.repair.RepairResult.Status;
import dev.compileloop.repair.RepairResult.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The repair loop. For each attempt it asks the provider for a proposal, checks
 * it with PatchValidator, applies it to a fresh disposable copy of the project,
 * and runs the build in that copy. Only a passing build in the copy counts as a
 * fix, and even then the patch is only written to the report folder: the user's
 * own tree is never modified.
 *
 * Every attempt keeps the model's raw answer, the diff, the validation result and
 * the build log under {@code target/compileloop/repairs/<runId>/}, written as it
 * happens, so a crash still leaves an audit trail.
 *
 * <p>The loop stops early when an attempt is not making progress: the model
 * repeats the same patch, or the diagnostic signature does not change between
 * consecutive still-failing attempts.
 */
public final class RepairEngine {

    /** Runs the build in a directory; the real one is BuildRunner::runMavenTests. */
    @FunctionalInterface
    public interface BuildExecutor {
        BuildResult run(Path projectDirectory, Duration timeout) throws IOException;
    }

    public record Config(int maxAttempts, Duration buildTimeout, PatchPolicy policy,
                         ContextBuilder.Limits contextLimits, Workspace.Limits workspaceLimits) {
        public Config {
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("attempts must be between 1 and 10");
            }
            if (buildTimeout.isZero() || buildTimeout.isNegative()) {
                throw new IllegalArgumentException("the build timeout must be positive");
            }
        }

        /** Tests are protected by default: a "fix" that edits the tests proves nothing. */
        public static Config defaults() {
            return new Config(3, Duration.ofSeconds(300),
                    PatchPolicy.defaults().withProtectedPaths(List.of("src/test/java")),
                    ContextBuilder.Limits.defaults(), Workspace.Limits.defaults());
        }

        public Config withMaxAttempts(int v) {
            return new Config(v, buildTimeout, policy, contextLimits, workspaceLimits);
        }

        public Config withBuildTimeout(Duration v) {
            return new Config(maxAttempts, v, policy, contextLimits, workspaceLimits);
        }

        public Config withPolicy(PatchPolicy v) {
            return new Config(maxAttempts, buildTimeout, v, contextLimits, workspaceLimits);
        }

        public Config allowingTestChanges() {
            return withPolicy(policy.withProtectedPaths(
                    policy.protectedPaths().stream().filter(p -> !p.startsWith("src/test")).toList()));
        }
    }

    private static final int FEEDBACK_ITEMS = 3;
    private static final int FEEDBACK_CHARS = 300;

    private final BuildExecutor executor;
    private final AIProvider provider;
    private final Config config;

    public RepairEngine(BuildExecutor executor, AIProvider provider, Config config) {
        this.executor = executor;
        this.provider = provider;
        this.config = config;
    }

    public RepairResult repair(Path projectDirectory) {
        Path project;
        try {
            project = projectDirectory.toRealPath();
        } catch (IOException e) {
            return infra(null, null, null, "The project directory cannot be read: " + e.getMessage());
        }
        Instant started = Instant.now();
        String runId = BuildRunner.newRunId(started);
        Path dir = project.resolve("target").resolve("compileloop").resolve("repairs").resolve(runId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return infra(runId, null, null, "The report folder cannot be created (" + e.getMessage()
                    + "); refusing to repair without an audit trail.");
        }
        List<String> warnings = new ArrayList<>();

        // 1. baseline build, in the user's own tree (like `compileloop test`)
        BuildResult baseline;
        try {
            baseline = executor.run(project, config.buildTimeout());
        } catch (IOException e) {
            return finish(RepairResult.Verdict.INFRA_ERROR, runId, dir, null, List.of(), null, warnings,
                    "The build could not be run: " + e.getMessage(), started);
        }
        if (baseline.passed()) {
            return finish(Verdict.ALREADY_PASSING, runId, dir, baseline, List.of(), null, warnings,
                    "The build already passes; there is nothing to repair.", started);
        }
        if (baseline.outcome() != BuildOutcome.FAIL) {
            return finish(Verdict.INFRA_ERROR, runId, dir, baseline, List.of(), null, warnings,
                    "The build did not run to a normal result (" + baseline.outcome()
                            + "), so there is nothing reliable to repair.", started);
        }
        ParsedBuild diagnostics = baseline.parsed();
        if (!diagnostics.hasCompileErrors() && !diagnostics.hasTestFailures()) {
            return finish(Verdict.INFRA_ERROR, runId, dir, baseline, List.of(), null, warnings,
                    "The build failed but no compile errors or test failures were recognised (for example a "
                            + "dependency could not be downloaded). CompileLoop only repairs recognised Java "
                            + "compile errors and test failures.", started);
        }

        // 2. attempts
        List<AttemptRecord> attempts = new ArrayList<>();
        List<String> feedback = new ArrayList<>();
        Set<String> seenPatches = new HashSet<>();
        String lastDiagnosticSig = null;
        String stopReason = null;
        Path fix = null;
        for (int n = 1; n <= config.maxAttempts(); n++) {
            AttemptRecord a;
            try {
                a = attempt(n, project, diagnostics, feedback, dir, warnings);
            } catch (IOException e) {
                return finish(Verdict.INFRA_ERROR, runId, dir, baseline, attempts, null, warnings,
                        "Attempt " + n + " could not be carried out: " + e.getMessage(), started);
            }
            attempts.add(a);
            writeAttemptFiles(dir, a);

            if (a.status() == Status.VERIFIED) {
                fix = dir.resolve("fix.patch");
                try {
                    // Write a NORMALIZED patch: canonical headers with the counts
                    // the parser recomputed from the body. The model's raw diff is
                    // preserved separately in attempt-N/proposal.diff for audit.
                    String normalized = UnifiedDiffWriter.write(a.validation().files());
                    Files.writeString(fix, normalized, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    warnings.add("The fix was verified but fix.patch could not be written: " + e.getMessage());
                    fix = null;
                }
                if (a.modifiesTests()) {
                    warnings.add("This patch changes test files. Check that the tests were fixed, not weakened.");
                }
                break;
            }

            // Stop condition: repeated identical patch. A model that returns the
            // same diff again is stuck; a third identical attempt is a waste.
            if (a.diff() != null && !seenPatches.add(diffFingerprint(a.diff()))) {
                stopReason = "Stopped after " + n + " of " + config.maxAttempts()
                        + " attempts: the model produced the same patch as an earlier attempt; "
                        + "retrying would not help.";
                break;
            }

            // Stop condition: no new information between attempts. Two consecutive
            // still-failing attempts with the same diagnostic signature mean the
            // feedback loop is dead.
            if (a.status() == Status.STILL_FAILING && a.build() != null) {
                String sig = diagnosticSignature(a.build().parsed());
                if (sig.equals(lastDiagnosticSig)) {
                    stopReason = "Stopped after " + n + " of " + config.maxAttempts()
                            + " attempts: the build still fails with the same diagnostics as the "
                            + "previous attempt; retrying would not help.";
                    break;
                }
                lastDiagnosticSig = sig;
            }

            if (a.status() == Status.AI_ERROR && (a.aiErrorKind() == AIProviderException.Kind.UNAVAILABLE
                    || a.aiErrorKind() == AIProviderException.Kind.CONTEXT_OVERFLOW)) {
                break; // retrying cannot help
            }
            feedback.add(describeForRetry(a));
        }

        AttemptRecord last = attempts.get(attempts.size() - 1);
        if (last.status() == Status.VERIFIED) {
            return finish(Verdict.VERIFIED_CANDIDATE, runId, dir, baseline, attempts, fix, warnings,
                    "Attempt " + last.number() + " produced a patch whose build passes in a disposable copy. "
                            + "Your working tree is unchanged; review fix.patch and apply it yourself.",
                    started);
        }
        if (stopReason != null) {
            return finish(Verdict.NO_PROGRESS, runId, dir, baseline, attempts, null, warnings, stopReason, started);
        }
        boolean allAiDown = attempts.stream().allMatch(x -> x.status() == Status.AI_ERROR
                && (x.aiErrorKind() == AIProviderException.Kind.UNAVAILABLE
                || x.aiErrorKind() == AIProviderException.Kind.CONTEXT_OVERFLOW
                || x.aiErrorKind() == AIProviderException.Kind.TIMEOUT));
        if (allAiDown) {
            return finish(Verdict.AI_UNAVAILABLE, runId, dir, baseline, attempts, null, warnings,
                    "The model could not be used: " + last.aiErrorMessage(), started);
        }
        return finish(Verdict.NOT_FIXED, runId, dir, baseline, attempts, null, warnings,
                "No attempt produced a verified fix (" + attempts.size() + " tried).", started);
    }

    // ---- one attempt -------------------------------------------------------------------

    private AttemptRecord attempt(int n, Path project, ParsedBuild diagnostics, List<String> feedback,
                                  Path reportDir, List<String> warnings) throws IOException {
        long t0 = System.nanoTime();
        RepairContext context = ContextBuilder.build(project, diagnostics, config.policy(), config.contextLimits());
        if (!feedback.isEmpty()) {
            context = context.withExtraNotes(feedback);
        }

        PatchProposal proposal;
        try {
            proposal = provider.proposeRepair(context);
        } catch (AIProviderException e) {
            return new AttemptRecord(n, Status.AI_ERROR, millisSince(t0), null, List.of(), null, null,
                    e.kind(), e.getMessage(), null, null, null, false, "The model did not give a usable answer: "
                    + e.getMessage());
        }

        try (Workspace ws = Workspace.copyOf(project, config.workspaceLimits())) {
            if (!ws.skipped().isEmpty() && warnings.isEmpty()) {
                warnings.add("Links or special files were not copied into the workspace: " + ws.skipped());
            }
            ValidationResult validation = PatchValidator.validate(proposal, config.policy(), ws.root());
            if (!validation.accepted()) {
                return record(n, Status.REJECTED, t0, proposal, validation, null, null, false,
                        "The patch was rejected: " + firstViolations(validation));
            }
            PatchApplier.ApplyResult applied = PatchApplier.apply(validation.files(), ws.root());
            boolean touchesTests = applied.changes().stream().anyMatch(c -> c.path().startsWith("src/test/"));
            if (!applied.applied()) {
                return record(n, Status.DID_NOT_APPLY, t0, proposal, validation, applied, null, touchesTests,
                        "The patch did not apply: " + String.join("; ", applied.errors()));
            }
            BuildResult build = executor.run(ws.root(), config.buildTimeout());
            copyLog(build, reportDir.resolve("attempt-" + n));
            if (build.passed()) {
                return record(n, Status.VERIFIED, t0, proposal, validation, applied, build, touchesTests,
                        "The build passes with this patch.");
            }
            if (build.outcome() != BuildOutcome.FAIL) {
                return record(n, Status.BUILD_NOT_RUNNABLE, t0, proposal, validation, applied, build, touchesTests,
                        "The build of the patched copy did not run to a normal result (" + build.outcome() + ").");
            }
            return record(n, Status.STILL_FAILING, t0, proposal, validation, applied, build, touchesTests,
                    "The patch applied, but the build still fails: "
                            + build.parsed().compileErrors().size() + " compile error(s), "
                            + build.parsed().testFailures().size() + " test failure(s).");
        }
    }

    private static AttemptRecord record(int n, Status s, long t0, PatchProposal p, ValidationResult v,
                                        PatchApplier.ApplyResult a, BuildResult b, boolean tests, String detail) {
        return new AttemptRecord(n, s, millisSince(t0), p.summary(), p.assumptions(), p.rawResponse(), p.diff(),
                null, null, v, a, b, tests, detail);
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String firstViolations(ValidationResult v) {
        List<String> parts = new ArrayList<>();
        for (ValidationResult.Violation x : v.violations()) {
            if (parts.size() >= FEEDBACK_ITEMS) {
                parts.add("and " + (v.violations().size() - FEEDBACK_ITEMS) + " more");
                break;
            }
            parts.add(x.toString());
        }
        return String.join("; ", parts);
    }

    /** What the next attempt is told about this one. Short and factual. */
    private static String describeForRetry(AttemptRecord a) {
        StringBuilder sb = new StringBuilder("Earlier attempt ").append(a.number()).append(" failed. ")
                .append(clip(a.detail()));
        if (a.status() == Status.STILL_FAILING && a.build() != null) {
            int shown = 0;
            for (ParsedBuild.CompileError e : a.build().parsed().compileErrors()) {
                if (shown++ >= FEEDBACK_ITEMS) {
                    break;
                }
                sb.append(" | ").append(e.file()).append(':').append(e.line()).append(' ')
                        .append(clip(e.message().replace('\n', ' ')));
            }
            for (ParsedBuild.TestFailure f : a.build().parsed().testFailures()) {
                if (shown++ >= FEEDBACK_ITEMS) {
                    break;
                }
                sb.append(" | ").append(f.testClass()).append('.').append(f.testMethod()).append(' ')
                        .append(clip(f.message().replace('\n', ' ')));
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > FEEDBACK_CHARS ? s.substring(0, FEEDBACK_CHARS) + "..." : s;
    }

    // ---- stop-condition helpers -------------------------------------------------------

    /**
     * A stable fingerprint of a diff, used to detect "the model said the same
     * thing again". Line endings are normalised so CRLF vs LF does not count as
     * a new attempt. {@code String.hashCode} is sufficient here: a collision
     * only causes an early stop, never a safety failure.
     */
    static String diffFingerprint(String diff) {
        String normalized = diff.replace("\r\n", "\n").replace('\r', '\n');
        return Integer.toHexString(normalized.hashCode());
    }

    /**
     * A stable description of what is wrong with a build: file, line, category
     * and the first line of every message, plus the test summary. Two attempts
     * with the same signature are not making progress.
     */
    static String diagnosticSignature(ParsedBuild p) {
        StringBuilder sb = new StringBuilder();
        for (ParsedBuild.CompileError e : p.compileErrors()) {
            sb.append("C:").append(e.file()).append(':').append(e.line()).append(':')
                    .append(e.category()).append(':').append(firstLine(e.message())).append('\n');
        }
        for (ParsedBuild.TestFailure f : p.testFailures()) {
            sb.append("T:").append(f.testClass()).append('.').append(f.testMethod()).append(':')
                    .append(f.kind()).append(':').append(firstLine(f.message())).append('\n');
        }
        p.testSummary().ifPresent(s -> sb.append("S:").append(s.run()).append('/')
                .append(s.failures()).append('/').append(s.errors()).append('/')
                .append(s.skipped()).append('\n'));
        return sb.toString();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    // ---- files ---------------------------------------------------------------------------

    private static void copyLog(BuildResult b, Path attemptDir) {
        if (b == null || b.logPath() == null) {
            return;
        }
        try {
            Files.createDirectories(attemptDir);
            Files.copy(b.logPath(), attemptDir.resolve("build.log"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // the log is a convenience copy; the report still records the outcome
        }
    }

    private static void writeAttemptFiles(Path dir, AttemptRecord a) {
        Path d = dir.resolve("attempt-" + a.number());
        try {
            Files.createDirectories(d);
            if (a.rawResponse() != null) {
                Files.writeString(d.resolve("response.txt"), a.rawResponse(), StandardCharsets.UTF_8);
            }
            if (a.diff() != null) {
                Files.writeString(d.resolve("proposal.diff"), a.diff(), StandardCharsets.UTF_8);
            }
            Files.writeString(d.resolve("attempt.json"), RepairReport.attemptJson(a), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // surfaced by the missing files; the loop itself must not die on a report error
        }
    }

    private RepairResult finish(Verdict verdict, String runId, Path dir, BuildResult baseline,
                                List<AttemptRecord> attempts, Path fix, List<String> warnings,
                                String message, Instant started) {
        RepairResult r = new RepairResult(verdict, runId, dir, baseline, attempts, fix, warnings, message);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("maxAttempts", config.maxAttempts());
        cfg.put("buildTimeoutSeconds", config.buildTimeout().toSeconds());
        cfg.put("allowedRoots", config.policy().allowedRoots());
        cfg.put("allowedExtensions", config.policy().allowedExtensions());
        cfg.put("protectedPaths", config.policy().protectedPaths());
        cfg.put("maxFiles", config.policy().maxFiles());
        cfg.put("maxChangedLines", config.policy().maxChangedLines());
        try {
            Files.writeString(dir.resolve("repair.json"), RepairReport.resultJson(r, started, cfg),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            List<String> w = new ArrayList<>(warnings);
            w.add("repair.json could not be written: " + e.getMessage());
            return new RepairResult(verdict, runId, dir, baseline, attempts, fix, w, message);
        }
        return r;
    }

    private static RepairResult infra(String runId, Path dir, BuildResult baseline, String message) {
        return new RepairResult(Verdict.INFRA_ERROR, runId, dir, baseline, List.of(), null, List.of(), message);
    }
}