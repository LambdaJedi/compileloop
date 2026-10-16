package dev.compileloop.build;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the Docker build executor. Skipped entirely when the
 * Docker CLI or daemon is not available, so the suite stays green on machines
 * without Docker.
 *
 * <p>The image pulled is the default one ({@code maven:3.9-eclipse-temurin-17}).
 * First run on a fresh machine takes a minute or two; the test allows for that
 * with a generous timeout. Subsequent runs are fast because the Maven cache
 * lives in a named volume.
 */
class DockerBuildExecutorTest {

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerBuildExecutor.isAvailable(), "docker CLI or daemon is not available");
    }

    @TempDir
    Path tmp;

    private Path fixture(String name, String source) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>fixture</groupId>
                    <artifactId>fixture</artifactId>
                    <version>1.0</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                </project>
                """);
        Path dir = Files.createDirectories(root.resolve("src/main/java/fx"));
        Files.writeString(dir.resolve("Thing.java"), source);
        return root;
    }

    @Test
    @Timeout(600)
    void passingProjectIsPass() throws IOException {
        Path project = fixture("pass", """
                package fx;
                public class Thing {
                    public int value() { return 42; }
                }
                """);

        BuildResult r = new DockerBuildExecutor().run(project, Duration.ofMinutes(8));

        assertEquals(BuildOutcome.PASS, r.outcome(), r.outputTail());
        assertEquals(0, r.exitCode());
        assertFalse(r.timedOut());
        assertTrue(Files.isRegularFile(r.logPath()), "the build log is written");
    }

    @Test
    @Timeout(600)
    void compileErrorIsFail() throws IOException {
        Path project = fixture("fail", """
                package fx;
                public class Thing {
                    public int value() { return "not an int"; }
                }
                """);

        BuildResult r = new DockerBuildExecutor().run(project, Duration.ofMinutes(8));

        assertEquals(BuildOutcome.FAIL, r.outcome(), r.outputTail());
        assertTrue(r.parsed().hasCompileErrors(), "the compile error is parsed from the container output");
        assertTrue(r.parsed().compileErrors().get(0).file().contains("Thing.java"),
                r.parsed().compileErrors().get(0).file());
    }

    @Test
    void missingPomIsStartError() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));

        BuildResult r = new DockerBuildExecutor().run(empty, Duration.ofMinutes(1));

        assertEquals(BuildOutcome.START_ERROR, r.outcome());
        assertTrue(r.outputTail().contains("pom.xml"));
    }

    @Test
    @Timeout(600)
    void theOriginalDirectoryIsNeverMountedDirectly() throws IOException {
        Path project = fixture("untouched", """
                package fx;
                public class Thing {
                    public int value() { return 42; }
                }
                """);
        // Add a marker that a hostile build would try to overwrite.
        Path marker = project.resolve("src/main/java/fx/marker.txt");
        Files.writeString(marker, "untouched\n");

        new DockerBuildExecutor().run(project, Duration.ofMinutes(8));

        assertEquals("untouched\n", Files.readString(marker),
                "the executor must copy the project; the original is never the mount");
    }
}
