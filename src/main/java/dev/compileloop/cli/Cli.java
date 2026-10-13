package dev.compileloop.cli;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.ReportWriter;
import dev.compileloop.build.TextReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Command-line front end. All behaviour lives here (with injected streams and
 * build action) so it can be tested without launching Maven or touching System.out.
 *
 * Exit codes: 0 build passed; 1 build failed (the project's code or tests);
 * 2 timed out; 3 could not run the build or read the run directory; 64 bad usage.
 */
public final class Cli {

    public static final int EXIT_PASS = 0;
    public static final int EXIT_FAIL = 1;
    public static final int EXIT_TIMEOUT = 2;
    public static final int EXIT_INFRA = 3;
    public static final int EXIT_USAGE = 64;

    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** What the CLI runs; the real one is BuildRunner::runMavenTests. */
    @FunctionalInterface
    public interface BuildAction {
        BuildResult run(Path projectDirectory, Duration timeout) throws IOException;
    }

    private final BuildAction action;
    private final PrintStream out;
    private final PrintStream err;

    public Cli(BuildAction action, PrintStream out, PrintStream err) {
        this.action = action;
        this.out = out;
        this.err = err;
    }

    public int run(String[] args) {
        if (args.length == 0) {
            err.println("Missing command.");
            err.println();
            printUsage(err);
            return EXIT_USAGE;
        }
        String command = args[0];
        if (command.equals("--help") || command.equals("-h") || command.equals("help")) {
            printUsage(out);
            return EXIT_PASS;
        }
        return switch (command) {
            case "test" -> runTest(args);
            case "report" -> runReport(args);
            default -> {
                err.println("Unknown command: " + command);
                err.println();
                printUsage(err);
                yield EXIT_USAGE;
            }
        };
    }

    // ---- test -------------------------------------------------------------

    private int runTest(String[] args) {
        Path project = null;
        boolean json = false;
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--timeout") || a.startsWith("--timeout=")) {
                String value;
                if (a.equals("--timeout")) {
                    if (i + 1 >= args.length) {
                        return usageError("--timeout needs a number of seconds.");
                    }
                    value = args[++i];
                } else {
                    value = a.substring("--timeout=".length());
                }
                try {
                    timeoutSeconds = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return usageError("--timeout must be a whole number of seconds, got: " + value);
                }
                if (timeoutSeconds <= 0) {
                    return usageError("--timeout must be greater than zero.");
                }
            } else if (a.equals("--json")) {
                json = true;
            } else if (a.startsWith("-")) {
                return usageError("Unknown option: " + a);
            } else if (project == null) {
                project = Path.of(a);
            } else {
                return usageError("Unexpected extra argument: " + a);
            }
        }
        if (project == null) {
            return usageError("Missing project directory.");
        }

        BuildResult result;
        try {
            result = action.run(project, Duration.ofSeconds(timeoutSeconds));
        } catch (IOException e) {
            err.println("Could not run the build: " + e.getMessage());
            return EXIT_INFRA;
        }
        return json ? reportJson(result) : reportText(result, timeoutSeconds);
    }

    private int reportJson(BuildResult r) {
        out.println(ReportWriter.toJson(r));
        return exitCodeFor(r);
    }

    private int reportText(BuildResult r, Integer timeoutSeconds) {
        out.print(TextReport.render(r, timeoutSeconds));
        return exitCodeFor(r);
    }

    private static int exitCodeFor(BuildResult r) {
        return switch (r.outcome()) {
            case PASS -> EXIT_PASS;
            case FAIL -> EXIT_FAIL;
            case TIMEOUT -> EXIT_TIMEOUT;
            default -> EXIT_INFRA;
        };
    }

    // ---- report -----------------------------------------------------------

    private int runReport(String[] args) {
        if (args.length < 2) {
            return usageError("Missing run directory.");
        }
        if (args.length > 2) {
            return usageError("Unexpected extra argument: " + args[2]);
        }
        Path dir = Path.of(args[1]);
        if (!Files.isDirectory(dir)) {
            return usageError("Not a directory: " + dir);
        }
        Path text = dir.resolve(ReportWriter.TEXT_FILE_NAME);
        Path json = dir.resolve(ReportWriter.FILE_NAME);
        try {
            if (Files.isRegularFile(text)) {
                out.print(Files.readString(text, StandardCharsets.UTF_8));
                return EXIT_PASS;
            }
            if (Files.isRegularFile(json)) {
                out.print(Files.readString(json, StandardCharsets.UTF_8));
                return EXIT_PASS;
            }
        } catch (IOException e) {
            err.println("Could not read report: " + e.getMessage());
            return EXIT_INFRA;
        }
        err.println("No report found in " + dir
                + " (looked for " + ReportWriter.TEXT_FILE_NAME + " and " + ReportWriter.FILE_NAME + ").");
        return EXIT_INFRA;
    }

    // ---- usage ------------------------------------------------------------

    private int usageError(String message) {
        err.println(message);
        err.println();
        printUsage(err);
        return EXIT_USAGE;
    }

    static void printUsage(PrintStream to) {
        to.println("CompileLoop - run a Maven project's tests and explain what failed.");
        to.println();
        to.println("Usage:");
        to.println("  compileloop test <project-dir> [--timeout <seconds>] [--json]");
        to.println("  compileloop report <run-dir>");
        to.println("  compileloop --help");
        to.println();
        to.println("Options for 'test':");
        to.println("  --timeout <seconds>   stop the build after this long (default " + DEFAULT_TIMEOUT_SECONDS + ")");
        to.println("  --json                print the JSON report to stdout instead of the text summary");
        to.println();
        to.println("Exit codes:");
        to.println("  0  build passed (or report rendered)");
        to.println("  1  build failed (compile errors or failing tests)");
        to.println("  2  build timed out");
        to.println("  3  the build could not be run, or the run directory could not be read");
        to.println("  64 bad command-line usage");
    }
}
