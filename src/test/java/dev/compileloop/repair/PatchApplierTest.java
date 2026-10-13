package dev.compileloop.repair;

import dev.compileloop.repair.UnifiedDiff.FilePatch;
import dev.compileloop.repair.UnifiedDiff.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchApplierTest {

    @TempDir
    Path ws;

    private static String diff(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private void write(String rel, String content) throws IOException {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String read(String rel) throws IOException {
        return Files.readString(ws.resolve(rel));
    }

    private PatchApplier.ApplyResult apply(String diff) throws Exception {
        return PatchApplier.apply(UnifiedDiff.parse(diff), ws);
    }

    private static final String CALC = "package fx;\npublic class Calc {\n    int add(int a, int b) {\n"
            + "        return \"x\";\n    }\n}\n";

    private static final String CALC_FIX = diff(
            "--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
            "@@ -2,4 +2,4 @@",
            " public class Calc {",
            "     int add(int a, int b) {",
            "-        return \"x\";",
            "+        return a + b;",
            "     }");

    @Test
    void appliesASimpleEdit() throws Exception {
        write("src/main/java/fx/Calc.java", CALC);

        PatchApplier.ApplyResult r = apply(CALC_FIX);

        assertTrue(r.applied(), r.errors().toString());
        assertEquals(CALC.replace("return \"x\";", "return a + b;"), read("src/main/java/fx/Calc.java"));
        assertEquals(1, r.changes().size());
        assertEquals(Kind.MODIFY, r.changes().get(0).kind());
    }

    @Test
    void toleratesWrongLineNumbersWithinTheWindow() throws Exception {
        write("src/main/java/fx/Calc.java", CALC);
        String shifted = CALC_FIX.replace("@@ -2,4 +2,4 @@", "@@ -9,4 +9,4 @@");

        assertTrue(apply(shifted).applied());
        assertTrue(read("src/main/java/fx/Calc.java").contains("return a + b;"));
    }

    @Test
    void failsWhenTheContextIsNotInTheFile() throws Exception {
        write("src/main/java/fx/Calc.java", CALC);
        String wrong = CALC_FIX.replace("-        return \"x\";", "-        return \"y\";");

        PatchApplier.ApplyResult r = apply(wrong);

        assertFalse(r.applied());
        assertTrue(r.errors().get(0).contains("were not found"), r.errors().toString());
        assertEquals(CALC, read("src/main/java/fx/Calc.java"), "nothing may be written");
    }

    @Test
    void doesNotAcceptWhitespaceDifferences() throws Exception {
        write("src/main/java/fx/Calc.java", CALC);
        String wrong = CALC_FIX.replace("-        return \"x\";", "-    return \"x\";");

        assertFalse(apply(wrong).applied());
    }

    @Test
    void isAllOrNothingAcrossFiles() throws Exception {
        write("src/main/java/fx/Calc.java", CALC);
        write("src/main/java/fx/Other.java", "class Other {\n}\n");
        String d = CALC_FIX + diff(
                "--- a/src/main/java/fx/Other.java", "+++ b/src/main/java/fx/Other.java",
                "@@ -1,2 +1,2 @@", "-class Missing {", "+class Other2 {", " }");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertEquals(CALC, read("src/main/java/fx/Calc.java"), "the good file must stay untouched too");
        assertEquals("class Other {\n}\n", read("src/main/java/fx/Other.java"));
    }

    @Test
    void refusesToGuessWhenTwoPlacesMatchEquallyWell() throws Exception {
        write("src/main/java/fx/Dup.java", "a\nX\nb\nc\nd\nX\ne\n");
        String d = diff("--- a/src/main/java/fx/Dup.java", "+++ b/src/main/java/fx/Dup.java",
                "@@ -4,1 +4,1 @@", "-X", "+Y");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertTrue(r.errors().get(0).contains("refusing to guess"), r.errors().toString());
    }

    @Test
    void picksTheNearestMatchWhenOneIsCloser() throws Exception {
        write("src/main/java/fx/Dup.java", "a\nX\nb\nc\nd\nX\ne\n");
        String d = diff("--- a/src/main/java/fx/Dup.java", "+++ b/src/main/java/fx/Dup.java",
                "@@ -2,1 +2,1 @@", "-X", "+Y");

        assertTrue(apply(d).applied());
        assertEquals("a\nY\nb\nc\nd\nX\ne\n", read("src/main/java/fx/Dup.java"));
    }

    @Test
    void appliesSeveralHunksInOrder() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("line").append(i).append('\n');
        }
        write("src/main/java/fx/Big.java", sb.toString());
        String d = diff("--- a/src/main/java/fx/Big.java", "+++ b/src/main/java/fx/Big.java",
                "@@ -2,3 +2,3 @@", " line2", "-line3", "+LINE3", " line4",
                "@@ -20,3 +20,4 @@", " line20", " line21", "+inserted", " line22");

        assertTrue(apply(d).applied());
        String out = read("src/main/java/fx/Big.java");
        assertTrue(out.contains("line2\nLINE3\nline4\n"));
        assertTrue(out.contains("line21\ninserted\nline22\n"));
        assertFalse(out.contains("line3\n"));
    }

    @Test
    void rejectsHunksThatGoBackwardsOrOverlap() throws Exception {
        write("src/main/java/fx/Big.java", "a\nb\nc\nd\ne\nf\n");
        String d = diff("--- a/src/main/java/fx/Big.java", "+++ b/src/main/java/fx/Big.java",
                "@@ -4,1 +4,1 @@", "-d", "+D",
                "@@ -2,1 +2,1 @@", "-b", "+B");

        assertFalse(apply(d).applied());
        assertEquals("a\nb\nc\nd\ne\nf\n", read("src/main/java/fx/Big.java"));
    }

    @Test
    void insertsAtTheTopOfAFile() throws Exception {
        write("src/main/java/fx/A.java", "class A {}\n");
        String d = diff("--- a/src/main/java/fx/A.java", "+++ b/src/main/java/fx/A.java",
                "@@ -0,0 +1,2 @@", "+package fx;", "+");

        assertTrue(apply(d).applied());
        assertEquals("package fx;\n\nclass A {}\n", read("src/main/java/fx/A.java"));
    }

    @Test
    void keepsWindowsLineEndingsInEditedFiles() throws Exception {
        write("src/main/java/fx/Win.java", "one\r\ntwo\r\nthree\r\n");
        String d = diff("--- a/src/main/java/fx/Win.java", "+++ b/src/main/java/fx/Win.java",
                "@@ -1,3 +1,4 @@", " one", " two", "+two and a half", " three");

        assertTrue(apply(d).applied());
        assertEquals("one\r\ntwo\r\ntwo and a half\r\nthree\r\n", read("src/main/java/fx/Win.java"));
    }

    @Test
    void matchesContextEvenWhenTheDiffWasWrittenWithCrlf() throws Exception {
        write("src/main/java/fx/Win.java", "one\r\ntwo\r\n");
        String d = diff("--- a/src/main/java/fx/Win.java", "+++ b/src/main/java/fx/Win.java",
                "@@ -1,2 +1,2 @@", " one", "-two", "+TWO").replace("\n", "\r\n");

        assertTrue(apply(d).applied());
        assertEquals("one\r\nTWO\r\n", read("src/main/java/fx/Win.java"));
    }

    @Test
    void handlesFilesWithoutATrailingNewline() throws Exception {
        write("src/main/java/fx/NoNl.java", "a\nb");
        String d = diff("--- a/src/main/java/fx/NoNl.java", "+++ b/src/main/java/fx/NoNl.java",
                "@@ -1,2 +1,3 @@", " a", " b", "\\ No newline at end of file", "+c");

        assertTrue(apply(d).applied());
        assertEquals("a\nb\nc\n", read("src/main/java/fx/NoNl.java"), "the old last line must get a line break");
    }

    @Test
    void honoursNoNewlineMarkerOnAddedLines() throws Exception {
        write("src/main/java/fx/NoNl.java", "a\nb\n");
        String d = diff("--- a/src/main/java/fx/NoNl.java", "+++ b/src/main/java/fx/NoNl.java",
                "@@ -1,2 +1,2 @@", " a", "-b", "+B", "\\ No newline at end of file");

        assertTrue(apply(d).applied());
        assertEquals("a\nB", read("src/main/java/fx/NoNl.java"));
    }

    @Test
    void createsANewFileAndItsFolders() throws Exception {
        String d = diff("--- /dev/null", "+++ b/src/main/java/fx/sub/New.java",
                "@@ -0,0 +1,2 @@", "+package fx.sub;", "+class New {}");

        PatchApplier.ApplyResult r = apply(d);

        assertTrue(r.applied(), r.errors().toString());
        assertEquals("package fx.sub;\nclass New {}\n", read("src/main/java/fx/sub/New.java"));
        assertEquals(Kind.CREATE, r.changes().get(0).kind());
    }

    @Test
    void refusesToCreateOverAnExistingFile() throws Exception {
        write("src/main/java/fx/New.java", "old\n");
        String d = diff("--- /dev/null", "+++ b/src/main/java/fx/New.java", "@@ -0,0 +1,1 @@", "+x");

        assertFalse(apply(d).applied());
        assertEquals("old\n", read("src/main/java/fx/New.java"));
    }

    @Test
    void deletesAFileOnlyWhenTheDiffShowsItsWholeContent() throws Exception {
        write("src/main/java/fx/Gone.java", "a\nb\n");
        String ok = diff("--- a/src/main/java/fx/Gone.java", "+++ /dev/null", "@@ -1,2 +0,0 @@", "-a", "-b");
        String partial = diff("--- a/src/main/java/fx/Gone.java", "+++ /dev/null", "@@ -1,1 +0,0 @@", "-a");

        assertFalse(apply(partial).applied());
        assertTrue(Files.exists(ws.resolve("src/main/java/fx/Gone.java")));
        assertTrue(apply(ok).applied());
        assertFalse(Files.exists(ws.resolve("src/main/java/fx/Gone.java")));
    }

    @Test
    void failsOnAMissingFile() throws Exception {
        String d = diff("--- a/src/main/java/fx/Ghost.java", "+++ b/src/main/java/fx/Ghost.java",
                "@@ -1,1 +1,1 @@", "-a", "+b");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertTrue(r.errors().get(0).contains("does not exist"), r.errors().toString());
    }

    @Test
    void failsOnAFileThatIsNotUtf8() throws Exception {
        Path p = ws.resolve("src/main/java/fx/Bin.java");
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{'a', '\n', (byte) 0xff, (byte) 0xfe, '\n'});
        String d = diff("--- a/src/main/java/fx/Bin.java", "+++ b/src/main/java/fx/Bin.java",
                "@@ -1,1 +1,1 @@", "-a", "+b");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertTrue(r.errors().get(0).contains("UTF-8"), r.errors().toString());
    }

    @Test
    void keepsNonAsciiTextIntact() throws Exception {
        write("src/main/java/fx/U.java", "// café é\nint x = 1;\n");
        String d = diff("--- a/src/main/java/fx/U.java", "+++ b/src/main/java/fx/U.java",
                "@@ -1,2 +1,2 @@", " // café é", "-int x = 1;", "+int x = 2;");

        assertTrue(apply(d).applied());
        assertEquals("// café é\nint x = 2;\n",
                new String(Files.readAllBytes(ws.resolve("src/main/java/fx/U.java")), StandardCharsets.UTF_8));
    }

    @Test
    void refusesRenamesAndCopies() throws Exception {
        write("src/main/java/fx/Old.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Old.java b/src/main/java/fx/New.java",
                "similarity index 100%", "rename from src/main/java/fx/Old.java",
                "rename to src/main/java/fx/New.java");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertTrue(Files.exists(ws.resolve("src/main/java/fx/Old.java")));
        assertFalse(Files.exists(ws.resolve("src/main/java/fx/New.java")));
    }

    @Test
    void refusesPatchesThatChangeNothing() throws Exception {
        write("src/main/java/fx/Same.java", "a\n");
        String d = diff("--- a/src/main/java/fx/Same.java", "+++ b/src/main/java/fx/Same.java",
                "@@ -1,1 +1,1 @@", "-a", "+a");

        PatchApplier.ApplyResult r = apply(d);

        assertFalse(r.applied());
        assertTrue(r.errors().get(0).contains("would not change"), r.errors().toString());
    }

    @Test
    void refusesAnEmptyPatchList() {
        assertFalse(PatchApplier.apply(List.of(), ws).applied());
    }

    @Test
    void stillRefusesPathsOutsideTheWorkspaceEvenIfValidationWasSkipped() throws Exception {
        Path outside = Files.createTempFile("outside", ".java");
        try {
            Files.writeString(outside, "a\n");
            FilePatch evil = new FilePatch("../" + outside.getFileName(), "../" + outside.getFileName(),
                    Kind.MODIFY, false, false, null,
                    List.of(new UnifiedDiff.Hunk(1, 1, 1, 1, List.of("-a", "+HACKED"))), 1, 1);
            Path nested = Files.createDirectories(ws.resolve("deep/er"));

            PatchApplier.ApplyResult r = PatchApplier.apply(List.of(evil), nested);

            assertFalse(r.applied());
            assertEquals("a\n", Files.readString(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void refusesAWorkspaceThatDoesNotExist() throws Exception {
        PatchApplier.ApplyResult r = PatchApplier.apply(UnifiedDiff.parse(CALC_FIX), ws.resolve("nope"));

        assertFalse(r.applied());
    }

    @Test
    void refusesResultsThatAreAbsurdlyLarge() throws Exception {
        write("src/main/java/fx/A.java", "a\n");
        StringBuilder d = new StringBuilder("--- a/src/main/java/fx/A.java\n+++ b/src/main/java/fx/A.java\n");
        int n = 3000;
        d.append("@@ -1,1 +1,").append(n + 1).append(" @@\n a\n");
        for (int i = 0; i < n; i++) {
            d.append('+').append("x".repeat(400)).append('\n');
        }

        PatchApplier.ApplyResult r = PatchApplier.apply(UnifiedDiff.parse(d.toString()), ws);

        assertFalse(r.applied());
        assertEquals("a\n", read("src/main/java/fx/A.java"));
    }
}
