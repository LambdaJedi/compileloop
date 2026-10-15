package dev.compileloop.repair;

import dev.compileloop.repair.UnifiedDiff.FilePatch;
import dev.compileloop.repair.UnifiedDiff.Hunk;
import dev.compileloop.repair.UnifiedDiff.Kind;

import java.util.List;

/**
 * Renders validated {@link FilePatch}es back to unified-diff text. The output is
 * canonical: hunk headers carry the counts the parser recomputed from the body,
 * so {@code git apply} accepts the result even when the model's original diff
 * had wrong arithmetic. This is what gets written to {@code fix.patch}; the
 * model's raw answer stays in {@code attempt-N/proposal.diff} for the audit trail.
 *
 * <p>Only the patch kinds PatchPolicy allows reach here (MODIFY, CREATE, DELETE).
 * RENAME and COPY are rejected by the validator before we get called; if one
 * still arrives, this class throws rather than guess at a rendering.
 */
public final class UnifiedDiffWriter {

    private UnifiedDiffWriter() {
    }

    public static String write(List<FilePatch> patches) {
        StringBuilder sb = new StringBuilder();
        for (FilePatch p : patches) {
            render(sb, p);
        }
        return sb.toString();
    }

    private static void render(StringBuilder sb, FilePatch p) {
        switch (p.kind()) {
            case MODIFY, CREATE, DELETE -> { }
            default -> throw new IllegalArgumentException(
                    "cannot render a " + p.kind() + " patch; only MODIFY, CREATE and DELETE are allowed");
        }

        String oldForHeader = p.oldPath() == null ? "/dev/null" : "a/" + p.oldPath();
        String newForHeader = p.newPath() == null ? "/dev/null" : "b/" + p.newPath();

        // A `diff --git` line is conventional but only meaningful when both sides exist.
        if (p.oldPath() != null && p.newPath() != null) {
            sb.append("diff --git ").append(oldForHeader).append(' ').append(newForHeader).append('\n');
        }
        if (p.kind() == Kind.CREATE) {
            sb.append("new file mode 100644\n");
        } else if (p.kind() == Kind.DELETE) {
            sb.append("deleted file mode 100644\n");
        }

        sb.append("--- ").append(oldForHeader).append('\n');
        sb.append("+++ ").append(newForHeader).append('\n');

        for (Hunk h : p.hunks()) {
            sb.append("@@ -").append(h.oldStart()).append(',').append(h.oldCount())
                    .append(" +").append(h.newStart()).append(',').append(h.newCount())
                    .append(" @@\n");
            for (String line : h.lines()) {
                sb.append(line).append('\n');
            }
        }
    }
}
