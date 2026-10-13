package dev.compileloop.repair;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.DiagnosticCategory;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.repair.RepairResult.Status;
import dev.compileloop.repair.RepairResult.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop is tested with a fake build that reads the files of whatever directory
 * it is given, so no Maven is needed, and a MockProvider with scripted answers.
 */
class RepairEngineTest {

    @TempDir
    Path project;

    private static final String CALC = "package fx;\npublic class Calc {\n    public int add(int a, int b) {\n"
            + "        return \"x\";\n    }\n}\n";
    private static final String CALC_TEST = "package fx;\nclass CalcTest {\n    void t() {\n        check();\n    }\n}\n";

    private static String diff(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static final String GOOD_DIFF = diff(
            "--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
            "@@ -2,4 +2,4 @@",
            " public class Calc {",
            "     public int add(int a, int b) {",
            "-        return \"x\";",
            "+        return a + b;",
            "     }");
    private static final String STILL_BROKEN_DIFF = diff(
            "--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
            "@@ -2,4 +2,4 @@",
            " public class Calc {",
            "     public int add(int a, int b) {",
            "-        return \"x\";",
            "+        return \"y\";",
            "     }");
    private static final String TEST_EDIT_DIFF = diff(
            "--- a/src/test/java/fx/CalcTest.java", "+++ b/src/test/java/fx/CalcTest.java",
            "@@ -2,4 +2,4 @@",
            " class CalcTest {",
            "     void t() {",
            "-        check();",
            "+        // WEAKENED",
            "     }");
    private static final String PROTECTED_DIFF = diff(
            "--- a/pom.xml", "+++ b/pom.xml", "@@ -1,1 +1,1 @@", "-<project/>", "+<project>x</project>");

    private final List<Path> directoriesBuilt = new ArrayList<>();
    private int builds;

    private static String response(String summary, String diff) {
        return "{\"summary\":" + Json.quote(summary) + ",\"diff\":" + Json.quote(diff) + "}";
    }

    private void write(String rel, String content) throws IOException {
        Path p = project.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws IOException {
        write("pom.xml", "<project/>\n");
        write("src/main/java/fx/Calc.java", CALC);
        write("src/test/java/fx/CalcTest.java", CALC_TEST);
    }

    private static ParsedBuild errors(int n) {
        List<ParsedBuild.CompileError> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new ParsedBuild.CompileError("src/main/java/fx/Calc.java", 4, 16,
                    "incompatible types: String cannot be converted to int",
                    DiagnosticCategory.TYPE_MISMATCH, "raw"));
        }
        return new ParsedBuild(list, List.of(), Optional.empty());
    }

    private static BuildResult result(Path dir, BuildOutcome outcome, ParsedBuild parsed) throws IOException {
        Path runDir = Files.createDirectories(dir.resolve("target/compileloop/runs/r1"));
        Path log = runDir.resolve("build.log");
        Files.writeString(log, "fake build log for " + outcome + "\n");
        return new BuildResult("mvn -B test", outcome, outcome == BuildOutcome.PASS ? 0 : 1, false,
                Instant.now(), 5, log, "tail", parsed, dir, "r1", runDir);
    }

    /** PASS iff Calc.java returns a + b (or the test file was weakened); FAIL otherwise. */
    private RepairEngine.BuildExecutor fakeBuild() {
        return (dir, timeout) -> {
            builds++;
            directoriesBuilt.add(dir);
            String calc = Files.readString(dir.resolve("src/main/java/fx/Calc.java"));
            String test = Files.readString(dir.resolve("src/test/java/fx/CalcTest.java"));
            if (calc.contains("return a + b;") || test.contains("WEAKENED")) {
                return result(dir, BuildOutcome.PASS, ParsedBuild.empty());
            }
            return result(dir, BuildOutcome.FAIL, errors(1));
        };
    }

    private RepairResult run(AIProvider provider) {
        return new RepairEngine(fakeBuild(), provider, RepairEngine.Config.defaults()).repair(project);
    }

    private RepairResult run(AIProvider provider, RepairEngine.Config config) {
        return new RepairEngine(fakeBuild(), provider, config).repair(project);
    }

    private void assertWorkingTreeUntouched() throws IOException {
        assertEquals(CALC, Files.readString(project.resolve("src/main/java/fx/Calc.java")));
        assertEquals(CALC_TEST, Files.readString(project.resolve("src/test/java/fx/CalcTest.java")));
        assertEquals("<project/>\n", Files.readString(project.resolve("pom.xml")));
    }

