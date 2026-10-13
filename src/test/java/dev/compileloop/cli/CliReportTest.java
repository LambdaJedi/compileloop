package dev.compileloop.cli;



import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliReportTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    /** The report path never runs a build; if it does, the test fails loudly. */
    private Cli cli() {
        return new Cli((dir, timeout) -> {
            throw new AssertionError("report must not run a build");
        },
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsTextReportWhenPresent(@TempDir Path runDir) throws IOException {
        Files.writeString(runDir.resolve("report.txt"),
                "CompileLoop: mvn -B test\nResult: PASS (1.2s)\n");

        int code = cli().run(new String[]{"report", runDir.toString()});

        assertEquals(Cli.EXIT_PASS, code);
        assertTrue(out().contains("CompileLoop: mvn -B test"));
        assertTrue(out().contains("Result: PASS"));
    }

    @Test
    void fallsBackToJsonWhenNoTextReport(@TempDir Path runDir) throws IOException {
        Files.writeString(runDir.resolve("report.json"), "{\n  \"outcome\": \"FAIL\"\n}\n");

        int code = cli().run(new String[]{"report", runDir.toString()});

        assertEquals(Cli.EXIT_PASS, code);
        assertTrue(out().contains("\"outcome\": \"FAIL\""));
    }

    @Test
    void prefersTextOverJson(@TempDir Path runDir) throws IOException {
        Files.writeString(runDir.resolve("report.txt"), "TEXT VERSION\n");
        Files.writeString(runDir.resolve("report.json"), "JSON VERSION\n");

        int code = cli().run(new String[]{"report", runDir.toString()});

        assertEquals(Cli.EXIT_PASS, code);
        assertTrue(out().contains("TEXT VERSION"));
    }

    @Test
    void missingReportFilesIsInfraError(@TempDir Path runDir) {
        int code = cli().run(new String[]{"report", runDir.toString()});

        assertEquals(Cli.EXIT_INFRA, code);
        assertTrue(err().contains("No report found"));
    }

    @Test
    void missingArgumentIsUsageError() {
        int code = cli().run(new String[]{"report"});

        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(err().contains("Missing run directory"));
    }

    @Test
    void extraArgumentIsUsageError(@TempDir Path runDir) {
        int code = cli().run(new String[]{"report", runDir.toString(), "extra"});

        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(err().contains("Unexpected extra argument"));
    }

    @Test
    void nonDirectoryIsUsageError(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("not-a-dir");
        Files.writeString(file, "hello");

        int code = cli().run(new String[]{"report", file.toString()});

        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(err().contains("Not a directory"));
    }
}
