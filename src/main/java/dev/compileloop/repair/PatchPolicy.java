package dev.compileloop.repair;

import java.util.List;

/**
 * Limits a proposed patch must stay within. Paths are relative to the project
 * root and use '/'. Defaults are conservative: Java sources and tests only, no
 * deletions, small patches.
 */
public record PatchPolicy(
        List<String> allowedRoots,
        List<String> allowedExtensions,
        List<String> protectedPaths,
        int maxFiles,
        int maxChangedLines,
        int maxDiffChars,
        int maxResponseChars,
        boolean allowCreate,
        boolean allowDelete
) {
    public PatchPolicy {
        allowedRoots = List.copyOf(allowedRoots.stream().map(PatchPolicy::cleanRoot).toList());
        allowedExtensions = List.copyOf(allowedExtensions.stream().map(e -> e.toLowerCase(java.util.Locale.ROOT)).toList());
        protectedPaths = List.copyOf(protectedPaths.stream().map(PatchPolicy::cleanRoot).toList());
        if (maxFiles < 1 || maxChangedLines < 1 || maxDiffChars < 1 || maxResponseChars < 1) {
            throw new IllegalArgumentException("policy limits must be positive");
        }
    }

    public static PatchPolicy defaults() {
        return new PatchPolicy(
                List.of("src/main/java", "src/test/java"),
                List.of(".java"),
                List.of(),
                5, 200, 100_000, 200_000,
                true, false);
    }

    public PatchPolicy withAllowedRoots(List<String> v) {
        return new PatchPolicy(v, allowedExtensions, protectedPaths, maxFiles, maxChangedLines,
                maxDiffChars, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withAllowedExtensions(List<String> v) {
        return new PatchPolicy(allowedRoots, v, protectedPaths, maxFiles, maxChangedLines,
                maxDiffChars, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withProtectedPaths(List<String> v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, v, maxFiles, maxChangedLines,
                maxDiffChars, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withMaxFiles(int v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, v, maxChangedLines,
                maxDiffChars, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withMaxChangedLines(int v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, maxFiles, v,
                maxDiffChars, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withMaxDiffChars(int v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, maxFiles, maxChangedLines,
                v, maxResponseChars, allowCreate, allowDelete);
    }

    public PatchPolicy withMaxResponseChars(int v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, maxFiles, maxChangedLines,
                maxDiffChars, v, allowCreate, allowDelete);
    }

    public PatchPolicy withAllowCreate(boolean v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, maxFiles, maxChangedLines,
                maxDiffChars, maxResponseChars, v, allowDelete);
    }

    public PatchPolicy withAllowDelete(boolean v) {
        return new PatchPolicy(allowedRoots, allowedExtensions, protectedPaths, maxFiles, maxChangedLines,
                maxDiffChars, maxResponseChars, allowCreate, v);
    }

    /** Configuration sanity: roots/protected entries must be plain relative paths. */
    private static String cleanRoot(String s) {
        String t = s.replace('\\', '/');
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.isEmpty() || t.startsWith("/") || t.contains("..") || t.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("policy path must be a plain relative path: " + s);
        }
        return t;
    }
}