    // ---- outcomes ---------------------------------------------------------------------

    @Test
    void verifiesAGoodPatchInACopyAndNeverTouchesTheWorkingTree() throws Exception {
        MockProvider ai = MockProvider.returning(response("return the sum", GOOD_DIFF));

        RepairResult r = run(ai);

        assertEquals(Verdict.FIXED, r.verdict(), r.message());
        assertEquals(1, r.attempts().size());
        assertEquals(Status.VERIFIED, r.attempts().get(0).status());
        assertNotNull(r.patchFile());
        assertEquals(GOOD_DIFF, Files.readString(r.patchFile()));
        assertWorkingTreeUntouched();
        assertEquals(2, builds, "one baseline build and one build of the patched copy");
        assertEquals(project.toRealPath(), directoriesBuilt.get(0));
        assertFalse(directoriesBuilt.get(1).equals(project.toRealPath()), "the patched build ran in a copy");
        assertFalse(Files.exists(directoriesBuilt.get(1)), "the copy is deleted afterwards");
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void alreadyPassingNeedsNoModel() throws Exception {
        Files.writeString(project.resolve("src/main/java/fx/Calc.java"), CALC.replace("\"x\"", "a + b"));
        MockProvider ai = MockProvider.returning();

        RepairResult r = run(ai);

        assertEquals(Verdict.ALREADY_PASSING, r.verdict());
        assertEquals(0, ai.receivedContexts().size());
        assertEquals(1, builds);
    }

    @Test
    void anInfrastructureFailureIsNeverTreatedAsABrokenProject() throws Exception {
        for (BuildOutcome o : List.of(BuildOutcome.TIMEOUT, BuildOutcome.START_ERROR, BuildOutcome.INFRA_ERROR)) {
            MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF));
            RepairResult r = new RepairEngine((d, t) -> result(d, o, ParsedBuild.empty()), ai,
                    RepairEngine.Config.defaults()).repair(project);

            assertEquals(Verdict.INFRA_ERROR, r.verdict(), o.toString());
            assertEquals(0, ai.receivedContexts().size(), "the model must not be asked about " + o);
        }
    }

    @Test
    void aFailureWithNothingRecognisedIsNotSentToTheModel() throws Exception {
        MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF));

        RepairResult r = new RepairEngine((d, t) -> result(d, BuildOutcome.FAIL, ParsedBuild.empty()), ai,
                RepairEngine.Config.defaults()).repair(project);

        assertEquals(Verdict.INFRA_ERROR, r.verdict());
        assertTrue(r.message().contains("no compile errors or test failures were recognised"), r.message());
        assertEquals(0, ai.receivedContexts().size());
    }

    @Test
    void aBuildThatCannotBeStartedIsReported() {
        MockProvider ai = MockProvider.returning();

        RepairResult r = new RepairEngine((d, t) -> {
            throw new IOException("no mvn");
        }, ai, RepairEngine.Config.defaults()).repair(project);

        assertEquals(Verdict.INFRA_ERROR, r.verdict());
        assertTrue(r.message().contains("no mvn"));
    }

    @Test
    void aMissingProjectDirectoryIsReported() {
        RepairResult r = new RepairEngine(fakeBuild(), MockProvider.returning(), RepairEngine.Config.defaults())
                .repair(project.resolve("nope"));

        assertEquals(Verdict.INFRA_ERROR, r.verdict());
    }

    // ---- attempts ------------------------------------------------------------------------

    @Test
    void retriesAfterARejectedPatchAndTellsTheModelWhy() throws Exception {
        MockProvider ai = MockProvider.returning(
                response("edit the pom", PROTECTED_DIFF),
                response("return the sum", GOOD_DIFF));

        RepairResult r = run(ai);

        assertEquals(Verdict.FIXED, r.verdict());
        assertEquals(List.of(Status.REJECTED, Status.VERIFIED),
                r.attempts().stream().map(RepairResult.AttemptRecord::status).toList());
        assertTrue(r.attempts().get(0).detail().contains("FORBIDDEN_PATH"), r.attempts().get(0).detail());
        List<String> notes = ai.receivedContexts().get(1).notes();
        assertTrue(notes.stream().anyMatch(n -> n.contains("Earlier attempt 1 failed")), notes.toString());
        assertEquals(List.of(), ai.receivedContexts().get(0).notes().stream()
                .filter(n -> n.contains("Earlier attempt")).toList());
        assertWorkingTreeUntouched();
    }

    @Test
    void aPatchThatBuildsButStillFailsIsNotAFix() throws Exception {
        MockProvider ai = MockProvider.returning(
                response("try y", STILL_BROKEN_DIFF), response("try y again", STILL_BROKEN_DIFF),
                response("and again", STILL_BROKEN_DIFF));

        RepairResult r = run(ai);

        assertEquals(Verdict.NOT_FIXED, r.verdict());
        assertEquals(3, r.attempts().size());
        assertTrue(r.attempts().stream().allMatch(a -> a.status() == Status.STILL_FAILING));
        assertNull(r.patchFile());
        assertFalse(Files.exists(r.reportDirectory().resolve("fix.patch")));
        assertTrue(ai.receivedContexts().get(2).notes().stream().anyMatch(n -> n.contains("still fails")),
                ai.receivedContexts().get(2).notes().toString());
        assertWorkingTreeUntouched();
        for (Path d : directoriesBuilt.subList(1, directoriesBuilt.size())) {
            assertFalse(Files.exists(d), "every copy is deleted");
        }
    }

    @Test
    void stopsAtTheAttemptLimit() throws Exception {
        MockProvider ai = MockProvider.returning(
                response("1", STILL_BROKEN_DIFF), response("2", STILL_BROKEN_DIFF), response("3", STILL_BROKEN_DIFF));

        RepairResult r = run(ai, RepairEngine.Config.defaults().withMaxAttempts(2));

        assertEquals(2, r.attempts().size());
        assertEquals(2, ai.receivedContexts().size());
    }

    @Test
    void aPatchThatDoesNotApplyIsReportedAsSuch() throws Exception {
        String wrongContext = GOOD_DIFF.replace("-        return \"x\";", "-        return \"zzz\";");
        MockProvider ai = MockProvider.returning(response("bad context", wrongContext),
                response("return the sum", GOOD_DIFF));

        RepairResult r = run(ai);

        assertEquals(Status.DID_NOT_APPLY, r.attempts().get(0).status());
        assertTrue(r.attempts().get(0).detail().contains("did not apply"));
        assertEquals(Verdict.FIXED, r.verdict());
    }

    @Test
    void aMalformedAnswerIsRetried() throws Exception {
        MockProvider ai = MockProvider.returning("Sure! Here is a fix: ...", response("return the sum", GOOD_DIFF));

        RepairResult r = run(ai);

        assertEquals(Status.AI_ERROR, r.attempts().get(0).status());
        assertEquals(AIProviderException.Kind.MALFORMED_RESPONSE, r.attempts().get(0).aiErrorKind());
        assertEquals(Verdict.FIXED, r.verdict());
    }

    @Test
    void anUnavailableModelStopsImmediately() throws Exception {
        MockProvider ai = MockProvider.failing(AIProviderException.Kind.UNAVAILABLE, "connection refused");

        RepairResult r = run(ai);

        assertEquals(Verdict.AI_UNAVAILABLE, r.verdict());
        assertEquals(1, r.attempts().size(), "retrying an unreachable model is pointless");
        assertTrue(r.message().contains("connection refused"));
        assertEquals(1, builds, "no patched build was attempted");
    }

    @Test
    void aTimeoutIsRetriedButStillReportedAsUnavailableIfItKeepsHappening() throws Exception {
        MockProvider ai = MockProvider.failing(AIProviderException.Kind.TIMEOUT, "too slow");

        RepairResult r = run(ai, RepairEngine.Config.defaults().withMaxAttempts(2));

        assertEquals(2, r.attempts().size());
        assertEquals(Verdict.AI_UNAVAILABLE, r.verdict());
    }

    @Test
    void aContextThatDoesNotFitStopsImmediately() throws Exception {
        MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF)).withContextLimit(10);

        RepairResult r = run(ai);

        assertEquals(Verdict.AI_UNAVAILABLE, r.verdict());
        assertEquals(1, r.attempts().size());
    }

    @Test
    void aPatchedBuildThatCannotRunIsNotCountedAsAFailedFix() throws Exception {
        MockProvider ai = MockProvider.returning(response("return the sum", GOOD_DIFF));
        int[] calls = {0};

        RepairResult r = new RepairEngine((d, t) -> {
            calls[0]++;
            return calls[0] == 1 ? result(d, BuildOutcome.FAIL, errors(1))
                    : result(d, BuildOutcome.TIMEOUT, ParsedBuild.empty());
        }, ai, RepairEngine.Config.defaults().withMaxAttempts(1)).repair(project);

        assertEquals(Status.BUILD_NOT_RUNNABLE, r.attempts().get(0).status());
        assertEquals(Verdict.NOT_FIXED, r.verdict());
    }

    // ---- tests are protected --------------------------------------------------------------

    @Test
    void editingTestFilesIsRejectedByDefault() throws Exception {
        MockProvider ai = MockProvider.returning(response("weaken the test", TEST_EDIT_DIFF));

        RepairResult r = run(ai, RepairEngine.Config.defaults().withMaxAttempts(1));

        assertEquals(Verdict.NOT_FIXED, r.verdict());
        assertEquals(Status.REJECTED, r.attempts().get(0).status());
        assertTrue(r.attempts().get(0).validation().has(ValidationResult.Code.PROTECTED_PATH));
        assertWorkingTreeUntouched();
    }

    @Test
    void whenAllowedATestEditThatTurnsTheBuildGreenIsFlaggedForReview() throws Exception {
        MockProvider ai = MockProvider.returning(response("weaken the test", TEST_EDIT_DIFF));

        RepairResult r = run(ai, RepairEngine.Config.defaults().allowingTestChanges());

        assertEquals(Verdict.FIXED, r.verdict());
        assertTrue(r.attempts().get(0).modifiesTests());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("changes test files")), r.warnings().toString());
    }

    @Test
    void theModelIsToldTestsAreOffLimits() throws Exception {
        MockProvider ai = MockProvider.returning(response("return the sum", GOOD_DIFF));

        run(ai);

        assertTrue(ai.receivedContexts().get(0).render().contains("Do not modify: src/test/java"));
    }

    // ---- audit trail ------------------------------------------------------------------------

    @Test
    void writesAnAuditTrailForEveryAttempt() throws Exception {
        MockProvider ai = MockProvider.returning(
                response("edit the pom", PROTECTED_DIFF), response("return the sum", GOOD_DIFF));

        RepairResult r = run(ai);

        Path dir = r.reportDirectory();
        assertTrue(dir.startsWith(project.toRealPath().resolve("target/compileloop/repairs")));
        for (String f : List.of("repair.json", "fix.patch", "attempt-1/response.txt", "attempt-1/proposal.diff",
                "attempt-1/attempt.json", "attempt-2/response.txt", "attempt-2/attempt.json",
                "attempt-2/build.log")) {
            assertTrue(Files.isRegularFile(dir.resolve(f)), "missing " + f);
        }
        assertFalse(Files.exists(dir.resolve("attempt-1/build.log")), "a rejected patch is never built");
        assertEquals(response("edit the pom", PROTECTED_DIFF), Files.readString(dir.resolve("attempt-1/response.txt")),
                "the raw answer is kept exactly as received");
        assertTrue(Files.readString(dir.resolve("attempt-2/build.log")).contains("fake build log"));
    }

    @Test
    void theJsonReportIsValidAndSaysWhatHappened() throws Exception {
        MockProvider ai = MockProvider.returning(
                response("edit the pom", PROTECTED_DIFF), response("return the \"sum\"\u0007", GOOD_DIFF));

        RepairResult r = run(ai);

        Object root = Json.parse(Files.readString(r.reportDirectory().resolve("repair.json")), 20);
        Map<?, ?> m = (Map<?, ?>) root;
        assertEquals("FIXED", m.get("verdict"));
        assertEquals(1L, ((Number) m.get("schemaVersion")).longValue());
        List<?> attempts = (List<?>) m.get("attempts");
        assertEquals(2, attempts.size());
        Map<?, ?> first = (Map<?, ?>) attempts.get(0);
        assertEquals("REJECTED", first.get("status"));
        Map<?, ?> validation = (Map<?, ?>) first.get("validation");
        assertEquals(Boolean.FALSE, validation.get("accepted"));
        assertEquals("fix.patch", m.get("patchFile"));
        assertEquals("return the \"sum\"\u0007", ((Map<?, ?>) attempts.get(1)).get("summary"),
                "text round-trips through JSON escaping, control characters included");
        assertTrue(((String) m.get("note")).contains("never applied to your working tree"));
        Object one = Json.parse(Files.readString(r.reportDirectory().resolve("attempt-2/attempt.json")), 20);
        assertEquals("VERIFIED", ((Map<?, ?>) one).get("status"));
    }

    @Test
    void invalidConfigurationIsRefused() {
        RepairEngine.Config c = RepairEngine.Config.defaults();
        boolean threw = false;
        try {
            c.withMaxAttempts(0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
        threw = false;
        try {
            c.withBuildTimeout(Duration.ZERO);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }
}
