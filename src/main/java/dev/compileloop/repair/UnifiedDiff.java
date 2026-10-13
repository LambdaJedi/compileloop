package dev.compileloop.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for unified diffs (git style, also plain ---/+++). It only
 * describes a diff: it never touches the filesystem. Paths have one leading
 * "a/" (old) or "b/" (new) stripped, as {@code git apply} / {@code patch -p1} do.
 *
 * <p>Hunk counts are re-derived from the body's ' ', '-', '+' markers, matching
 * {@code git apply --recount}. A header whose numeric counts disagree with its
 * body is accepted when the body is unambiguous; the stored hunk carries the
 * recomputed counts. Malformed headers, missing file headers, unexpected body
 * lines and empty hunks are still rejected.
 */
public final class UnifiedDiff {

    public enum Kind { MODIFY, CREATE, DELETE, RENAME, COPY }

    /** Each line keeps its leading ' ', '+', '-' or '\' marker. */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {
    }

    /**
     * {@code oldPath}/{@code newPath} are null for /dev/null. {@code fileMode} is the
     * mode from "new file mode" or "new mode", or null if the diff did not state one.
     */
    public record FilePatch(String oldPath, String newPath, Kind kind, boolean binary,
                            boolean modeChange, String fileMode, List<Hunk> hunks,
                            int added, int removed) {
        /** The path this patch ends up at (or removes). */
        public String path() {
            return newPath != null ? newPath : oldPath;
        }
    }

    public static final class DiffFormatException extends Exception {
        DiffFormatException(int line, String message) {
            super("line " + line + ": " + message);
        }
    }

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiff() {
    }

    public static List<FilePatch> parse(String diff) throws DiffFormatException {
        List<String> lines = splitLines(diff);
        List<FilePatch> out = new ArrayList<>();
        Builder b = null;
        int i = 0;
        while (i < lines.size()) {
            String line = stripCr(lines.get(i));
            int lineNo = i + 1;

            if (line.startsWith("diff --git ")) {
                if (b != null) {
                    out.add(b.build(lineNo));
                }
                b = new Builder();
                parseGitHeader(b, line, lineNo);
                i++;
                continue;
            }
            if (line.startsWith("--- ")) {
                if (b == null || b.sawOldHeader || !b.hunks.isEmpty()) {
                    if (b != null) {
                        out.add(b.build(lineNo));
                    }
                    b = new Builder();
                }
                if (i + 1 >= lines.size() || !stripCr(lines.get(i + 1)).startsWith("+++ ")) {
                    throw new DiffFormatException(lineNo, "'---' header is not followed by a '+++' header");
                }
                b.oldPath = parsePathField(line.substring(4), 'a', lineNo);
                b.newPath = parsePathField(stripCr(lines.get(i + 1)).substring(4), 'b', lineNo + 1);
                b.sawOldHeader = true;
                i += 2;
                continue;
            }
            if (b == null) {
                if (line.isBlank()) {
                    i++;
                    continue;
                }
                throw new DiffFormatException(lineNo, "content before the first file header: " + clip(line));
            }
            if (line.startsWith("@@ ")) {
                i = readHunk(lines, i, b);
                continue;
            }
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (line.startsWith("new file mode ")) {
                b.newFile = true;
                b.fileMode = line.substring("new file mode ".length()).trim();
            } else if (line.startsWith("deleted file mode ")) {
                b.deletedFile = true;
            } else if (line.startsWith("old mode ")) {
                b.modeChange = true;
            } else if (line.startsWith("new mode ")) {
                b.modeChange = true;
                b.fileMode = line.substring("new mode ".length()).trim();
            } else if (line.startsWith("similarity index ") || line.startsWith("dissimilarity index ")
                    || line.startsWith("index ")) {
                // informational only
            } else if (line.startsWith("rename from ")) {
                b.rename = true;
                b.renameFrom = line.substring("rename from ".length());
            } else if (line.startsWith("rename to ")) {
                b.rename = true;
                b.renameTo = line.substring("rename to ".length());
            } else if (line.startsWith("copy from ")) {
                b.copy = true;
                b.renameFrom = line.substring("copy from ".length());
            } else if (line.startsWith("copy to ")) {
                b.copy = true;
                b.renameTo = line.substring("copy to ".length());
            } else if (line.startsWith("Binary files ")) {
                b.binary = true;
            } else if (line.equals("GIT binary patch")) {
                b.binary = true;
                // skip the encoded payload up to the next file section
                while (i + 1 < lines.size() && !stripCr(lines.get(i + 1)).startsWith("diff --git ")) {
                    i++;
                }
            } else {
                throw new DiffFormatException(lineNo, "unrecognised line: " + clip(line));
            }
            i++;
        }
        if (b != null) {
            out.add(b.build(lines.size()));
        }
        if (out.isEmpty()) {
            throw new DiffFormatException(1, "the diff contains no file changes");
        }
        return out;
    }

