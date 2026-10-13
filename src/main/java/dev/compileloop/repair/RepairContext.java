package dev.compileloop.repair;

import dev.compileloop.build.ParsedBuild;

import java.util.List;

/**
 * Everything a provider is allowed to see, prepared and bounded by the engine.
 * Providers never read the project themselves.
 */
public record RepairContext(
        String language,
        String buildSystem,
        ParsedBuild diagnostics,
        List<SourceFile> files,
        PatchPolicy policy,
        List<String> notes,
        String acceptance
) {
    /** {@code path} is project-relative with '/' separators. */
    public record SourceFile(String path, String content, boolean truncated) {
    }

    public RepairContext {
        files = List.copyOf(files);
        notes = List.copyOf(notes);
    }

    /** A copy with extra notes appended (used to tell the model why earlier attempts failed). */
    public RepairContext withExtraNotes(List<String> extra) {
        List<String> all = new java.util.ArrayList<>(notes);
        all.addAll(extra);
        return new RepairContext(language, buildSystem, diagnostics, files, policy, all, acceptance);
    }

    private static final int MAX_MESSAGE_CHARS = 2000;

    /** The prompt text. Deterministic: the same context always renders the same text. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are proposing a minimal repair for a failing ")
                .append(buildSystem).append(' ').append(language).append(" project.\n\n");

        sb.append("## Response format (exact)\n");
        sb.append("Reply with exactly one JSON object and nothing else. ")
                .append("No markdown fences, no commentary before or after.\n\n");
        sb.append("{\"summary\": \"<one sentence>\", \"diff\": \"<unified diff>\", ")
                .append("\"assumptions\": [\"<string>\"]}\n\n");
        sb.append("The value of \"diff\" MUST be a complete unified diff. ")
                .append("It MUST begin with the two header lines `--- a/<path>` and `+++ b/<path>` ")
                .append("before any `@@` hunk. It MUST NOT begin with `@@`.\n\n");
        sb.append("Example of a well-formed response (do not copy the code, only the shape):\n");
        sb.append("{\"summary\": \"Return a + b instead of a string.\", ")
                .append("\"diff\": \"--- a/src/main/java/com/example/Widget.java\\n")
                .append("+++ b/src/main/java/com/example/Widget.java\\n")
                .append("@@ -10,3 +10,3 @@\\n")
                .append("     public int add(int a, int b) {\\n")
                .append("-        return \\\"x\\\";\\n")
                .append("+        return a + b;\\n")
                .append("     }\\n\", ")
                .append("\"assumptions\": []}\n\n");

        sb.append("## Rules\n");
        sb.append("- The diff MUST start with `--- a/...` and `+++ b/...`. ")
                .append("A diff that starts with `@@` is invalid and will be rejected.\n");
        sb.append("- In the `@@ -oldStart,oldCount +newStart,newCount @@` header, ")
                .append("oldCount is the number of context and `-` lines in the hunk, ")
                .append("and newCount is the number of context and `+` lines. ")
                .append("A wrong count causes the patch to be rejected.\n");
        sb.append("- Prefer the smallest correct change: fix method bodies before changing method signatures. ")
                .append("Do not change a public method's return type or parameter list unless the diagnostic ")
                .append("itself requires it.\n");
        sb.append("- Only modify files under: ").append(String.join(", ", policy.allowedRoots())).append('\n');
        sb.append("- Only modify files of type: ").append(String.join(", ", policy.allowedExtensions())).append('\n');
        sb.append("- At most ").append(policy.maxFiles()).append(" files and ")
                .append(policy.maxChangedLines()).append(" changed lines.\n");
        sb.append("- No binary changes, renames, copies, or file mode changes.\n");
        sb.append(policy.allowCreate() ? "- You may create new files.\n" : "- Do not create new files.\n");
        sb.append(policy.allowDelete() ? "- You may delete files.\n" : "- Do not delete files.\n");
        if (!policy.protectedPaths().isEmpty()) {
            sb.append("- Do not modify: ").append(String.join(", ", policy.protectedPaths())).append('\n');
        }
        sb.append("- Do not modify build configuration, CI workflows or scripts.\n");
        sb.append("- Build and test results are authoritative; your explanation is not proof of correctness.\n\n");

        sb.append("## Acceptance\n").append(acceptance).append("\n\n");

        diagnostics.testSummary().ifPresent(s -> sb.append("## Test status\n")
                .append(s.run()).append(" run, ").append(s.failures()).append(" failed, ")
                .append(s.errors()).append(" errors, ").append(s.skipped()).append(" skipped\n\n"));

        sb.append("## Diagnostics\n").append(renderDiagnostics(diagnostics)).append('\n');

        if (!files.isEmpty()) {
            sb.append("## Source files\n");
            for (SourceFile f : files) {
                sb.append("### ").append(f.path()).append(f.truncated() ? " (truncated)" : "").append('\n');
                sb.append("```java\n").append(f.content());
                if (!f.content().endsWith("\n")) {
                    sb.append('\n');
                }
                sb.append("```\n\n");
            }
        }
        if (!notes.isEmpty()) {
            sb.append("## Notes\n");
            for (String n : notes) {
                sb.append("- ").append(n).append('\n');
            }
        }
        return sb.toString();
    }

    static String renderDiagnostics(ParsedBuild d) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (ParsedBuild.CompileError e : d.compileErrors()) {
            sb.append(n++).append(". [").append(e.category()).append("] ")
                    .append(e.file()).append(':').append(e.line()).append(':').append(e.column()).append('\n');
            indent(sb, e.message());
        }
        for (ParsedBuild.TestFailure f : d.testFailures()) {
            sb.append(n++).append(". [").append(f.category()).append("] ").append(f.testClass());
            if (!f.testMethod().isEmpty()) {
                sb.append('.').append(f.testMethod());
            }
            if (f.line() >= 0) {
                sb.append(':').append(f.line());
            }
            sb.append('\n');
            indent(sb, f.message());
        }
        if (n == 1) {
            sb.append("(no structured diagnostics were recognised)\n");
        }
        return sb.toString();
    }

    private static void indent(StringBuilder sb, String message) {
        String m = message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS) + "... (message truncated)" : message;
        for (String line : m.split("\\R")) {
            sb.append("   ").append(line).append('\n');
        }
    }
}
