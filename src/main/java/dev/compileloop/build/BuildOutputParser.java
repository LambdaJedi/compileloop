package dev.compileloop.build;

import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;
import dev.compileloop.build.ParsedBuild.TestSummary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of {@code mvn -B test} (maven-compiler-plugin 3.x, surefire
 * 3.x) into structured compile errors and test failures. Pure function: no I/O.
 * Unrecognised lines are ignored for classification but {@code [ERROR]} lines
 * that match no known pattern are retained in {@link ParsedBuild#unrecognized()}.
 */
public final class BuildOutputParser {

    private static final Pattern COMPILE_ERROR =
            Pattern.compile("^\\[ERROR] (.+?\\.java):\\[(\\d+),(\\d+)] (.*)$");
    private static final Pattern COMPILE_BLOCK_START =
            Pattern.compile("^\\[ERROR] COMPILATION ERROR\\s*:?\\s*$");
    private static final Pattern COMPILE_BLOCK_END =
            Pattern.compile("^\\[INFO] \\d+ errors?$");
    private static final Pattern SUMMARY_CONTINUATION =
            Pattern.compile("^\\[ERROR] {3,}(\\S.*)$");

    private static final Pattern TEST_HEADER =
            Pattern.compile("^\\[ERROR] (.+?) -- Time elapsed: [\\d.,]+ s <<< (FAILURE|ERROR)!$");
    private static final Pattern STACK_FRAME =
            Pattern.compile("^\\s+at (\\S+)\\.[^.(\\s]+\\([^:)]+:(\\d+)\\)");
    private static final int RAW_STACK_FRAMES = 8;
    private static final Pattern STACK_START = Pattern.compile("^\\s+at \\S");

    private static final Pattern SUMMARY =
            Pattern.compile("^\\[(?:INFO|ERROR|WARNING)] Tests run: (\\d+), Failures: (\\d+), "
                    + "Errors: (\\d+), Skipped: (\\d+)$");

    /** Cap on retained unclassified lines; the log file remains the full record. */
    private static final int MAX_UNRECOGNIZED = 50;

    private BuildOutputParser() {
    }

    public static ParsedBuild parse(String output) {
        if (output == null || output.isEmpty()) {
            return ParsedBuild.empty();
        }
        String[] lines = output.split("\\r*\\n|\\r", -1);
        return new ParsedBuild(
                parseCompileErrors(lines),
                parseTestFailures(lines),
                parseSummary(lines),
                collectUnrecognized(lines));
    }

    // ---- unrecognised -----------------------------------------------------

    private static List<String> collectUnrecognized(String[] lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("[ERROR]")) {
                continue;
            }
            if (isRecognised(line) || isMavenBoilerplate(line)) {
                continue;
            }
            out.add(line);
            if (out.size() >= MAX_UNRECOGNIZED) {
                break;
            }
        }
        return out;
    }

    private static boolean isRecognised(String line) {
        return COMPILE_ERROR.matcher(line).matches()
                || COMPILE_BLOCK_START.matcher(line).matches()
                || COMPILE_BLOCK_END.matcher(line).matches()
                || SUMMARY_CONTINUATION.matcher(line).matches()
                || TEST_HEADER.matcher(line).matches()
                || SUMMARY.matcher(line).matches();
    }

    /** Maven's own failure guidance; not a diagnostic, so not worth reporting. */
    private static boolean isMavenBoilerplate(String line) {
        String rest = line.length() > 7 ? line.substring(7).strip() : "";
        if (rest.isEmpty()) {
            return true;
        }
        return rest.startsWith("Failed to execute goal")
                || rest.startsWith("-> [Help")
                || rest.startsWith("[Help")
                || rest.startsWith("To see the full stack trace")
                || rest.startsWith("Re-run Maven")
                || rest.startsWith("For more information")
                || rest.startsWith("See ");
    }

    // ---- compile errors ---------------------------------------------------

    private static List<CompileError> parseCompileErrors(String[] lines) {
        List<CompileError> fromBlocks = parseCompileBlocks(lines);
        return fromBlocks.isEmpty() ? parseCompileSummary(lines) : fromBlocks;
    }

    private static List<CompileError> parseCompileBlocks(String[] lines) {
        Set<CompileError> out = new LinkedHashSet<>();
        boolean inBlock = false;
        Pending cur = null;
        for (String line : lines) {
            if (COMPILE_BLOCK_START.matcher(line).matches()) {
                inBlock = true;
                cur = null;
                continue;
            }
            if (!inBlock) {
                continue;
            }
            if (COMPILE_BLOCK_END.matcher(line).matches()) {
                flush(cur, out);
                cur = null;
                inBlock = false;
                continue;
            }
            Matcher m = COMPILE_ERROR.matcher(line);
            if (m.matches()) {
                flush(cur, out);
                cur = Pending.of(m, line);
            } else if (cur != null && !line.startsWith("[") && !line.isBlank()) {
                cur.message.append('\n').append(line.trim());
                cur.raw.append('\n').append(line);
            }
        }
        flush(cur, out);
        return new ArrayList<>(out);
    }

    private static List<CompileError> parseCompileSummary(String[] lines) {
        Set<CompileError> out = new LinkedHashSet<>();
        Pending cur = null;
        for (String line : lines) {
            Matcher m = COMPILE_ERROR.matcher(line);
            if (m.matches()) {
                flush(cur, out);
                cur = Pending.of(m, line);
                continue;
            }
            Matcher c = SUMMARY_CONTINUATION.matcher(line);
            if (cur != null && c.matches()) {
                cur.message.append('\n').append(c.group(1).trim());
                cur.raw.append('\n').append(line);
            } else {
                flush(cur, out);
                cur = null;
            }
        }
        flush(cur, out);
        return new ArrayList<>(out);
    }

    private static void flush(Pending p, Set<CompileError> out) {
        if (p != null) {
            String msg = p.message.toString();
            out.add(new CompileError(p.file, p.line, p.column, msg,
                    DiagnosticCategory.forCompileMessage(msg), p.raw.toString()));
        }
    }

    private static final class Pending {
        final String file;
        final int line;
        final int column;
        final StringBuilder message;
        final StringBuilder raw;

        private Pending(String file, int line, int column, String firstLine, String rawLine) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.message = new StringBuilder(firstLine);
            this.raw = new StringBuilder(rawLine);
        }

        static Pending of(Matcher m, String rawLine) {
            return new Pending(normalizePath(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4), rawLine);
        }
    }

    /** "/C:/Users/x/F.java" -> "C:/Users/x/F.java"; other paths unchanged. */
    static String normalizePath(String path) {
        return path.replaceFirst("^/([A-Za-z]:/)", "$1").replace('\\', '/');
    }

    // ---- test failures ----------------------------------------------------

    private static List<TestFailure> parseTestFailures(String[] lines) {
        List<TestFailure> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            Matcher h = TEST_HEADER.matcher(lines[i]);
            if (!h.matches()) {
                i++;
                continue;
            }
            String[] id = splitTestId(h.group(1));
            String kind = h.group(2);

            StringBuilder message = new StringBuilder();
            StringBuilder raw = new StringBuilder(lines[i]);
            int rawFrames = 0;
            int failLine = -1;
            boolean inStack = false;
            i++;
            for (; i < lines.length; i++) {
                String l = lines[i];
                if (l.isBlank() || l.startsWith("[")) {
                    break;
                }
                if (STACK_START.matcher(l).find()) {
                    inStack = true;
                    if (rawFrames++ < RAW_STACK_FRAMES) {
                        raw.append('\n').append(l);
                    }
                    Matcher f = STACK_FRAME.matcher(l);
                    if (failLine < 0 && f.find() && isTestClass(f.group(1), id[0])) {
                        failLine = Integer.parseInt(f.group(2));
                    }
                } else if (!inStack) {
                    if (message.length() > 0) {
                        message.append('\n');
                    }
                    message.append(l.trim());
                    raw.append('\n').append(l);
                }
            }
            DiagnosticCategory category = kind.equals("FAILURE")
                    ? DiagnosticCategory.ASSERTION_FAILURE : DiagnosticCategory.TEST_ERROR;
            out.add(new TestFailure(id[0], id[1], kind, message.toString(), failLine,
                    category, raw.toString()));
        }
        return out;
    }

    private static String[] splitTestId(String id) {
        String bare = id.replaceFirst("[(\\[].*$", "");
        int dot = bare.lastIndexOf('.');
        if (dot < 0) {
            return new String[]{bare, ""};
        }
        String last = bare.substring(dot + 1);
        if (!last.isEmpty() && Character.isUpperCase(last.charAt(0))) {
            return new String[]{bare, ""};
        }
        return new String[]{bare.substring(0, dot), last};
    }

    private static boolean isTestClass(String frameClass, String testClass) {
        return frameClass.equals(testClass) || frameClass.startsWith(testClass + "$");
    }

    // ---- summary ----------------------------------------------------------

    private static Optional<TestSummary> parseSummary(String[] lines) {
        TestSummary last = null;
        for (String line : lines) {
            Matcher m = SUMMARY.matcher(line);
            if (m.matches()) {
                last = new TestSummary(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            }
        }
        return Optional.ofNullable(last);
    }
}
