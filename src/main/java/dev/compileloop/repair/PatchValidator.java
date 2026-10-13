package dev.compileloop.repair;

import dev.compileloop.repair.UnifiedDiff.FilePatch;
import dev.compileloop.repair.ValidationResult.Code;
import dev.compileloop.repair.ValidationResult.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a proposed diff may be applied to a workspace. Pure checking:
 * it reads the workspace to verify files exist and are not symlinks, and never
 * writes anything. A patch is accepted only if there are no violations at all.
 *
 * Everything in the diff is hostile input. The checks are layered: textual path
 * rules first (so nothing is resolved against the filesystem until the path looks
 * sane), then policy (roots, extensions, protected files), then filesystem facts
 * (symlinks, existence).
 */
public final class PatchValidator {

    private static final Set<String> FORBIDDEN_SEGMENTS =
            Set.of(".git", ".github", ".mvn", ".idea", "target", ".compileloop");
    private static final Set<String> FORBIDDEN_BASENAMES = Set.of(
            "pom.xml", "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.lockfile",
            "package.json", "package-lock.json", "yarn.lock", "dockerfile", "makefile",
            ".gitignore", ".gitattributes", ".gitmodules");
    private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(
            ".sh", ".cmd", ".bat", ".ps1", ".yml", ".yaml", ".jar", ".class", ".exe", ".dll", ".so");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private PatchValidator() {
    }

