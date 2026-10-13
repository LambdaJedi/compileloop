package dev.compileloop.repair;

import dev.compileloop.repair.ValidationResult.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Attack-style tests: the diff is hostile input and must never get past the validator. */
class PatchValidatorTest {

    @TempDir
    Path ws;

    private static final PatchPolicy POLICY = PatchPolicy.defaults();

    private static String diff(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static PatchProposal proposal(String diff) {
        return new PatchProposal("s", diff, List.of(), "raw");
    }

    /** A modify patch of {@code path}. */
    private static String modify(String path) {
        return diff("--- a/" + path, "+++ b/" + path, "@@ -1,1 +1,1 @@", "-old", "+new");
    }

    private static String create(String path) {
        return diff("--- /dev/null", "+++ b/" + path, "@@ -0,0 +1,1 @@", "+new");
    }

    private void write(String rel, String content) throws IOException {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private ValidationResult validate(String diff) {
        return PatchValidator.validate(proposal(diff), POLICY, ws);
    }

    private ValidationResult validate(String diff, PatchPolicy policy) {
        return PatchValidator.validate(proposal(diff), policy, ws);
    }

    private void assertRejectedWith(Code expected, String diff) {
        ValidationResult r = validate(diff);
        assertFalse(r.accepted(), "must be rejected: " + diff);
        assertTrue(r.has(expected), "expected " + expected + " but got " + r.violations());
    }

    // ---- the good path ---------------------------------------------------------

    @Test
    void acceptsASmallEditToAnExistingSourceFile() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");

        ValidationResult r = validate(modify("src/main/java/fx/Calc.java"));

        assertTrue(r.accepted(), r.violations().toString());
        assertEquals(1, r.files().size());
    }

    @Test
    void acceptsEditsToTestSourcesToo() throws IOException {
        write("src/test/java/fx/CalcTest.java", "old\n");

        assertTrue(validate(modify("src/test/java/fx/CalcTest.java")).accepted());
    }

    @Test
    void acceptsCreatingANewJavaFileWhenAllowed() {
        assertTrue(validate(create("src/main/java/fx/New.java")).accepted());
    }

    // ---- path attacks ----------------------------------------------------------

    @Test
    void rejectsParentTraversal() {
        assertRejectedWith(Code.PATH_TRAVERSAL, modify("../../etc/passwd"));
        assertRejectedWith(Code.PATH_TRAVERSAL, modify("src/main/java/../../../outside/Evil.java"));
    }

    @Test
    void rejectsAbsolutePathsAndDriveLetters() {
        assertRejectedWith(Code.ABSOLUTE_PATH, modify("/etc/passwd"));
        assertRejectedWith(Code.ABSOLUTE_PATH, modify("C:/Windows/win.ini"));
        assertRejectedWith(Code.ABSOLUTE_PATH, modify("~/.ssh/authorized_keys"));
    }

    @Test
    void rejectsBackslashes() {
        assertRejectedWith(Code.INVALID_PATH, modify("src\\main\\java\\fx\\Calc.java"));
    }

    @Test
    void rejectsColonsSuchAsAlternateDataStreams() {
        assertRejectedWith(Code.INVALID_PATH, modify("src/main/java/fx/Calc.java:stream"));
    }

    @Test
    void rejectsReservedWindowsNames() {
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/CON.java"));
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/nul.java"));
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/aux/Thing.java"));
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/LPT1.java"));
    }

    @Test
    void rejectsTrailingDotsAndSpacesInSegments() {
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/Calc.java."));
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx /Calc.java"));
    }

    @Test
    void rejectsEmptyAndDotSegments() {
        assertRejectedWith(Code.INVALID_PATH, create("src/main//java/fx/Calc.java"));
        assertRejectedWith(Code.INVALID_PATH, create("src/main/./java/fx/Calc.java"));
    }

    @Test
    void rejectsControlCharactersInPaths() {
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/Ca\u0001lc.java"));
        // A TAB in a ---/+++ header starts the (ignored) timestamp, as in every unified diff
        // reader, so it can never become part of a path that is later applied.
        assertRejectedWith(Code.INVALID_PATH, create("src/main/java/fx/Ca\u007flc.java"));
    }

    // ---- files that may never change ---------------------------------------------

    @Test
    void rejectsBuildFilesCiAndGitInternals() throws IOException {
        write("pom.xml", "x\n");
        assertRejectedWith(Code.FORBIDDEN_PATH, modify("pom.xml"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".github/workflows/ci.yml"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".git/hooks/pre-commit"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".mvn/wrapper/maven-wrapper.properties"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify("mvnw"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify("mvnw.cmd"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify("target/classes/fx/Calc.java"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".compileloop/state.json"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".idea/workspace.xml"));
    }

    @Test
    void forbiddenSegmentsAreRejectedEvenInsideAllowedRoots() {
        assertRejectedWith(Code.FORBIDDEN_PATH, create("src/main/java/target/Calc.java"));
        assertRejectedWith(Code.FORBIDDEN_PATH, create("src/main/java/.git/Calc.java"));
    }

    @Test
    void forbiddenSegmentsAreMatchedCaseInsensitively() {
        assertRejectedWith(Code.FORBIDDEN_PATH, create("src/main/java/TARGET/Calc.java"));
        assertRejectedWith(Code.FORBIDDEN_PATH, modify(".GitHub/workflows/ci.yml"));
    }

    @Test
    void rejectsScriptsAndExecutablesEvenUnderAllowedRoots() {
        Code[] any = {Code.FORBIDDEN_PATH};
        for (String name : List.of("run.sh", "run.cmd", "run.bat", "run.ps1", "x.yml", "x.jar", "X.class", "x.exe")) {
            ValidationResult r = validate(create("src/main/java/fx/" + name));
            assertFalse(r.accepted(), name);
            assertTrue(r.has(any[0]), name + " -> " + r.violations());
        }
    }

    // ---- policy -----------------------------------------------------------------

    @Test
    void rejectsFilesOutsideTheAllowedRoots() throws IOException {
        write("README.md", "x\n");
        assertRejectedWith(Code.OUTSIDE_ALLOWED_ROOTS, modify("README.md"));
        assertRejectedWith(Code.OUTSIDE_ALLOWED_ROOTS, create("src/main/resources/App.java"));
        assertRejectedWith(Code.OUTSIDE_ALLOWED_ROOTS, create("Other.java"));
    }

    @Test
    void allowedRootsAreMatchedOnSegmentBoundaries() {
        assertRejectedWith(Code.OUTSIDE_ALLOWED_ROOTS, create("src/main/javaX/fx/Calc.java"));
    }

    @Test
    void rootsAreCaseSensitive() {
        assertRejectedWith(Code.OUTSIDE_ALLOWED_ROOTS, create("SRC/main/java/fx/Calc.java"));
    }

    @Test
    void rejectsDisallowedExtensions() {
        assertRejectedWith(Code.DISALLOWED_EXTENSION, create("src/main/java/fx/notes.txt"));
        assertRejectedWith(Code.DISALLOWED_EXTENSION, create("src/main/java/fx/Calc.kt"));
    }

    @Test
    void rejectsProtectedPaths() throws IOException {
        write("src/main/java/fx/Secret.java", "old\n");
        PatchPolicy policy = POLICY.withProtectedPaths(List.of("src/main/java/fx/Secret.java", "src/test/java/golden"));

        ValidationResult r1 = validate(modify("src/main/java/fx/Secret.java"), policy);
        ValidationResult r2 = validate(create("src/test/java/golden/Case.java"), policy);

        assertTrue(r1.has(Code.PROTECTED_PATH), r1.violations().toString());
        assertTrue(r2.has(Code.PROTECTED_PATH), r2.violations().toString());
        assertFalse(r1.accepted());
        assertFalse(r2.accepted());
    }

    // ---- operation types ----------------------------------------------------------

    @Test
    void rejectsDeletionsByDefault() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        String d = diff("--- a/src/main/java/fx/Calc.java", "+++ /dev/null", "@@ -1,1 +0,0 @@", "-old");

        assertRejectedWith(Code.DELETE_NOT_ALLOWED, d);
    }

    @Test
    void deletionsCanBeEnabledByPolicy() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        String d = diff("--- a/src/main/java/fx/Calc.java", "+++ /dev/null", "@@ -1,1 +0,0 @@", "-old");

        assertTrue(validate(d, POLICY.withAllowDelete(true)).accepted());
    }

    @Test
    void rejectsCreationWhenDisabled() {
        ValidationResult r = validate(create("src/main/java/fx/New.java"), POLICY.withAllowCreate(false));

        assertTrue(r.has(Code.CREATE_NOT_ALLOWED));
        assertFalse(r.accepted());
    }

    @Test
    void rejectsRenames() throws IOException {
        write("src/main/java/fx/Old.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Old.java b/src/main/java/fx/New.java",
                "similarity index 100%",
                "rename from src/main/java/fx/Old.java",
                "rename to src/main/java/fx/New.java");

        assertRejectedWith(Code.RENAME_NOT_ALLOWED, d);
    }

    @Test
    void renameCannotSmuggleAFileOutOfTheAllowedRoots() throws IOException {
        write("src/main/java/fx/Old.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Old.java b/pom.xml",
                "similarity index 100%",
                "rename from src/main/java/fx/Old.java",
                "rename to pom.xml");

        ValidationResult r = validate(d);

        assertFalse(r.accepted());
        assertTrue(r.has(Code.RENAME_NOT_ALLOWED));
        assertTrue(r.has(Code.FORBIDDEN_PATH), "the destination is checked too: " + r.violations());
    }

    @Test
    void rejectsCopies() throws IOException {
        write("src/main/java/fx/Old.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Old.java b/src/main/java/fx/Copy.java",
                "similarity index 100%",
                "copy from src/main/java/fx/Old.java",
                "copy to src/main/java/fx/Copy.java");

        assertRejectedWith(Code.COPY_NOT_ALLOWED, d);
    }

    @Test
    void rejectsBinaryPatches() throws IOException {
        write("src/main/java/fx/Calc.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Calc.java b/src/main/java/fx/Calc.java",
                "index 83db48f..bf2a3c1 100644",
                "GIT binary patch",
                "literal 10",
                "Jc$@KB000000");

        assertRejectedWith(Code.BINARY_PATCH, d);
    }

    @Test
    void rejectsNulBytesInsideTheDiff() {
        assertRejectedWith(Code.BINARY_PATCH, modify("src/main/java/fx/Calc.java") + "\0");
    }

    @Test
    void rejectsModeChanges() throws IOException {
        write("src/main/java/fx/Calc.java", "x\n");
        String d = diff("diff --git a/src/main/java/fx/Calc.java b/src/main/java/fx/Calc.java",
                "old mode 100644", "new mode 100755");

        assertRejectedWith(Code.MODE_CHANGE, d);
    }

    @Test
    void rejectsCreatingExecutableFiles() {
        String d = diff("diff --git a/src/main/java/fx/New.java b/src/main/java/fx/New.java",
                "new file mode 100755",
                "--- /dev/null", "+++ b/src/main/java/fx/New.java", "@@ -0,0 +1,1 @@", "+x");

        assertRejectedWith(Code.MODE_CHANGE, d);
    }

    @Test
    void rejectsCreatingSymbolicLinks() {
        String d = diff("diff --git a/src/main/java/fx/Link.java b/src/main/java/fx/Link.java",
                "new file mode 120000",
                "--- /dev/null", "+++ b/src/main/java/fx/Link.java", "@@ -0,0 +1,1 @@", "+/etc/passwd");

        assertRejectedWith(Code.SYMLINK_NOT_ALLOWED, d);
    }

    // ---- limits ------------------------------------------------------------------

    @Test
    void rejectsEmptyDiffs() {
        assertRejectedWith(Code.EMPTY_DIFF, "");
        assertRejectedWith(Code.EMPTY_DIFF, "   \n\n");
    }

    @Test
    void rejectsMalformedDiffs() {
        assertRejectedWith(Code.MALFORMED_DIFF, "this is not a diff\n");
        // A hunk header that doesn't match the "--- +N[,M] +N[,M]" pattern is still
        // rejected. (An accepted hunk with a wrong count is handled by recount.)
        assertRejectedWith(Code.MALFORMED_DIFF,
                diff("--- a/src/main/java/fx/Calc.java", "+++ b/src/main/java/fx/Calc.java",
                        "@@ not a header @@", "-old"));
    }

    @Test
    void rejectsOversizedDiffsBeforeParsingThem() {
        PatchPolicy tiny = POLICY.withMaxDiffChars(50);

        ValidationResult r = validate(modify("src/main/java/fx/Calc.java"), tiny);

        assertTrue(r.has(Code.DIFF_TOO_LARGE));
        assertEquals(List.of(), r.files(), "an oversized diff is not parsed at all");
    }

    @Test
    void rejectsTooManyFiles() throws IOException {
        PatchPolicy two = POLICY.withMaxFiles(2);
        String d = create("src/main/java/fx/A.java") + create("src/main/java/fx/B.java")
                + create("src/main/java/fx/C.java");

        assertTrue(validate(d, two).has(Code.TOO_MANY_FILES));
        assertTrue(validate(d, POLICY).accepted());
    }

    @Test
    void rejectsTooManyChangedLines() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        PatchPolicy small = POLICY.withMaxChangedLines(1);

        ValidationResult r = validate(modify("src/main/java/fx/Calc.java"), small);

        assertTrue(r.has(Code.TOO_MANY_LINES), "one removed + one added = 2 changed lines");
    }

    @Test
    void rejectsTheSameFileTwiceEvenWithDifferentCase() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        String d = modify("src/main/java/fx/Calc.java") + modify("src/main/java/fx/Calc.java");

        assertRejectedWith(Code.DUPLICATE_FILE, d);
        assertRejectedWith(Code.DUPLICATE_FILE,
                create("src/main/java/fx/Dup.java") + create("src/main/java/fx/DUP.java"));
    }

