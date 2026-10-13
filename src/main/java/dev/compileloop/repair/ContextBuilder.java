package dev.compileloop.repair;

import dev.compileloop.build.ParsedBuild;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a bounded RepairContext from build diagnostics using fixed rules (no
 * model involved, so the same failure always yields the same context). Only files
 * inside the policy's allowed roots are ever read, so the model cannot be shown
 * secrets or configuration. Whatever is cut or skipped is recorded in the notes.
 */
public final class ContextBuilder {

    public record Limits(int maxFiles, int maxCharsPerFile, int maxTotalChars, int maxDiagnostics) {
        public static Limits defaults() {
            return new Limits(5, 20_000, 60_000, 20);
        }
    }

    private static final int INSTRUCTION_OVERHEAD = 2_500;
    private static final Pattern FRAME =
            Pattern.compile("\\bat ([\\w.$]+)\\.[\\w$<>]+\\([\\w$]+\\.java:\\d+\\)");

    private ContextBuilder() {
    }

    public static RepairContext build(Path project, ParsedBuild diagnostics, PatchPolicy policy, Limits limits) {
        Path proj = project.toAbsolutePath().normalize();
        List<String> notes = new ArrayList<>();

        // 1. bound the diagnostics
        int ce = Math.min(limits.maxDiagnostics(), diagnostics.compileErrors().size());
        int tf = Math.min(limits.maxDiagnostics() - ce, diagnostics.testFailures().size());
        if (ce < diagnostics.compileErrors().size() || tf < diagnostics.testFailures().size()) {
            notes.add("Diagnostics were limited to the first " + limits.maxDiagnostics() + "; others were omitted.");
        }
        ParsedBuild bounded = new ParsedBuild(
                List.copyOf(diagnostics.compileErrors().subList(0, ce)),
                List.copyOf(diagnostics.testFailures().subList(0, tf)),
                diagnostics.testSummary());

        // 2. candidate files, in a fixed order
        Set<String> candidates = new LinkedHashSet<>();
        String projText = proj.toString().replace('\\', '/');
        for (ParsedBuild.CompileError e : bounded.compileErrors()) {
            String rel = relativize(e.file(), projText);
            if (rel != null) {
                candidates.add(rel);
            }
        }
        for (ParsedBuild.TestFailure f : bounded.testFailures()) {
            String cls = outerClass(f.testClass());
            candidates.add("src/test/java/" + cls.replace('.', '/') + ".java");
            String underTest = classUnderTest(cls);
            if (underTest != null) {
                candidates.add("src/main/java/" + underTest.replace('.', '/') + ".java");
            }
            Matcher m = FRAME.matcher(f.rawExcerpt());
            while (m.find()) {
                String path = outerClass(m.group(1)).replace('.', '/') + ".java";
                candidates.add("src/main/java/" + path);
                candidates.add("src/test/java/" + path);
            }
        }

        // 3. read within bounds
        int budget = limits.maxTotalChars() - INSTRUCTION_OVERHEAD - RepairContext.renderDiagnostics(bounded).length();
        List<RepairContext.SourceFile> files = new ArrayList<>();
        int omitted = 0;
        for (String rel : candidates) {
            if (!readable(rel, policy)) {
                continue;
            }
            Path p = proj.resolve(rel).normalize();
            if (!p.startsWith(proj) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (files.size() >= limits.maxFiles() || budget < 200) {
                omitted++;
                continue;
            }
            if (!insideProject(proj, p)) {
                notes.add("Skipped " + rel + ": it resolves outside the project.");
                continue;
            }
            int cap = Math.min(limits.maxCharsPerFile(), budget);
            try {
                String[] text = readCapped(p, cap);
                boolean truncated = text[1].equals("1");
                files.add(new RepairContext.SourceFile(rel, text[0], truncated));
                budget -= text[0].length();
                if (truncated) {
                    notes.add(rel + " was truncated to " + text[0].length() + " characters.");
                }
            } catch (IOException e) {
                notes.add("Skipped " + rel + ": it could not be read.");
            }
        }
        if (omitted > 0) {
            notes.add(omitted + " further relevant file(s) were left out to stay within the context limits.");
        }
        return new RepairContext("java", "maven", bounded, files, policy, notes,
                "`mvn -B test` must pass with no failing tests.");
    }

    /**
     * Project-relative path for a path printed by the build. Windows logs carry
     * paths like C:/Users/x/proj/src/..., which may not match this machine, so
     * fall back to the src/main|test/java marker.
     */
    static String relativize(String file, String projAbs) {
        String f = file.replace('\\', '/');
        String p = projAbs.replace('\\', '/');
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (f.length() > p.length() && f.regionMatches(true, 0, p, 0, p.length()) && f.charAt(p.length()) == '/') {
            return f.substring(p.length() + 1);
        }
        for (String marker : new String[]{"/src/main/java/", "/src/test/java/"}) {
            int idx = f.indexOf(marker);
            if (idx >= 0) {
                return f.substring(idx + 1);
            }
        }
        return f.startsWith("src/") ? f : null;
    }

    private static boolean readable(String rel, PatchPolicy policy) {
        if (rel.contains("..") || rel.startsWith("/") || rel.indexOf('\\') >= 0) {
            return false;
        }
        boolean inRoot = policy.allowedRoots().stream().anyMatch(r -> rel.startsWith(r + "/"));
        String lower = rel.toLowerCase(java.util.Locale.ROOT);
        return inRoot && policy.allowedExtensions().stream().anyMatch(lower::endsWith);
    }

    private static boolean insideProject(Path proj, Path file) {
        try {
            return file.toRealPath().startsWith(proj.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static String outerClass(String name) {
        int d = name.indexOf('$');
        return d >= 0 ? name.substring(0, d) : name;
    }

    /** CalcTest / CalcTests / TestCalc -> Calc (same package), else null. */
    private static String classUnderTest(String testClass) {
        int dot = testClass.lastIndexOf('.');
        String pkg = dot >= 0 ? testClass.substring(0, dot + 1) : "";
        String simple = testClass.substring(dot + 1);
        String base = null;
        if (simple.endsWith("Tests") && simple.length() > 5) {
            base = simple.substring(0, simple.length() - 5);
        } else if (simple.endsWith("Test") && simple.length() > 4) {
            base = simple.substring(0, simple.length() - 4);
        } else if (simple.startsWith("Test") && simple.length() > 4) {
            base = simple.substring(4);
        }
        return base == null ? null : pkg + base;
    }

    /** Returns {text, "1" if truncated else "0"}. Reads at most 4 bytes per allowed character. */
    private static String[] readCapped(Path p, int maxChars) throws IOException {
        long limit = 4L * maxChars + 4;
        byte[] bytes;
        boolean cut = false;
        try (InputStream in = Files.newInputStream(p)) {
            bytes = in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
            cut = in.read() >= 0;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > maxChars) {
            int end = text.lastIndexOf('\n', maxChars);
            text = text.substring(0, end > 0 ? end + 1 : maxChars);
            cut = true;
        }
        return new String[]{text, cut ? "1" : "0"};
    }
}
