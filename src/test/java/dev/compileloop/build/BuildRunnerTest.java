package dev.compileloop.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests: each one builds a tiny throwaway Maven project in a temp
 * directory and runs the real Maven against it. They need mvn (or a wrapper)
 * on PATH, and the plugin/JUnit versions below must already be in ~/.m2 (they
 * match this project's pom, so they are after your first build).
 */
class BuildRunnerTest {

    @TempDir
    Path tmp;

    // ---- fixtures ---------------------------------------------------------

    private static final String FIXTURE_POM = """
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
            """;

    private Path fixture(String name, String testBody) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Files.writeString(root.resolve("pom.xml"), FIXTURE_POM);
        Path testDir = Files.createDirectories(root.resolve("src/test/java/fx"));
        Files.writeString(testDir.resolve("FxTest.java"), """
                package fx;
                import org.junit.jupiter.api.Test;
                class FxTest {
                    @Test
                    void t() throws Exception {
                %s
                    }
                }
                """.formatted(testBody));
        return root;
    }

    // ---- tests ------------------------------------------------------------

    @Test
    @Timeout(180)
    void passingProjectIsPass() throws IOException {
        Path project = fixture("pass", "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1);");

        BuildResult r = new BuildRunner().runMavenTests(project);

        assertEquals(BuildOutcome.PASS, r.outcome(), r.outputTail());
        assertEquals(0, r.exitCode());
        assertTrue(r.passed());
        assertFalse(r.infrastructureFailure());
        assertNotNull(r.logPath());
        assertTrue(Files.isRegularFile(r.logPath()));
        assertTrue(r.outputTail().contains("BUILD SUCCESS"));
        assertEquals(1, r.parsed().testSummary().orElseThrow().run());
        assertFalse(r.parsed().hasCompileErrors());
        assertFalse(r.parsed().hasTestFailures());

        // Per-run artifacts: target/compileloop/runs/<runId>/{build.log,report.json}
        assertNotNull(r.runId());
        assertTrue(r.runId().matches("\\d{8}T\\d{6}Z-[0-9a-f]{6}"), r.runId());
        assertEquals(r.runDirectory().getFileName().toString(), r.runId());
        assertEquals(r.runDirectory().resolve("build.log"), r.logPath());
        Path report = r.runDirectory().resolve("report.json");
        assertTrue(Files.isRegularFile(report));
        String json = Files.readString(report);
        assertTrue(json.contains("\"outcome\": \"PASS\""));
        assertTrue(json.contains(r.runId()));
    }

    @Test
    void directoryWithoutPomIsStartError() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("no-pom"));

        BuildResult r = new BuildRunner().runMavenTests(empty);

        assertEquals(BuildOutcome.START_ERROR, r.outcome());
        assertTrue(r.outputTail().contains("pom.xml"));
        assertTrue(r.infrastructureFailure());
        assertEquals(null, r.runDirectory(), "no artifacts for a build that never started");
    }

    @Test
    void runIdsAreWellFormedAndDistinct() {
        // Uniqueness is enforced by atomic directory creation, not by the random suffix.
        String id = BuildRunner.newRunId(java.time.Instant.parse("2026-10-12T11:55:00Z"));

        assertTrue(id.startsWith("20261012T115500Z-"), id);
        assertTrue(id.matches("\\d{8}T\\d{6}Z-[0-9a-f]{6}"), id);
    }

    @Test
    @Timeout(180)
    void failingTestIsFail() throws IOException {
        Path project = fixture("failtest", "org.junit.jupiter.api.Assertions.fail(\"boom\");");

        BuildResult r = new BuildRunner().runMavenTests(project);

        assertEquals(BuildOutcome.FAIL, r.outcome(), r.outputTail());
        assertTrue(r.exitCode() != 0);
        assertFalse(r.infrastructureFailure());
        assertTrue(r.outputTail().contains("boom"));
        assertEquals(1, r.parsed().testFailures().size());
        assertTrue(r.parsed().testFailures().get(0).message().contains("boom"));
        assertEquals("fx.FxTest", r.parsed().testFailures().get(0).testClass());
    }

    @Test
    @Timeout(180)
    void compileErrorIsFailNotInfrastructure() throws IOException {
        Path project = fixture("compileerr", "int x = \"not an int\";");

        BuildResult r = new BuildRunner().runMavenTests(project);

        assertEquals(BuildOutcome.FAIL, r.outcome(), r.outputTail());
        assertFalse(r.infrastructureFailure());
        assertTrue(r.outputTail().contains("COMPILATION ERROR"));
        assertTrue(r.parsed().hasCompileErrors(), "structured errors should be attached");
        assertTrue(r.parsed().compileErrors().get(0).file().endsWith("FxTest.java"));
        assertTrue(r.parsed().compileErrors().get(0).message().contains("incompatible types"));
    }

    @Test
    void invalidPathIsStartError() throws IOException {
        BuildResult r = new BuildRunner().runMavenTests(tmp.resolve("does-not-exist"));

        assertEquals(BuildOutcome.START_ERROR, r.outcome());
        assertTrue(r.infrastructureFailure());
        assertFalse(r.passed());
        assertTrue(r.outputTail().contains("Not a directory"));
        assertFalse(r.parsed().hasCompileErrors());
    }

    @Test
    @Timeout(120)
    void shortTimeoutKillsTheBuild() throws IOException {
        Path project = fixture("sleepy", "Thread.sleep(120_000);");

        long begin = System.nanoTime();
        BuildResult r = new BuildRunner().runMavenTests(project, Duration.ofSeconds(8));
        long elapsedSec = Duration.ofNanos(System.nanoTime() - begin).toSeconds();

        assertEquals(BuildOutcome.TIMEOUT, r.outcome());
        assertTrue(r.timedOut());
        assertTrue(elapsedSec < 40, "runner should return shortly after the timeout, took " + elapsedSec + "s");
    }

    @Test
    @Timeout(240)
    void largeOutputDoesNotDeadlockAndTailIsBounded() throws IOException {
        // ~8 MB of console output: far more than any OS pipe buffer.
        Path project = fixture("chatty", """
                        String line = "x".repeat(99);
                        for (int i = 0; i < 80_000; i++) {
                            System.out.println(line);
                        }
                """);

        BuildResult r = new BuildRunner().runMavenTests(project, Duration.ofMinutes(3));

        assertEquals(BuildOutcome.PASS, r.outcome(), "build should finish, not hang on a full pipe");
        assertTrue(Files.size(r.logPath()) > 4_000_000, "full output goes to the log file");
        assertTrue(r.outputTail().length() <= 256 * 1024, "in-memory tail must stay bounded");
        assertTrue(r.outputTail().contains("BUILD SUCCESS"), "tail keeps the end of the output");
    }

    @Test
    void projectWrapperIsPreferredOverPath() throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper = Files.writeString(tmp.resolve(windows ? "mvnw.cmd" : "mvnw"), "");

        Optional<Path> resolved = BuildRunner.resolveMavenExecutable(tmp);

        assertEquals(Optional.of(wrapper), resolved);
    }

    @Test
    void pathSearchSurvivesQuotedAndInvalidEntries() throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path exe = Files.writeString(bin.resolve("mvn-test-exe"), "");
        String sep = java.io.File.pathSeparator;
        // quoted entry, blank entry, an entry with an illegal NUL character, then the real one
        String path = "\"" + bin + "\"" + sep + sep + "bad\0dir" + sep + "/definitely/not/here";

        assertEquals(Optional.of(exe), BuildRunner.findOnPath("mvn-test-exe", path));
        assertEquals(Optional.empty(), BuildRunner.findOnPath("mvn-test-exe", "bad\0dir" + sep + "/nope"));
        assertEquals(Optional.empty(), BuildRunner.findOnPath("mvn-test-exe", null));
    }
}
