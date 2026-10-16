package dev.compileloop.build;

import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;

import java.util.Locale;

/**
 * Human-readable rendering of a {@link BuildResult}. Pure function: no I/O.
 * The same text is printed to the console and stored alongside the JSON report
 * as {@code report.txt}, so a stored run can be reviewed later with
 * {@code compileloop report <run-dir>}.
 */
public final class TextReport {

    static final int UNPARSED_TAIL_LINES = 20;

    private TextReport() {
    }

    public static String render(BuildResult r) {
        return render(r, null);
    }

    public static String render(BuildResult r, Integer timeoutSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("CompileLoop: ").append(r.commandLabel()).append('\n');
        switch (r.outcome()) {
            case PASS -> {
                sb.append("Result: PASS (").append(seconds(r.durationMillis())).append(")\n");
                appendSummary(sb, r.parsed());
                appendArtifacts(sb, r);
            }
            case FAIL -> {
                sb.append("Result: FAIL, exit code ").append(r.exitCode())
                        .append(" (").append(seconds(r.durationMillis())).append(")\n");
                appendFailures(sb, r);
                appendArtifacts(sb, r);
            }
            case TIMEOUT -> {
                sb.append("Result: TIMEOUT.");
                if (timeoutSeconds != null) {
                    sb.append(" The build was stopped after ").append(timeoutSeconds).append("s.");
                } else {
                    sb.append(" The build was stopped at the time limit.");
                }
                sb.append('\n');
                appendArtifacts(sb, r);
            }
            default -> {
                sb.append("Result: ").append(r.outcome())
                        .append(". The build could not be run or completed; ")
                        .append("this says nothing about the project's code.\n");
                String detail = r.outputTail() == null ? "" : r.outputTail().strip();
                if (!detail.isEmpty()) {
                    sb.append(detail).append('\n');
                }
                appendArtifacts(sb, r);
            }
        }
        return sb.toString();
    }

    private static void appendFailures(StringBuilder sb, BuildResult r) {
        ParsedBuild p = r.parsed();
        if (p.hasCompileErrors()) {
            sb.append('\n');
            sb.append("Compile errors (").append(p.compileErrors().size()).append("):\n");
            for (CompileError e : p.compileErrors()) {
                sb.append("  ").append(e.file()).append(':').append(e.line())
                        .append(':').append(e.column()).append('\n');
                appendIndented(sb, e.message());
            }
        }
        if (p.hasTestFailures()) {
            sb.append('\n');
            sb.append("Test failures (").append(p.testFailures().size()).append("):\n");
            for (TestFailure f : p.testFailures()) {
                sb.append("  ").append(testId(f)).append(" [").append(f.kind()).append("]\n");
                appendIndented(sb, f.message());
            }
        }
        appendSummary(sb, p);
        if (!p.hasCompileErrors() && !p.hasTestFailures()) {
            sb.append('\n');
            sb.append("The build failed but no structured errors were recognised. ")
                    .append("Last lines of output:\n");
            String[] lines = (r.outputTail() == null ? "" : r.outputTail()).strip().split("\\R");
            int from = Math.max(0, lines.length - UNPARSED_TAIL_LINES);
            for (int i = from; i < lines.length; i++) {
                sb.append("  ").append(lines[i]).append('\n');
            }
        }
        if (p.hasUnrecognizedErrors()) {
            sb.append('\n');
            sb.append("Also present: ").append(p.unrecognized().size())
                    .append(" unclassified [ERROR] line(s) - see the log for details.\n");
        }
    }

    private static void appendSummary(StringBuilder sb, ParsedBuild p) {
        p.testSummary().ifPresent(s -> {
            sb.append('\n');
            sb.append("Tests: ").append(s.run()).append(" run, ")
                    .append(s.failures()).append(" failed, ")
                    .append(s.errors()).append(" errors, ")
                    .append(s.skipped()).append(" skipped\n");
        });
    }

    private static void appendArtifacts(StringBuilder sb, BuildResult r) {
        if (r.runDirectory() != null) {
            sb.append('\n');
            sb.append("Run:    ").append(r.runId()).append('\n');
            if (r.logPath() != null) {
                sb.append("Log:    ").append(r.logPath()).append('\n');
            }
            sb.append("Report: ").append(r.runDirectory().resolve(ReportWriter.FILE_NAME)).append('\n');
            sb.append("Text:   ").append(r.runDirectory().resolve(ReportWriter.TEXT_FILE_NAME)).append('\n');
        } else if (r.logPath() != null) {
            sb.append('\n');
            sb.append("Log:    ").append(r.logPath()).append('\n');
        }
    }

    private static void appendIndented(StringBuilder sb, String message) {
        for (String line : message.split("\\R")) {
            sb.append("    ").append(line).append('\n');
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
}