    // ---- hunks --------------------------------------------------------------

    private static int readHunk(List<String> lines, int i, Builder b) throws DiffFormatException {
        int headerLine = i + 1;
        Matcher m = HUNK.matcher(stripCr(lines.get(i)));
        if (!m.matches()) {
            throw new DiffFormatException(headerLine, "malformed hunk header: " + clip(lines.get(i)));
        }
        if (!b.sawOldHeader) {
            throw new DiffFormatException(headerLine, "hunk without a '---'/'+++' file header");
        }
        int oldStart;
        int newStart;
        try {
            oldStart = Integer.parseInt(m.group(1));
            newStart = Integer.parseInt(m.group(3));
            // Declared counts are parsed only to reject non-numeric values.
            // The actual counts are re-derived from the body, matching
            // `git apply --recount`: a wrong count is a cosmetic error when
            // the body is unambiguous, so we do not reject on arithmetic.
            if (m.group(2) != null) {
                Integer.parseInt(m.group(2));
            }
            if (m.group(4) != null) {
                Integer.parseInt(m.group(4));
            }
        } catch (NumberFormatException e) {
            throw new DiffFormatException(headerLine, "hunk numbers are out of range");
        }

        List<String> body = new ArrayList<>();
        int oldCount = 0;
        int newCount = 0;
        int added = 0;
        int removed = 0;
        i++;
        while (i < lines.size()) {
            String l = lines.get(i);

            // A "--- " line followed by "+++ " is a new file header, not body
            // content. Real diffs never have that pattern as a hunk body, and
            // without this check the next section's headers would be swallowed
            // as body lines (e.g. "-old\n+new\n--- a/B.java\n+++ b/B.java\n").
            if (l.startsWith("--- ") && i + 1 < lines.size()
                    && lines.get(i + 1).startsWith("+++ ")) {
                break;
            }

            if (l.isEmpty()) {
                // Empty line between two body lines is an empty context line
                // (the leading space was stripped upstream). A trailing empty
                // line — or one followed by a new file header — ends the hunk
                // instead of inflating the count. Stored as a single space,
                // matching what git diff writes.
                boolean nextIsFileHeader = i + 1 < lines.size()
                        && lines.get(i + 1).startsWith("--- ")
                        && i + 2 < lines.size() && lines.get(i + 2).startsWith("+++ ");
                boolean nextIsBody = !nextIsFileHeader
                        && i + 1 < lines.size() && isBodyLine(lines.get(i + 1));
                if (body.isEmpty() || !nextIsBody) {
                    break;
                }
                oldCount++;
                newCount++;
                body.add(" ");
                i++;
                continue;
            }

            char c = l.charAt(0);
            if (c == ' ') {
                oldCount++;
                newCount++;
            } else if (c == '-') {
                oldCount++;
                removed++;
            } else if (c == '+') {
                newCount++;
                added++;
            } else if (c == '\\') {
                // "\ No newline at end of file": informational, not counted
            } else {
                break;
            }
            body.add(l);
            i++;
        }
        if (oldCount == 0 && newCount == 0) {
            throw new DiffFormatException(headerLine, "hunk header without any body lines");
        }

        b.hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, body));
        b.added += added;
        b.removed += removed;
        return i;
    }

    private static boolean isBodyLine(String l) {
        if (l.isEmpty()) {
            return false;
        }
        char c = l.charAt(0);
        return c == ' ' || c == '-' || c == '+' || c == '\\';
    }

    // ---- headers ------------------------------------------------------------

    private static void parseGitHeader(Builder b, String line, int lineNo) throws DiffFormatException {
        String rest = line.substring("diff --git ".length());
        if (rest.startsWith("\"")) {
            throw new DiffFormatException(lineNo, "quoted paths are not supported");
        }
        int from = 0;
        int firstSplit = -1;
        while (true) {
            int idx = rest.indexOf(" b/", from);
            if (idx < 0) {
                break;
            }
            if (firstSplit < 0) {
                firstSplit = idx;
            }
            String left = rest.substring(0, idx);
            String right = rest.substring(idx + 1);
            if (left.startsWith("a/") && left.substring(2).equals(right.substring(2))) {
                b.gitA = left.substring(2);
                b.gitB = right.substring(2);
                return;
            }
            from = idx + 1;
        }
        if (firstSplit < 0 || !rest.startsWith("a/")) {
            throw new DiffFormatException(lineNo, "cannot read the 'diff --git' header: " + clip(line));
        }
        b.gitA = rest.substring(2, firstSplit);
        b.gitB = rest.substring(firstSplit + 3);
    }

    private static String parsePathField(String field, char prefix, int lineNo) throws DiffFormatException {
        int tab = field.indexOf('\t');
        String p = tab >= 0 ? field.substring(0, tab) : field;
        if (p.startsWith("\"")) {
            throw new DiffFormatException(lineNo, "quoted paths are not supported");
        }
        if (p.isEmpty()) {
            throw new DiffFormatException(lineNo, "empty file path");
        }
        if (p.equals("/dev/null")) {
            return null;
        }
        return p.startsWith(prefix + "/") ? p.substring(2) : p;
    }

    private static final class Builder {
        String gitA;
        String gitB;
        String oldPath;
        String newPath;
        String renameFrom;
        String renameTo;
        String fileMode;
        boolean sawOldHeader;
        boolean newFile;
        boolean deletedFile;
        boolean rename;
        boolean copy;
        boolean modeChange;
        boolean binary;
        final List<Hunk> hunks = new ArrayList<>();
        int added;
        int removed;

        FilePatch build(int lineNo) throws DiffFormatException {
            boolean moved = rename || copy;
            String o = sawOldHeader ? oldPath : (moved ? renameFrom : gitA);
            String n = sawOldHeader ? newPath : (moved ? renameTo : gitB);
            if (newFile) {
                o = null;
            }
            if (deletedFile) {
                n = null;
            }
            if (o == null && n == null) {
                throw new DiffFormatException(lineNo, "a file section has no path");
            }
            Kind kind;
            if (copy) {
                kind = Kind.COPY;
            } else if (newFile || o == null) {
                kind = Kind.CREATE;
            } else if (deletedFile || n == null) {
                kind = Kind.DELETE;
            } else if (rename || !o.equals(n)) {
                kind = Kind.RENAME;
            } else {
                kind = Kind.MODIFY;
            }
            if (kind == Kind.MODIFY && hunks.isEmpty() && !modeChange && !binary) {
                throw new DiffFormatException(lineNo, "file section for " + o + " has no changes");
            }
            return new FilePatch(o, n, kind, binary, modeChange, fileMode, List.copyOf(hunks), added, removed);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static List<String> splitLines(String diff) {
        List<String> lines = new ArrayList<>(List.of(diff.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String clip(String s) {
        String t = s.length() > 80 ? s.substring(0, 80) + "..." : s;
        return t.replaceAll("[\\p{Cntrl}]", "?");
    }
}