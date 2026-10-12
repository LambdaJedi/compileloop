package dev.compileloop.cli;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Command-line front end. All behaviour lives here (with injected streams and
 * build action) so it can be tested without launching Maven or touching System.out.
 *
 * Exit codes: 0 build passed; 1 build failed (the project's code or tests);
 * 2 timed out; 3 could not run the build (infrastructure); 64 bad usage.
 */
public final class Cli {

    public static final int EXIT_PASS = 0;
    public static final int EXIT_FAIL = 1;
    public static final int EXIT_TIMEOUT = 2;
    public static final int EXIT_INFRA = 3;
    public static final int EXIT_USAGE = 64;

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int UNPARSED_TAIL_LINES = 20;

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
        if (!command.equals("test")) {
            err.println("Unknown command: " + command);
            err.println();
            printUsage(err);
            return EXIT_USAGE;
        }

        Path project = null;
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
        return report(result, timeoutSeconds);
    }

    // ---- reporting --------------------------------------------------------

    private int report(BuildResult r, int timeoutSeconds) {
        out.println("CompileLoop: " + r.commandLabel());
        switch (r.outcome()) {
            case PASS -> {
                out.println("Result: PASS (" + seconds(r.durationMillis()) + ")");
                printSummary(r.parsed());
                printLog(r);
                return EXIT_PASS;
            }
            case FAIL -> {
                out.println("Result: FAIL, exit code " + r.exitCode()
                        + " (" + seconds(r.durationMillis()) + ")");
                printFailures(r);
                printLog(r);
                return EXIT_FAIL;
            }
            case TIMEOUT -> {
                out.println("Result: TIMEOUT. The build was stopped after " + timeoutSeconds + "s.");
                printLog(r);
                return EXIT_TIMEOUT;
            }
            default -> {
                // START_ERROR, INFRA_ERROR, CANCELLED: not the project's fault.
                out.println("Result: " + r.outcome() + ". The build could not be run or completed; "
                        + "this says nothing about the project's code.");
                String detail = r.outputTail() == null ? "" : r.outputTail().strip();
                if (!detail.isEmpty()) {
                    out.println(detail);
                }
                printLog(r);
                return EXIT_INFRA;
            }
        }
    }

    private void printFailures(BuildResult r) {
        ParsedBuild p = r.parsed();
        if (p.hasCompileErrors()) {
            out.println();
            out.println("Compile errors (" + p.compileErrors().size() + "):");
            for (CompileError e : p.compileErrors()) {
                out.println("  " + e.file() + ":" + e.line() + ":" + e.column());
                printIndented(e.message());
            }
        }
        if (p.hasTestFailures()) {
            out.println();
            out.println("Test failures (" + p.testFailures().size() + "):");
            for (TestFailure f : p.testFailures()) {
                out.println("  " + testId(f) + " [" + f.kind() + "]");
                printIndented(f.message());
            }
        }
        printSummary(p);
        if (!p.hasCompileErrors() && !p.hasTestFailures()) {
            out.println();
            out.println("The build failed but no structured errors were recognised. "
                    + "Last lines of output:");
            String[] lines = (r.outputTail() == null ? "" : r.outputTail()).strip().split("\\R");
            int from = Math.max(0, lines.length - UNPARSED_TAIL_LINES);
            for (int i = from; i < lines.length; i++) {
                out.println("  " + lines[i]);
            }
        }
    }

    private void printSummary(ParsedBuild p) {
        p.testSummary().ifPresent(s -> {
            out.println();
            out.println("Tests: " + s.run() + " run, " + s.failures() + " failed, "
                    + s.errors() + " errors, " + s.skipped() + " skipped");
        });
    }

    private void printLog(BuildResult r) {
        if (r.logPath() != null) {
            out.println();
            out.println("Full log: " + r.logPath());
        }
    }

    private void printIndented(String message) {
        for (String line : message.split("\\R")) {
            out.println("    " + line);
        }
    }

    private static String testId(TestFailure f) {
        StringBuilder sb = new StringBuilder(f.testClass());
        if (!f.testMethod().isEmpty()) {
            sb.append('.').append(f.testMethod());
        }
        if (f.line() >= 0) {
            sb.append(':').append(f.line());
        }
        return sb.toString();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

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
        to.println("  compileloop test <project-dir> [--timeout <seconds>]");
        to.println("  compileloop --help");
        to.println();
        to.println("Options:");
        to.println("  --timeout <seconds>   stop the build after this long (default " + DEFAULT_TIMEOUT_SECONDS + ")");
        to.println();
        to.println("Exit codes:");
        to.println("  0  build passed");
        to.println("  1  build failed (compile errors or failing tests)");
        to.println("  2  build timed out");
        to.println("  3  the build could not be run (e.g. Maven not found)");
        to.println("  64 bad command-line usage");
    }
}