    // ---- filesystem facts -----------------------------------------------------------

    @Test
    void rejectsModifyingAFileThatDoesNotExist() {
        assertRejectedWith(Code.FILE_MISSING, modify("src/main/java/fx/Ghost.java"));
    }

    @Test
    void rejectsCreatingAFileThatAlreadyExists() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");

        assertRejectedWith(Code.FILE_EXISTS, create("src/main/java/fx/Calc.java"));
    }

    @Test
    void rejectsPathsThroughSymlinksThatEscapeTheWorkspace(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("Evil.java"), "old\n");
        Path link = ws.resolve("src/main/java/fx");
        Files.createDirectories(link.getParent());
        boolean linked = tryLink(link, outside);
        assumeTrue(linked, "symbolic links are not available here");

        ValidationResult r = validate(modify("src/main/java/fx/Evil.java"));

        assertFalse(r.accepted());
        assertTrue(r.has(Code.SYMLINK_ESCAPE), r.violations().toString());
        assertEquals("old\n", Files.readString(outside.resolve("Evil.java")), "validation never writes");
    }

    @Test
    void rejectsSymlinkedFilesEvenWhenTheyPointInsideTheWorkspace() throws IOException {
        write("src/main/java/fx/Real.java", "old\n");
        Path link = ws.resolve("src/main/java/fx/Alias.java");
        boolean linked = tryLink(link, ws.resolve("src/main/java/fx/Real.java"));
        assumeTrue(linked, "symbolic links are not available here");

        ValidationResult r = validate(modify("src/main/java/fx/Alias.java"));

        assertFalse(r.accepted());
        assertTrue(r.has(Code.SYMLINK_ESCAPE), r.violations().toString());
    }

    private static boolean tryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    @Test
    void reportsWhenTheWorkspaceDoesNotExist() {
        ValidationResult r = PatchValidator.validate(proposal(modify("src/main/java/fx/Calc.java")),
                POLICY, ws.resolve("no-such-dir"));

        assertFalse(r.accepted());
        assertTrue(r.has(Code.WORKSPACE_UNAVAILABLE));
    }

    // ---- behaviour -------------------------------------------------------------------

    @Test
    void collectsEveryViolationNotJustTheFirst() throws IOException {
        write("pom.xml", "x\n");
        String d = modify("../../etc/passwd") + modify("pom.xml") + create("src/main/java/fx/run.sh");

        ValidationResult r = validate(d);

        assertFalse(r.accepted());
        assertTrue(r.has(Code.PATH_TRAVERSAL));
        assertTrue(r.has(Code.FORBIDDEN_PATH));
        String paths = r.violations().stream().map(ValidationResult.Violation::path).collect(Collectors.joining(","));
        assertTrue(paths.contains("pom.xml") && paths.contains("run.sh"), paths);
    }

    @Test
    void oneBadFileRejectsTheWholePatch() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        String d = modify("src/main/java/fx/Calc.java") + modify("pom.xml");

        assertFalse(validate(d).accepted());
    }

    @Test
    void validationNeverWritesToTheWorkspace() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        validate(modify("src/main/java/fx/Calc.java") + create("src/main/java/fx/New.java"));

        try (Stream<Path> s = Files.walk(ws)) {
            List<String> names = s.filter(Files::isRegularFile).map(p -> ws.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
            assertEquals(List.of("src/main/java/fx/Calc.java"), names);
        }
        assertEquals("old\n", Files.readString(ws.resolve("src/main/java/fx/Calc.java")));
    }

    @Test
    void acceptsDiffsWithWindowsLineEndings() throws IOException {
        write("src/main/java/fx/Calc.java", "old\n");
        String crlf = modify("src/main/java/fx/Calc.java").replace("\n", "\r\n");

        assertTrue(validate(crlf).accepted(), validate(crlf).violations().toString());
    }

    @Test
    void forbiddenPathMessagesNeverEchoControlCharactersBack() {
        ValidationResult r = validate(create("src/main/java/fx/Ca\u001blc.java"));

        for (ValidationResult.Violation v : r.violations()) {
            assertFalse(v.toString().indexOf('\u001b') >= 0, "escape characters must not reach logs");
        }
    }
}
