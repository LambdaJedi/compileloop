package dev.compileloop.repair;

import dev.compileloop.repair.UnifiedDiff.FilePatch;
import dev.compileloop.repair.UnifiedDiff.Hunk;
import dev.compileloop.repair.UnifiedDiff.Kind;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a diff that PatchValidator already accepted. It is strict on purpose:
 * every context and removed line must match the file exactly (only the line
 * numbers may be off, within a small window), and either the whole patch is
 * applied or nothing is written. It still re-checks that each target stays
 * inside the workspace, because a validated diff is not a reason to skip that.
 */
public final class PatchApplier {

    /** How far from the stated line number a hunk may be found. */
    static final int MAX_OFFSET = 50;
    static final int MAX_RESULT_CHARS = 1_000_000;

    public record FileChange(String path, Kind kind, int added, int removed) {
    }

    public record ApplyResult(boolean applied, List<String> errors, List<FileChange> changes) {
        public ApplyResult {
            errors = List.copyOf(errors);
            changes = List.copyOf(changes);
        }
    }

    private PatchApplier() {
    }

    private record Line(String text, String eol) {
    }

    public static ApplyResult apply(List<FilePatch> patches, Path workspace) {
        List<String> errors = new ArrayList<>();
        List<FileChange> changes = new ArrayList<>();
        Map<Path, String> writes = new LinkedHashMap<>();   // path -> new content
        List<Path> deletes = new ArrayList<>();

        Path root;
        try {
            root = workspace.toRealPath();
        } catch (IOException e) {
            return new ApplyResult(false, List.of("workspace cannot be resolved: " + e.getMessage()), List.of());
        }

        for (FilePatch p : patches) {
            String path = p.path();
            if (path == null) {
                errors.add("a file entry has no path");
                continue;
            }
            Path target = root.resolve(path).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                errors.add(path + ": resolves outside the workspace");
                continue;
            }
            try {
                switch (p.kind()) {
                    case MODIFY -> {
                        String old = readText(target, path);
                        String updated = modify(old, p.hunks(), path);
                        if (updated.length() > MAX_RESULT_CHARS) {
                            throw new PatchException(path + ": the result would be larger than " + MAX_RESULT_CHARS + " characters");
                        }
                        writes.put(target, updated);
                        changes.add(new FileChange(path, p.kind(), p.added(), p.removed()));
                    }
                    case CREATE -> {
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw new PatchException(path + ": the file to create already exists");
                        }
                        writes.put(target, create(p.hunks(), path));
                        changes.add(new FileChange(path, p.kind(), p.added(), p.removed()));
                    }
                    case DELETE -> {
                        String old = readText(target, path);
                        checkWholeFileRemoved(old, p.hunks(), path);
                        deletes.add(target);
                        changes.add(new FileChange(path, p.kind(), p.added(), p.removed()));
                    }
                    default -> throw new PatchException(path + ": " + p.kind() + " is not supported");
                }
            } catch (PatchException e) {
                errors.add(e.getMessage());
            }
        }
        if (errors.isEmpty() && changes.isEmpty()) {
            errors.add("the patch contains no changes");
        }
        if (errors.isEmpty()) {
            boolean anyEffect = false;
            for (Map.Entry<Path, String> w : writes.entrySet()) {
                try {
                    if (!Files.exists(w.getKey(), LinkOption.NOFOLLOW_LINKS)
                            || !Files.readString(w.getKey(), StandardCharsets.UTF_8).equals(w.getValue())) {
                        anyEffect = true;
                    }
                } catch (IOException e) {
                    anyEffect = true;
                }
            }
            if (!anyEffect && deletes.isEmpty()) {
                errors.add("the patch would not change any file");
            }
        }
        if (!errors.isEmpty()) {
            return new ApplyResult(false, errors, List.of());
        }

        try {
            for (Map.Entry<Path, String> w : writes.entrySet()) {
                Files.createDirectories(w.getKey().getParent());
                Files.writeString(w.getKey(), w.getValue(), StandardCharsets.UTF_8);
            }
            for (Path d : deletes) {
                Files.delete(d);
            }
        } catch (IOException e) {
            return new ApplyResult(false, List.of("writing the patched files failed: " + e.getMessage()), List.of());
        }
        return new ApplyResult(true, List.of(), changes);
    }

    // ---- reading ------------------------------------------------------------------

    private static String readText(Path target, String shown) throws PatchException {
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatchException(shown + ": the file does not exist or is not a regular file");
            }
            byte[] bytes = Files.readAllBytes(target);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PatchException(shown + ": the file is not valid UTF-8 text");
        } catch (IOException e) {
            throw new PatchException(shown + ": the file could not be read (" + e.getMessage() + ")");
        }
    }

    private static List<Line> split(String text) {
        List<Line> lines = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int j = i;
            while (j < n && text.charAt(j) != '\n' && text.charAt(j) != '\r') {
                j++;
            }
            String eol = "";
            int end = j;
            if (j < n) {
                if (text.charAt(j) == '\r' && j + 1 < n && text.charAt(j + 1) == '\n') {
                    eol = "\r\n";
                    end = j + 2;
                } else {
                    eol = String.valueOf(text.charAt(j));
                    end = j + 1;
                }
            }
            lines.add(new Line(text.substring(i, j), eol));
            i = end;
        }
        return lines;
    }

    private static String dominantEol(List<Line> lines) {
        int crlf = 0;
        int lf = 0;
        for (Line l : lines) {
            if (l.eol().equals("\r\n")) {
                crlf++;
            } else if (l.eol().equals("\n")) {
                lf++;
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    // ---- hunks ------------------------------------------------------------------------

    /** One hunk's body, with each line's text and marker, and no-newline flags. */
    private record Entry(char marker, String text, boolean noEol) {
    }

    private static List<Entry> entries(Hunk h) {
        List<Entry> out = new ArrayList<>();
        for (String raw : h.lines()) {
            String l = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (l.startsWith("\\")) {
                if (!out.isEmpty()) {
                    Entry last = out.get(out.size() - 1);
                    out.set(out.size() - 1, new Entry(last.marker(), last.text(), true));
                }
                continue;
            }
            char m = l.isEmpty() ? ' ' : l.charAt(0);
            out.add(new Entry(m, l.isEmpty() ? "" : l.substring(1), false));
        }
        return out;
    }

    private static String modify(String old, List<Hunk> hunks, String path) throws PatchException {
        List<Line> file = split(old);
        String eol = dominantEol(file);
        List<Line> result = new ArrayList<>();
        int cursor = 0; // next unconsumed line of the original

        int hunkNo = 0;
        for (Hunk h : hunks) {
            hunkNo++;
            List<Entry> body = entries(h);
            List<String> expected = new ArrayList<>();
            for (Entry e : body) {
                if (e.marker() != '+') {
                    expected.add(e.text());
                }
            }
            int pos;
            if (expected.isEmpty()) {
                // pure insertion: oldStart is the line AFTER which to insert (0 = at the top)
                pos = h.oldStart();
                if (pos < cursor || pos > file.size()) {
                    throw new PatchException(path + ", hunk " + hunkNo + ": insertion point " + pos
                            + " is outside the file (" + file.size() + " lines)");
                }
            } else {
                pos = locate(file, expected, Math.max(0, h.oldStart() - 1), cursor, path, hunkNo);
            }
            result.addAll(file.subList(cursor, pos));
            int at = pos;
            for (Entry e : body) {
                switch (e.marker()) {
                    case ' ' -> result.add(file.get(at++));
                    case '-' -> at++;
                    case '+' -> result.add(new Line(e.text(), e.noEol() ? "" : eol));
                    default -> throw new PatchException(path + ", hunk " + hunkNo + ": unexpected line");
                }
            }
            cursor = at;
        }
        result.addAll(file.subList(cursor, file.size()));
        return join(result, eol);
    }

    /** Finds where the expected lines sit, nearest to {@code want}, not before {@code min}. */
    private static int locate(List<Line> file, List<String> expected, int want, int min, String path, int hunkNo)
            throws PatchException {
        int last = file.size() - expected.size();
        int best = -1;
        boolean tie = false;
        for (int d = 0; d <= MAX_OFFSET; d++) {
            int a = want - d;
            int b = want + d;
            boolean okA = a >= min && a <= last && matches(file, expected, a);
            boolean okB = d != 0 && b >= min && b <= last && matches(file, expected, b);
            if (okA && okB) {
                tie = true;
                best = a;
                break;
            }
            if (okA || okB) {
                best = okA ? a : b;
                break;
            }
        }
        if (tie) {
            throw new PatchException(path + ", hunk " + hunkNo
                    + ": the context matches in two places equally near the stated line; refusing to guess");
        }
        if (best < 0) {
            throw new PatchException(path + ", hunk " + hunkNo + ": the lines this hunk expects were not found within "
                    + MAX_OFFSET + " lines of line " + (want + 1) + ". First expected line: "
                    + clip(expected.get(0)));
        }
        return best;
    }

    private static boolean matches(List<Line> file, List<String> expected, int at) {
        for (int i = 0; i < expected.size(); i++) {
            if (!file.get(at + i).text().equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String join(List<Line> lines, String eol) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            sb.append(l.text());
            // every line except the last must end with a line break
            sb.append(l.eol().isEmpty() && i < lines.size() - 1 ? eol : l.eol());
        }
        return sb.toString();
    }

    private static String create(List<Hunk> hunks, String path) throws PatchException {
        StringBuilder sb = new StringBuilder();
        for (Hunk h : hunks) {
            for (Entry e : entries(h)) {
                if (e.marker() != '+') {
                    throw new PatchException(path + ": a new file may only contain added lines");
                }
                sb.append(e.text());
                if (!e.noEol()) {
                    sb.append('\n');
                }
            }
        }
        if (sb.length() > MAX_RESULT_CHARS) {
            throw new PatchException(path + ": the new file would be larger than " + MAX_RESULT_CHARS + " characters");
        }
        return sb.toString();
    }

    private static void checkWholeFileRemoved(String old, List<Hunk> hunks, String path) throws PatchException {
        List<Line> file = split(old);
        List<String> removed = new ArrayList<>();
        for (Hunk h : hunks) {
            for (Entry e : entries(h)) {
                if (e.marker() != '-') {
                    throw new PatchException(path + ": a deletion may only contain removed lines");
                }
                removed.add(e.text());
            }
        }
        if (removed.size() != file.size() || !matches(file, removed, 0)) {
            throw new PatchException(path + ": the deletion does not match the current file content");
        }
    }

    private static String clip(String s) {
        String t = s.strip();
        return "\"" + (t.length() > 80 ? t.substring(0, 80) + "..." : t) + "\"";
    }

    private static final class PatchException extends Exception {
        PatchException(String message) {
            super(message);
        }
    }
}
