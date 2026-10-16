package dev.compileloop;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.DiagnosticCategory;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.repair.MockProvider;
import dev.compileloop.repair.PatchPolicy;
import dev.compileloop.repair.RepairResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facade must not add behaviour: every assertion here checks that the
 * builder wires the same {@code RepairEngine.Config} the CLI would build,
 * and that missing required fields fail before any process is launched.
 */
class CompileLoopTest {

    @TempDir
    Path project;

    private static final String CALC =
            "package fx;\npublic class Calc {\n    public int add(int a, int b) {\n"
                    + "        return \"x\";\n    }\n}\n";
    private static final String CALC_TEST =
            "package fx;\nclass CalcTest {\n    void t() {\n        check();\n    }\n}\n";

    private static final String GOOD_DIFF = String.join("\n",
            "--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
            "@@ -2,4 +2,4 @@",
            " public class Calc {",
            "     public int add(int a, int b) {",
            "-        return \"x\";",
            "+        return a + b;",
            "     }") + "\n";

    private static String response(String summary, String diff) {
        return "{\"summary\":" + quote(summary) + ",\"diff\":" + quote(diff) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private void writeFixtures() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path main = Files.createDirectories(project.resolve("src/main/java/fx"));
        Path test = Files.createDirectories(project.resolve("src/test/java/fx"));
        Files.writeString(main.resolve("Calc.java"), CALC);
        Files.writeString(test.resolve("CalcTest.java"), CALC_TEST);
    }

    private static ParsedBuild errors(int n) {
        var list = new java.util.ArrayList<ParsedBuild.CompileError>();
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
        Files.writeString(log, "fake log\n");
        return new BuildResult("mvn -B test", outcome, outcome == BuildOutcome.PASS ? 0 : 1, false,
                Instant.now(), 5, log, "tail", parsed, dir, "r1", runDir);
    }

    /** Same fake build as RepairEngineTest: PASS iff Calc returns a + b. */
    private dev.compileloop.repair.RepairEngine.BuildExecutor fakeBuild() {
        return (dir, timeout) -> {
            String calc = Files.readString(dir.resolve("src/main/java/fx/Calc.java"));
            if (calc.contains("return a + b;")) {
                return result(dir, BuildOutcome.PASS, ParsedBuild.empty());
            }
            return result(dir, BuildOutcome.FAIL, errors(1));
        };
    }

    @Test
    void producesAVerifiedCandidateThroughTheFacade() throws IOException {
        writeFixtures();
        MockProvider ai = MockProvider.returning(response("return the sum", GOOD_DIFF));

        RepairResult r = CompileLoop.repair()
                .project(project)
                .provider(ai)
                .executor(fakeBuild())
                .run();

        assertEquals(RepairResult.Verdict.VERIFIED_CANDIDATE, r.verdict(), r.message());
        assertTrue(r.verifiedCandidate());
        assertTrue(Files.isRegularFile(r.patchFile()));
        assertEquals(CALC, Files.readString(project.resolve("src/main/java/fx/Calc.java")),
                "the working tree is untouched");
    }

    @Test
    void missingProjectFailsBeforeAnythingRuns() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CompileLoop.repair().provider(MockProvider.returning()).run());
        assertTrue(e.getMessage().contains("project"));
    }

    @Test
    void missingProviderFailsBeforeAnythingRuns() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CompileLoop.repair().project(project).run());
        assertTrue(e.getMessage().contains("provider"));
    }

    @Test
    void outOfRangeMaxAttemptsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CompileLoop.repair()
                .project(project).provider(MockProvider.returning())
                .maxAttempts(0).run());
        assertThrows(IllegalArgumentException.class, () -> CompileLoop.repair()
                .project(project).provider(MockProvider.returning())
                .maxAttempts(11).run());
    }

    @Test
    void defaultPolicyProtectsTestSources() throws IOException {
        writeFixtures();
        MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF));

        CompileLoop.repair()
                .project(project)
                .provider(ai)
                .executor(fakeBuild())
                .maxAttempts(1)
                .run();

        assertTrue(ai.receivedContexts().get(0).render().contains("Do not modify: src/test/java"),
                "the default policy must protect test sources");
    }

    @Test
    void allowTestChangesLiftsTheProtection() throws IOException {
        writeFixtures();
        MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF));

        CompileLoop.repair()
                .project(project)
                .provider(ai)
                .executor(fakeBuild())
                .allowTestChanges(true)
                .maxAttempts(1)
                .run();

        assertFalse(ai.receivedContexts().get(0).render().contains("Do not modify: src/test/java"),
                "allowTestChanges(true) must lift the protection");
    }

    @Test
    void customPolicyIsHonored() throws IOException {
        writeFixtures();
        MockProvider ai = MockProvider.returning(response("x", GOOD_DIFF));

        CompileLoop.repair()
                .project(project)
                .provider(ai)
                .executor(fakeBuild())
                .policy(PatchPolicy.defaults().withMaxFiles(2).withMaxChangedLines(10))
                .maxAttempts(1)
                .run();

        var ctx = ai.receivedContexts().get(0);
        assertTrue(ctx.render().contains("At most 2 files"), ctx.render());
        assertTrue(ctx.render().contains("10 changed lines"), ctx.render());
    }
}
