package dev.compileloop.build;

import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the machine-readable report for a run. No JSON library: the report is
 * small and its shape is fixed, so a tiny serializer keeps the project free of
 * dependencies.
 *
 * The report states only what was observed. A failed build with nothing
 * recognised is reported as exactly that, never as "no problems".
 */
public final class ReportWriter {

    public static final String FILE_NAME = "report.json";
    private static final int DETAIL_MAX_CHARS = 2000;

    private ReportWriter() {
    }

    /** Writes report.json into the run directory. No-op if there is no run directory. */
    public static void write(BuildResult r) throws IOException {
        if (r.runDirectory() == null) {
            return;
        }
        Files.writeString(r.runDirectory().resolve(FILE_NAME), toJson(r) + "\n", StandardCharsets.UTF_8);
    }

    public static String toJson(BuildResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("runId", r.runId());
        m.put("command", r.commandLabel());
        m.put("projectDirectory", r.projectDirectory() == null ? null : r.projectDirectory().toString());
        m.put("startedAt", r.startedAt() == null ? null : r.startedAt().toString());
        m.put("durationMillis", r.durationMillis());
        m.put("outcome", r.outcome().name());
        m.put("exitCode", r.exitCode());
        m.put("timedOut", r.timedOut());
        m.put("infrastructureFailure", r.infrastructureFailure());
        m.put("logFile", r.logPath() == null ? null : r.logPath().getFileName().toString());

        ParsedBuild p = r.parsed() == null ? ParsedBuild.empty() : r.parsed();
        List<Object> compile = new ArrayList<>();
        for (CompileError e : p.compileErrors()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("category", e.category().name());
            o.put("file", e.file());
            o.put("line", e.line());
            o.put("column", e.column());
            o.put("message", e.message());
            o.put("rawExcerpt", e.rawExcerpt());
            compile.add(o);
        }
        m.put("compileErrors", compile);

        List<Object> tests = new ArrayList<>();
        for (TestFailure f : p.testFailures()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("category", f.category().name());
            o.put("kind", f.kind());
            o.put("testClass", f.testClass());
            o.put("testMethod", f.testMethod());
            o.put("line", f.line() >= 0 ? (Object) f.line() : null);
            o.put("message", f.message());
            o.put("rawExcerpt", f.rawExcerpt());
            tests.add(o);
        }
        m.put("testFailures", tests);

        Object summary = null;
        if (p.testSummary().isPresent()) {
            ParsedBuild.TestSummary s = p.testSummary().get();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("run", s.run());
            o.put("failures", s.failures());
            o.put("errors", s.errors());
            o.put("skipped", s.skipped());
            summary = o;
        }
        m.put("testSummary", summary);

        List<Object> notes = new ArrayList<>();
        if (r.outcome() == BuildOutcome.FAIL && !p.hasCompileErrors() && !p.hasTestFailures()) {
            notes.add("The build failed but no structured diagnostics were recognised; "
                    + "see the log file for the raw output.");
        }
        if (r.infrastructureFailure()) {
            notes.add("The build could not be run or completed; this says nothing about the "
                    + "project's code.");
        }
        if (r.outcome() == BuildOutcome.TIMEOUT) {
            notes.add("The build was stopped at the time limit; diagnostics are not available.");
        }
        m.put("notes", notes);

        if (r.infrastructureFailure() && r.outputTail() != null && !r.outputTail().isBlank()) {
            String d = r.outputTail().strip();
            m.put("detail", d.length() > DETAIL_MAX_CHARS ? d.substring(0, DETAIL_MAX_CHARS) : d);
        }

        StringBuilder sb = new StringBuilder();
        value(sb, m, 0);
        return sb.toString();
    }

    // ---- minimal JSON serializer -----------------------------------------

    @SuppressWarnings("unchecked")
    private static void value(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                pad(sb, indent + 1);
                string(sb, e.getKey());
                sb.append(": ");
                value(sb, e.getValue(), indent + 1);
                sb.append(++n < map.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                value(sb, list.get(i), indent + 1);
                sb.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + v.getClass());
        }
    }

    private static void pad(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
