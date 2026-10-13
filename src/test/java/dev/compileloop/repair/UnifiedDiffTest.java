package dev.compileloop.repair;

import dev.compileloop.repair.UnifiedDiff.FilePatch;
import dev.compileloop.repair.UnifiedDiff.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class UnifiedDiffTest {

    private static String diff(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static List<FilePatch> parse(String d) throws UnifiedDiff.DiffFormatException {
        return UnifiedDiff.parse(d);
    }

    private static void assertRejected(String d) {
        try {
            UnifiedDiff.parse(d);
            fail("expected a format error for:\n" + d);
        } catch (UnifiedDiff.DiffFormatException e) {
            assertTrue(e.getMessage().startsWith("line "), e.getMessage());
        }
    }

    private static final String MODIFY = diff(
            "diff --git a/src/main/java/fx/Calc.java b/src/main/java/fx/Calc.java",
            "index 83db48f..bf2a3c1 100644",
            "--- a/src/main/java/fx/Calc.java",
            "+++ b/src/main/java/fx/Calc.java",
            "@@ -1,4 +1,4 @@",
            " package fx;",
            " public class Calc {",
            "-    int add(int a, int b) { return a - b; }",
            "+    int add(int a, int b) { return a + b; }",
            " }");

    @Test
    void parsesAGitStyleModification() throws Exception {
        List<FilePatch> files = parse(MODIFY);

        assertEquals(1, files.size());
        FilePatch f = files.get(0);
        assertEquals(Kind.MODIFY, f.kind());
        assertEquals("src/main/java/fx/Calc.java", f.oldPath());
        assertEquals("src/main/java/fx/Calc.java", f.newPath());
        assertEquals("src/main/java/fx/Calc.java", f.path());
        assertEquals(1, f.added());
        assertEquals(1, f.removed());
        assertEquals(1, f.hunks().size());
        assertEquals(1, f.hunks().get(0).oldStart());
        assertEquals(4, f.hunks().get(0).oldCount());
        assertEquals(4, f.hunks().get(0).newCount());
        assertEquals(5, f.hunks().get(0).lines().size());
        assertFalse(f.binary());
        assertFalse(f.modeChange());
    }

    @Test
    void parsesAPlainDiffWithoutGitHeader() throws Exception {
        List<FilePatch> files = parse(diff(
                "--- a/src/main/java/fx/Calc.java\t2026-10-12 10:00:00",
                "+++ b/src/main/java/fx/Calc.java\t2026-10-12 10:05:00",
                "@@ -1,2 +1,2 @@",
                " package fx;",
                "-class A {}",
                "+class B {}"));

        assertEquals("src/main/java/fx/Calc.java", files.get(0).path());
        assertEquals(Kind.MODIFY, files.get(0).kind());
    }

    @Test
    void onlyOneLeadingPrefixIsStripped() throws Exception {
        List<FilePatch> files = parse(diff(
                "--- a/a/Thing.java",
                "+++ b/b/Thing.java",
                "@@ -1 +1 @@",
                "-x",
                "+y"));

        assertEquals("a/Thing.java", files.get(0).oldPath());
        assertEquals("b/Thing.java", files.get(0).newPath());
        assertEquals(Kind.RENAME, files.get(0).kind(), "different old/new paths are a rename");
    }

    @Test
    void parsesSeveralFilesAndHunks() throws Exception {
        List<FilePatch> files = parse(diff(
                "diff --git a/A.java b/A.java",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -1,2 +1,2 @@",
                " one",
                "-two",
                "+TWO",
                "@@ -10,2 +10,3 @@",
                " ten",
                "-eleven",
                "+ELEVEN",
                "+twelve",
                "diff --git a/B.java b/B.java",
                "--- a/B.java",
                "+++ b/B.java",
                "@@ -1 +1 @@",
                "-b",
                "+B"));

        assertEquals(2, files.size());
        assertEquals(2, files.get(0).hunks().size());
        assertEquals(3, files.get(0).added());
        assertEquals(2, files.get(0).removed());
        assertEquals("B.java", files.get(1).path());
    }

    @Test
    void parsesNewFile() throws Exception {
        FilePatch f = parse(diff(
                "diff --git a/src/main/java/fx/New.java b/src/main/java/fx/New.java",
                "new file mode 100644",
                "index 0000000..e69de29",
                "--- /dev/null",
                "+++ b/src/main/java/fx/New.java",
                "@@ -0,0 +1,2 @@",
                "+package fx;",
                "+class New {}")).get(0);

        assertEquals(Kind.CREATE, f.kind());
        assertNull(f.oldPath());
        assertEquals("src/main/java/fx/New.java", f.newPath());
        assertEquals("100644", f.fileMode());
        assertEquals(2, f.added());
    }

    @Test
    void parsesDeletion() throws Exception {
        FilePatch f = parse(diff(
                "diff --git a/Old.java b/Old.java",
                "deleted file mode 100644",
                "--- a/Old.java",
                "+++ /dev/null",
                "@@ -1,2 +0,0 @@",
                "-package fx;",
                "-class Old {}")).get(0);

        assertEquals(Kind.DELETE, f.kind());
        assertEquals("Old.java", f.path());
        assertNull(f.newPath());
        assertEquals(2, f.removed());
    }

    @Test
    void parsesPureRenameAndRenameWithEdits() throws Exception {
        FilePatch pure = parse(diff(
                "diff --git a/A.java b/B.java",
                "similarity index 100%",
                "rename from A.java",
                "rename to B.java")).get(0);
        assertEquals(Kind.RENAME, pure.kind());
        assertEquals("A.java", pure.oldPath());
        assertEquals("B.java", pure.newPath());

        FilePatch edited = parse(diff(
                "diff --git a/A.java b/B.java",
                "similarity index 80%",
                "rename from A.java",
                "rename to B.java",
                "--- a/A.java",
                "+++ b/B.java",
                "@@ -1 +1 @@",
                "-a",
                "+b")).get(0);
        assertEquals(Kind.RENAME, edited.kind());
    }

    @Test
    void parsesCopy() throws Exception {
        FilePatch f = parse(diff(
                "diff --git a/A.java b/B.java",
                "copy from A.java",
                "copy to B.java")).get(0);

        assertEquals(Kind.COPY, f.kind());
    }

    @Test
    void marksBinaryChanges() throws Exception {
        FilePatch plain = parse(diff(
                "diff --git a/x.png b/x.png",
                "index 1..2 100644",
                "Binary files a/x.png and b/x.png differ")).get(0);
        assertTrue(plain.binary());

        List<FilePatch> encoded = parse(diff(
                "diff --git a/y.bin b/y.bin",
                "new file mode 100644",
                "GIT binary patch",
                "literal 5",
                "Mc${NZ0RR9100000",
                "",
                "diff --git a/A.java b/A.java",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -1 +1 @@",
                "-a",
                "+b"));
        assertEquals(2, encoded.size());
        assertTrue(encoded.get(0).binary());
        assertFalse(encoded.get(1).binary());
    }

    @Test
    void recordsModeChanges() throws Exception {
        FilePatch f = parse(diff(
                "diff --git a/run.java b/run.java",
                "old mode 100644",
                "new mode 100755")).get(0);

        assertTrue(f.modeChange());
        assertEquals("100755", f.fileMode());
        assertEquals(Kind.MODIFY, f.kind());
    }

    @Test
    void noNewlineMarkersAreNotCounted() throws Exception {
        FilePatch f = parse(diff(
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -1 +1 @@",
                "-old",
                "\\ No newline at end of file",
                "+new",
                "\\ No newline at end of file")).get(0);

        assertEquals(1, f.added());
        assertEquals(1, f.removed());
        assertEquals(1, f.hunks().size());
        assertEquals(4, f.hunks().get(0).lines().size());
    }

    @Test
    void hunkHeaderWithoutCountMeansOneLine() throws Exception {
        FilePatch f = parse(diff("--- a/A.java", "+++ b/A.java", "@@ -3 +3 @@", "-a", "+b")).get(0);

        assertEquals(1, f.hunks().get(0).oldCount());
        assertEquals(1, f.hunks().get(0).newCount());
    }

    @Test
    void emptyLineInsideAHunkIsEmptyContext() throws Exception {
        FilePatch f = parse(diff(
                "--- a/A.java", "+++ b/A.java", "@@ -1,3 +1,3 @@", " a", "", "-b", "+B")).get(0);

        assertEquals(1, f.added());
        assertEquals(" ", f.hunks().get(0).lines().get(1));
    }

    @Test
    void toleratesWindowsLineEndingsInHeaders() throws Exception {
        String crlf = MODIFY.replace("\n", "\r\n");

        FilePatch f = parse(crlf).get(0);

        assertEquals("src/main/java/fx/Calc.java", f.path());
        assertEquals(1, f.added());
    }

    // ---- recount: header counts are advisory, body counts are truth ------

    @Test
    void recountsHunkHeaderCountsFromTheBody() throws Exception {
        // Real output from qwen2.5-coder:7b: header says 7,7 but the body
        // is 3,3. Recount (matching `git apply --recount`) accepts it.
        FilePatch f = parse(diff(
                "--- a/src/main/java/fx/Calc.java",
                "+++ b/src/main/java/fx/Calc.java",
                "@@ -5,7 +5,7 @@",
                "     public int add(int a, int b) {",
                "-        return \"x\";",
                "+        return a + b;",
                "     }")).get(0);

        assertEquals(3, f.hunks().get(0).oldCount());
        assertEquals(3, f.hunks().get(0).newCount());
        assertEquals(1, f.added());
        assertEquals(1, f.removed());
    }

    @Test
    void recountDoesNotAcceptHunksWithNoBody() {
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -1,3 +1,3 @@"));
    }

    @Test
    void recountDoesNotAcceptUnknownBodyPrefixes() {
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -1 +1 @@", "?a", "+b"));
    }

    // ---- rejections ------------------------------------------------------

    @Test
    void rejectsMalformedDiffs() {
        assertRejected("");
        assertRejected("\n\n");
        assertRejected("hello world\n");
        assertRejected(diff("--- a/A.java", "@@ -1 +1 @@", "-a", "+b"));            // no +++
        assertRejected(diff("@@ -1 +1 @@", "-a", "+b"));                            // hunk without file header
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ nonsense @@"));     // bad hunk header
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -1,2 +1,2 @@"));    // hunk header, no body
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -1 +1 @@", "?a", "+b")); // unknown body prefix
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -1 +1 @@", "-a", "+b", "garbage")); // trailing junk
        assertRejected(diff("--- \"a/quoted\\tname.java\"", "+++ \"b/quoted\\tname.java\"", "@@ -1 +1 @@", "-a", "+b"));
        assertRejected(diff("diff --git \"a/x y\" \"b/x y\"", "--- a/x", "+++ b/x", "@@ -1 +1 @@", "-a", "+b"));
        assertRejected(diff("diff --git a/A.java b/A.java"));                        // header only, no changes
        assertRejected(diff("--- a/A.java", "+++ b/A.java", "@@ -99999999999 +1 @@", "-a", "+b")); // overflow
        assertRejected(diff("diff --git a/A.java b/A.java", "mystery header line"));
    }

    @Test
    void errorsPointAtALine() {
        try {
            UnifiedDiff.parse(diff("--- a/A.java", "+++ b/A.java", "@@ -1 +1 @@", "-a", "?weird"));
            fail("expected an error");
        } catch (UnifiedDiff.DiffFormatException e) {
            assertTrue(e.getMessage().startsWith("line 5"), e.getMessage());
        }
    }
}