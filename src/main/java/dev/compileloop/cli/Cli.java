package dev.compileloop.cli;

import dev.compileloop.build.BuildOutcome;
import dev.compileloop.build.BuildResult;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;
import dev.compileloop.build.ReportWriter;
import dev.compileloop.repair.RepairResult;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
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

    /** What `repair` runs; the real one builds a provider and a RepairEngine. */
    @FunctionalInterface
    public interface RepairAction {
        RepairResult run(Path projectDirectory, RepairOptions options) throws IOException;
    }

    private final BuildAction action;
    private final RepairAction repairAction;
    private final PrintStream out;
    private final PrintStream err;

    public Cli(BuildAction action, PrintStream out, PrintStream err) {
        this(action, null, out, err);
    }

    public Cli(BuildAction action, RepairAction repairAction, PrintStream out, PrintStream err) {
        this.action = action;
        this.repairAction = repairAction;
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
        if (command.equals("repair")) {
            return runRepair(args);
        }
        if (command.equals("report")) {
            return runReport(args);
        }
        if (!command.equals("test")) {
            err.println("Unknown command: " + command);
            err.println();
            printUsage(err);
            return EXIT_USAGE;
        }

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
        return json ? reportJson(result) : report(result, timeoutSeconds);
    }

    // ---- repair -----------------------------------------------------------

    private int runRepair(String[] args) {
        if (repairAction == null) {
            err.println("The repair command is not available in this build.");
            return EXIT_USAGE;
        }
        Path project = null;
        String provider = RepairOptions.DEFAULT_PROVIDER;
        String model = null;
        String ollamaUrl = RepairOptions.DEFAULT_OLLAMA_URL;
        String openAiBaseUrl = null;
        String apiKey = null;
        String apiKeyEnv = null;
        Path responseFile = null;
        int attempts = RepairOptions.DEFAULT_ATTEMPTS;
        int buildTimeout = RepairOptions.DEFAULT_BUILD_TIMEOUT_SECONDS;
        int aiTimeout = RepairOptions.DEFAULT_AI_TIMEOUT_SECONDS;
        boolean allowTests = false;
        boolean json = false;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String name = a.contains("=") && a.startsWith("--") ? a.substring(0, a.indexOf('=')) : a;
            String inline = a.contains("=") && a.startsWith("--") ? a.substring(a.indexOf('=') + 1) : null;
            switch (name) {
                case "--provider", "--model", "--ollama-url", "--openai-base-url",
                     "--api-key", "--api-key-env",
                     "--response-file", "--attempts", "--timeout", "--ai-timeout" -> {
                    String value;
                    if (inline != null) {
                        value = inline;
                    } else if (i + 1 < args.length) {
                        value = args[++i];
                    } else {
                        return usageError(name + " needs a value.");
                    }
                    switch (name) {
                        case "--provider" -> provider = value;
                        case "--model" -> model = value;
                        case "--ollama-url" -> ollamaUrl = value;
                        case "--openai-base-url" -> openAiBaseUrl = value;
                        case "--api-key" -> apiKey = value;
                        case "--api-key-env" -> apiKeyEnv = value;
                        case "--response-file" -> responseFile = Path.of(value);
                        default -> {
                            int n;
                            try {
                                n = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                return usageError(name + " must be a whole number, got: " + value);
                            }
                            if (n <= 0) {
                                return usageError(name + " must be greater than zero.");
                            }
                            if (name.equals("--attempts")) {
                                if (n > 10) {
                                    return usageError("--attempts must be between 1 and 10.");
                                }
                                attempts = n;
                            } else if (name.equals("--timeout")) {
                                buildTimeout = n;
                            } else {
                                aiTimeout = n;
                            }
                        }
                    }
                }
                case "--allow-test-changes" -> allowTests = true;
                case "--json" -> json = true;
                default -> {
                    if (a.startsWith("-")) {
                        return usageError("Unknown option: " + a);
                    }
                    if (project != null) {
                        return usageError("Unexpected extra argument: " + a);
                    }
                    project = Path.of(a);
                }
            }
        }

        if (project == null) {
            return usageError("Missing project directory.");
        }
        if (!provider.equals(RepairOptions.PROVIDER_OLLAMA)
                && !provider.equals(RepairOptions.PROVIDER_OPENAI)) {
            return usageError("--provider must be '" + RepairOptions.PROVIDER_OLLAMA
                    + "' or '" + RepairOptions.PROVIDER_OPENAI + "', got: " + provider);
        }
        if (apiKey != null && apiKeyEnv != null) {
            return usageError("Use either --api-key or --api-key-env, not both.");
        }

        if (responseFile != null) {
            // The response file bypasses the provider entirely. Model, URL and
            // key flags are ignored; nothing else is required.
        } else if (provider.equals(RepairOptions.PROVIDER_OLLAMA)) {
            if (model == null) {
                return usageError("--model <name> is required for provider=ollama "
                        + "(or use --response-file for offline replay).");
            }
        } else {
            if (model == null) {
                return usageError("--model <name> is required for provider=openai.");
            }
            if (openAiBaseUrl == null) {
                return usageError("--openai-base-url <url> is required for provider=openai.");
            }
            if (apiKey == null && apiKeyEnv == null) {
                return usageError("provider=openai needs an API key: pass --api-key <key> "
                        + "or --api-key-env <VAR>.");
            }
        }

        RepairResult r;
        try {
            r = repairAction.run(project, new RepairOptions(provider, model, ollamaUrl,
                    openAiBaseUrl, apiKey, apiKeyEnv, responseFile, attempts,
                    buildTimeout, aiTimeout, allowTests));
        } catch (IOException | RuntimeException e) {
            err.println("Could not run the repair: " + e.getMessage());
            return EXIT_INFRA;
        }
        return json ? repairJson(r) : repairReport(r);
    }

    private int repairJson(RepairResult r) {
        Path report = r.reportDirectory() == null ? null : r.reportDirectory().resolve("repair.json");
        if (report != null && Files.isRegularFile(report)) {
            try {
                out.print(Files.readString(report));
            } catch (IOException e) {
                out.println("{\"verdict\": \"" + r.verdict() + "\"}");
            }
        } else {
            out.println("{\"verdict\": \"" + r.verdict() + "\"}");
        }
        return repairExit(r);
    }

    static int repairExit(RepairResult r) {
        return switch (r.verdict()) {
            case ALREADY_PASSING, VERIFIED_CANDIDATE -> EXIT_PASS;
            case NOT_FIXED, NO_PROGRESS -> EXIT_FAIL;
            case AI_UNAVAILABLE, INFRA_ERROR -> EXIT_INFRA;
        };
    }

    private int repairReport(RepairResult r) {
        out.println("CompileLoop repair");
        if (r.baseline() != null) {
            out.println("Baseline build: " + r.baseline().outcome() + " ("
                    + r.baseline().parsed().compileErrors().size() + " compile error(s), "
                    + r.baseline().parsed().testFailures().size() + " test failure(s))");
        }
        for (RepairResult.AttemptRecord a : r.attempts()) {
            out.println();
            out.println("Attempt " + a.number() + ": " + a.status() + " (" + seconds(a.durationMillis()) + ")");
            if (a.summary() != null && !a.summary().isBlank()) {
                out.println("  Model says: " + clean(a.summary()));
            }
            out.println("  " + clean(a.detail()));
        }
        out.println();
        out.println("Result: " + r.verdict());
        out.println(clean(r.message()));
        for (String w : r.warnings()) {
            out.println("WARNING: " + clean(w));
        }
        if (r.patchFile() != null) {
            out.println();
            out.println("Patch:  " + r.patchFile());
            out.println("Review it, then apply it yourself from the project folder:");
            out.println("  git apply \"" + r.patchFile() + "\"");
            out.println("CompileLoop never changes your working tree. The build ran in a temporary copy, but "
                    + "that copy is not a sandbox: it executed the patched code with your permissions.");
        }
        if (r.reportDirectory() != null) {
            out.println();
            out.println("Audit trail: " + r.reportDirectory());
        }
        return repairExit(r);
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
                out.print(Files.readString(text));
                return EXIT_PASS;
            }
            if (Files.isRegularFile(json)) {
                out.print(Files.readString(json));
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

    /** Model-written or log-derived text is untrusted: strip terminal control characters. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f && !(c >= 0x80 && c < 0xa0))) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    // ---- test reporting ---------------------------------------------------

    private int reportJson(BuildResult r) {
        out.println(ReportWriter.toJson(r));
        return switch (r.outcome()) {
            case PASS -> EXIT_PASS;
            case FAIL -> EXIT_FAIL;
            case TIMEOUT -> EXIT_TIMEOUT;
            default -> EXIT_INFRA;
        };
    }

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
        if (r.runDirectory() != null) {
            out.println();
            out.println("Run:    " + r.runId());
            if (r.logPath() != null) {
                out.println("Log:    " + r.logPath());
            }
            out.println("Report: " + r.runDirectory().resolve(ReportWriter.FILE_NAME));
        } else if (r.logPath() != null) {
            out.println();
            out.println("Log:    " + r.logPath());
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
        to.println("  compileloop test <project-dir> [--timeout <seconds>] [--json]");
        to.println("  compileloop repair <project-dir> [options]");
        to.println("  compileloop report <run-dir>");
        to.println("  compileloop --help");
        to.println();
        to.println("Options for 'test':");
        to.println("  --timeout <seconds>   stop the build after this long (default "
                + DEFAULT_TIMEOUT_SECONDS + ")");
        to.println("  --json                print the JSON report to stdout instead of the text summary");
        to.println();
        to.println("Options for 'repair':");
        to.println("  --provider <name>        ollama (default) or openai");
        to.println("  --model <name>           model name (required unless --response-file)");
        to.println("  --ollama-url <url>       Ollama endpoint (default "
                + RepairOptions.DEFAULT_OLLAMA_URL + ")");
        to.println("  --openai-base-url <url>  base URL of an OpenAI-compatible endpoint (provider=openai)");
        to.println("  --api-key <key>          API key (provider=openai; visible in the process list)");
        to.println("  --api-key-env <VAR>      read the API key from an environment variable (safer)");
        to.println("  --response-file <file>   replay a canned model answer instead of calling a model");
        to.println("  --attempts <n>           max repair attempts (default "
                + RepairOptions.DEFAULT_ATTEMPTS + ", max 10)");
        to.println("  --timeout <seconds>      build timeout per run (default "
                + RepairOptions.DEFAULT_BUILD_TIMEOUT_SECONDS + ")");
        to.println("  --ai-timeout <seconds>   model request timeout per attempt (default "
                + RepairOptions.DEFAULT_AI_TIMEOUT_SECONDS + ")");
        to.println("  --allow-test-changes     permit modifications under src/test/java");
        to.println("  --json                   print the JSON report instead of the text summary");
        to.println();
        to.println("The patch is only written to target/compileloop/repairs/<run>/fix.patch.");
        to.println("The build of the patched copy runs the patched code with your permissions.");
        to.println();
        to.println("Exit codes:");
        to.println("  0  build passed (or report rendered)");
        to.println("  1  build failed, repair unsuccessful, or no progress made");
        to.println("  2  build timed out");
        to.println("  3  the build could not be run, or the model was unreachable");
        to.println("  64 bad command-line usage");
    }
}