    public static ValidationResult validate(PatchProposal proposal, PatchPolicy policy, Path workspace) {
        List<Violation> v = new ArrayList<>();
        String diff = proposal.diff();

        if (diff == null || diff.isBlank()) {
            v.add(new Violation(Code.EMPTY_DIFF, null, "the proposal contains no diff"));
            return new ValidationResult(false, v, List.of());
        }
        if (diff.length() > policy.maxDiffChars()) {
            v.add(new Violation(Code.DIFF_TOO_LARGE, null, "diff is " + diff.length()
                    + " characters, limit " + policy.maxDiffChars()));
            return new ValidationResult(false, v, List.of());
        }
        if (diff.indexOf('\0') >= 0) {
            v.add(new Violation(Code.BINARY_PATCH, null, "diff contains NUL bytes"));
            return new ValidationResult(false, v, List.of());
        }

        List<FilePatch> files;
        try {
            files = UnifiedDiff.parse(diff);
        } catch (UnifiedDiff.DiffFormatException e) {
            v.add(new Violation(Code.MALFORMED_DIFF, null, e.getMessage()));
            return new ValidationResult(false, v, List.of());
        }

        Path wsReal;
        try {
            wsReal = workspace.toRealPath();
        } catch (IOException e) {
            v.add(new Violation(Code.WORKSPACE_UNAVAILABLE, null, "workspace cannot be resolved: " + e.getMessage()));
            return new ValidationResult(false, v, files);
        }

        if (files.size() > policy.maxFiles()) {
            v.add(new Violation(Code.TOO_MANY_FILES, null,
                    files.size() + " files changed, limit " + policy.maxFiles()));
        }
        int changed = files.stream().mapToInt(f -> f.added() + f.removed()).sum();
        if (changed > policy.maxChangedLines()) {
            v.add(new Violation(Code.TOO_MANY_LINES, null,
                    changed + " lines changed, limit " + policy.maxChangedLines()));
        }

        Set<String> seen = new HashSet<>();
        for (FilePatch f : files) {
            checkFile(f, policy, wsReal, v);
            String key = String.valueOf(f.path()).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                v.add(new Violation(Code.DUPLICATE_FILE, f.path(), "the same file is patched more than once"));
            }
        }
        return new ValidationResult(v.isEmpty(), v, files);
    }

    // ---- per file -------------------------------------------------------------

    private static void checkFile(FilePatch f, PatchPolicy policy, Path wsReal, List<Violation> v) {
        String shown = f.path();

        if (f.binary()) {
            v.add(new Violation(Code.BINARY_PATCH, shown, "binary changes are not allowed"));
        }
        if ("120000".equals(f.fileMode())) {
            v.add(new Violation(Code.SYMLINK_NOT_ALLOWED, shown, "creating a symbolic link is not allowed"));
        } else if (f.modeChange() || (f.kind() == UnifiedDiff.Kind.CREATE && f.fileMode() != null
                && !"100644".equals(f.fileMode()))) {
            v.add(new Violation(Code.MODE_CHANGE, shown, "file mode changes are not allowed"));
        }

        switch (f.kind()) {
            case RENAME -> v.add(new Violation(Code.RENAME_NOT_ALLOWED, shown, "renames are not allowed"));
            case COPY -> v.add(new Violation(Code.COPY_NOT_ALLOWED, shown, "copies are not allowed"));
            case DELETE -> {
                if (!policy.allowDelete()) {
                    v.add(new Violation(Code.DELETE_NOT_ALLOWED, shown, "deleting files is not allowed"));
                }
            }
            case CREATE -> {
                if (!policy.allowCreate()) {
                    v.add(new Violation(Code.CREATE_NOT_ALLOWED, shown, "creating files is not allowed"));
                }
            }
            default -> { }
        }

        // Check every path the patch names (both sides of a rename/copy). Non-short-circuit
        // on purpose: every bad path should be reported, not just the first.
        boolean ok = true;
        if (f.oldPath() != null) {
            ok &= checkPath(f.oldPath(), policy, wsReal, v);
        }
        if (f.newPath() != null && !f.newPath().equals(f.oldPath())) {
            ok &= checkPath(f.newPath(), policy, wsReal, v);
        }

        // Filesystem facts only for plain create/modify/delete of a path that passed.
        if (ok) {
            Path target = wsReal.resolve(f.path());
            switch (f.kind()) {
                case MODIFY, DELETE -> {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        v.add(new Violation(Code.FILE_MISSING, f.path(), "the file to change does not exist"));
                    }
                }
                case CREATE -> {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        v.add(new Violation(Code.FILE_EXISTS, f.path(), "the file to create already exists"));
                    }
                }
                default -> { }
            }
        }
    }

    // ---- paths ----------------------------------------------------------------

    /** Returns true if the path raised no violation. */
    private static boolean checkPath(String p, PatchPolicy policy, Path wsReal, List<Violation> v) {
        int before = v.size();

        if (p.isEmpty()) {
            v.add(new Violation(Code.INVALID_PATH, p, "empty path"));
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                v.add(new Violation(Code.INVALID_PATH, printable(p), "path contains a control character"));
                return false;
            }
        }
        if (p.indexOf('\\') >= 0) {
            v.add(new Violation(Code.INVALID_PATH, p, "backslashes are not allowed in paths"));
            return false;
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.startsWith("~")) {
            v.add(new Violation(Code.ABSOLUTE_PATH, p, "absolute paths are not allowed"));
            return false;
        }
        if (p.indexOf(':') >= 0) {
            v.add(new Violation(Code.INVALID_PATH, p, "':' is not allowed in paths"));
            return false;
        }

        String[] segments = p.split("/", -1);
        for (String seg : segments) {
            if (seg.isEmpty() || seg.equals(".")) {
                v.add(new Violation(Code.INVALID_PATH, p, "path has an empty or '.' segment"));
                return false;
            }
            if (seg.equals("..")) {
                v.add(new Violation(Code.PATH_TRAVERSAL, p, "'..' segments are not allowed"));
                return false;
            }
            if (seg.endsWith(".") || seg.endsWith(" ")) {
                v.add(new Violation(Code.INVALID_PATH, p, "segment ends with '.' or a space"));
                return false;
            }
            String stem = seg.contains(".") ? seg.substring(0, seg.indexOf('.')) : seg;
            if (WINDOWS_RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
                v.add(new Violation(Code.INVALID_PATH, p, "reserved device name '" + seg + "'"));
                return false;
            }
            if (FORBIDDEN_SEGMENTS.contains(seg.toLowerCase(Locale.ROOT))) {
                v.add(new Violation(Code.FORBIDDEN_PATH, p, "'" + seg + "' may never be modified"));
                return false;
            }
        }

        String base = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (FORBIDDEN_BASENAMES.contains(base)) {
            v.add(new Violation(Code.FORBIDDEN_PATH, p, "build and configuration files may not be modified"));
        }
        for (String ext : FORBIDDEN_EXTENSIONS) {
            if (base.endsWith(ext)) {
                v.add(new Violation(Code.FORBIDDEN_PATH, p, "files of type '" + ext + "' may not be modified"));
                break;
            }
        }

        boolean inRoot = policy.allowedRoots().stream().anyMatch(r -> p.startsWith(r + "/"));
        if (!inRoot) {
            v.add(new Violation(Code.OUTSIDE_ALLOWED_ROOTS, p,
                    "only files under " + policy.allowedRoots() + " may be modified"));
        }
        boolean extOk = policy.allowedExtensions().stream().anyMatch(base::endsWith);
        if (!extOk) {
            v.add(new Violation(Code.DISALLOWED_EXTENSION, p,
                    "only " + policy.allowedExtensions() + " files may be modified"));
        }
        for (String prot : policy.protectedPaths()) {
            if (p.equals(prot) || p.startsWith(prot + "/")) {
                v.add(new Violation(Code.PROTECTED_PATH, p, "this path is protected"));
                break;
            }
        }

        if (v.size() == before) {
            checkFilesystem(wsReal, p, segments, v);
        }
        return v.size() == before;
    }

    /** Rejects any symbolic link (or junction) on the way to the target, inside or out. */
    private static void checkFilesystem(Path wsReal, String p, String[] segments, List<Violation> v) {
        Path cur = wsReal;
        for (String seg : segments) {
            cur = cur.resolve(seg);
            if (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                return; // everything below does not exist yet; nothing to follow
            }
            if (Files.isSymbolicLink(cur)) {
                v.add(new Violation(Code.SYMLINK_ESCAPE, p, "path goes through a symbolic link"));
                return;
            }
            try {
                if (!cur.toRealPath().startsWith(wsReal)) {
                    v.add(new Violation(Code.SYMLINK_ESCAPE, p, "path resolves outside the workspace"));
                    return;
                }
            } catch (IOException e) {
                v.add(new Violation(Code.SYMLINK_ESCAPE, p, "path cannot be resolved safely"));
                return;
            }
        }
    }

    private static String printable(String s) {
        return s.replaceAll("[\\p{Cntrl}]", "?");
    }
}
