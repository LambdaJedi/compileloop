package dev.compileloop.repair;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outcome of validating a proposal. Every violation found is listed, not just the
 * first, so the audit trail shows the whole picture. {@code files} is the parsed
 * diff when it could be parsed (even if rejected), otherwise empty.
 */
public record ValidationResult(boolean accepted, List<Violation> violations, List<UnifiedDiff.FilePatch> files) {

    public enum Code {
        EMPTY_DIFF, DIFF_TOO_LARGE, MALFORMED_DIFF, BINARY_PATCH,
        TOO_MANY_FILES, TOO_MANY_LINES, DUPLICATE_FILE,
        INVALID_PATH, ABSOLUTE_PATH, PATH_TRAVERSAL, FORBIDDEN_PATH,
        OUTSIDE_ALLOWED_ROOTS, DISALLOWED_EXTENSION, PROTECTED_PATH,
        SYMLINK_ESCAPE, SYMLINK_NOT_ALLOWED, MODE_CHANGE,
        DELETE_NOT_ALLOWED, CREATE_NOT_ALLOWED, RENAME_NOT_ALLOWED, COPY_NOT_ALLOWED,
        FILE_MISSING, FILE_EXISTS, WORKSPACE_UNAVAILABLE
    }

    public record Violation(Code code, String path, String message) {
        @Override
        public String toString() {
            return code + (path == null ? "" : " [" + path + "]") + ": " + message;
        }
    }

    public ValidationResult {
        violations = List.copyOf(violations);
        files = List.copyOf(files);
    }

    public Set<Code> codes() {
        return violations.stream().map(Violation::code).collect(Collectors.toSet());
    }

    public boolean has(Code code) {
        return codes().contains(code);
    }
}
