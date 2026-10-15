package dev.compileloop.repair;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole stack with the real BuildRunner, parser, context builder, validator,
 * applier and engine. Only the model is scripted (MockProvider).
 *
 * The first test uses a fake Maven wrapper script (POSIX only) so it is quick and
 * needs no network. The second runs the real Maven on a tiny throw-away project,
 * like BuildRunnerTest does.
 */
class RepairEndToEndTest {

    @TempDir
    Path tmp;

    private static final String CALC = "package fx;\npublic class Calc {\n    public int add(int a, int b) {\n"
            + "        return \"x\";\n    }\n}\n";

    private static final String GOOD_DIFF = String.join("\n",
            "--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
            "@@ -2,4 +2,4 @@",
            " public class Calc {",
            "     public int add(int a, int b) {",
            "-        return \"x\";",
            "+        return a + b;",
            "     }") + "\n";

    private static String response(String diff) {
        return "{\"summary\":\"return the sum\",\"diff\":" + Json.quote(diff) + ",\"assumptions\":[]}";
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @Test
    @Timeout(120)
    void repairsABrokenProjectUsingAFakeMavenWrapper()
            throws IOException, UnifiedDiff.DiffFormatException {
        assumeTrue(!windows(), "the fake wrapper is a shell script");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.createDirectories(project.resolve("src/main/java/fx"));
        Files.writeString(project.resolve("src/main/java/fx/Calc.java"), CALC);
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                if grep -q 'return "x";' src/main/java/fx/Calc.java; then
                cat <<EOT
                [INFO] Scanning for projects...
                [INFO] --- compiler:3.15.0:compile (default-compile) @ fx ---
                [INFO] -------------------------------------------------------------
                [ERROR] COMPILATION ERROR :
                [INFO] -------------------------------------------------------------
                [ERROR] $PWD/src/main/java/fx/Calc.java:[4,16] incompatible types: java.lang.String cannot be converted to int
                [INFO] 1 error
                [INFO] -------------------------------------------------------------
                [INFO] BUILD FAILURE
                EOT
                exit 1
                fi
                echo "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0"
                echo "[INFO] BUILD SUCCESS"
                exit 0
                """.replace("\n                ", "\n").stripLeading());
        assumeTrue(mvnw.toFile().setExecutable(true), "cannot mark the script executable");
        MockProvider ai = MockProvider.returning(response(GOOD_DIFF));

        RepairResult r = new RepairEngine(new BuildRunner()::runMavenTests, ai, RepairEngine.Config.defaults())
                .repair(project);

        assertEquals(RepairResult.Verdict.VERIFIED_CANDIDATE, r.verdict(), r.message() + " " + r.attempts());
        assertEquals(BuildOutcome.FAIL, r.baseline().outcome());
        assertEquals(1, r.baseline().parsed().compileErrors().size());
        List<String> shown = ai.receivedContexts().get(0).files().stream().map(RepairContext.SourceFile::path).toList();
        assertEquals(List.of("src/main/java/fx/Calc.java"), shown, "the broken file was shown to the model");
        assertTrue(ai.receivedContexts().get(0).render().contains("incompatible types"));
        // fix.patch is the NORMALIZED rendering (UnifiedDiffWriter adds a
        // `diff --git` header and recomputes hunk counts). Compare parsed
        // patches, not raw text, so a cosmetic change to the writer does not
        // fail this test while a semantic regression still does.
        String written = Files.readString(r.patchFile());
        assertEquals(UnifiedDiff.parse(GOOD_DIFF), UnifiedDiff.parse(written),
                "fix.patch should express the same patches as the model's diff");
        assertTrue(written.contains("diff --git "), "normalized output keeps a git header");
        assertEquals(CALC, Files.readString(project.resolve("src/main/java/fx/Calc.java")),
                "the user's own file is unchanged");
    }

    @Test
    @Timeout(300)
    void repairsABrokenProjectWithRealMaven() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("real"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>fixture</groupId>
                    <artifactId>fixture</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.5.2</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);
        Files.createDirectories(project.resolve("src/main/java/fx"));
        Files.writeString(project.resolve("src/main/java/fx/Calc.java"), CALC);
        MockProvider ai = MockProvider.returning(response(GOOD_DIFF));

        RepairResult r = new RepairEngine(new BuildRunner()::runMavenTests, ai,
                RepairEngine.Config.defaults().withBuildTimeout(Duration.ofMinutes(3))).repair(project);

        assertEquals(RepairResult.Verdict.VERIFIED_CANDIDATE, r.verdict(), r.message() + " " + r.attempts());
        assertEquals(1, r.baseline().parsed().compileErrors().size(), r.baseline().outputTail());
        assertEquals(CALC, Files.readString(project.resolve("src/main/java/fx/Calc.java")));
        assertTrue(Files.isRegularFile(r.reportDirectory().resolve("attempt-1/build.log")));
    }
}