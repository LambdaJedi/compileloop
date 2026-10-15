package dev.compileloop.repair;

import dev.compileloop.build.BuildOutputParser;
import dev.compileloop.build.DiagnosticCategory;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.build.ParsedBuild.CompileError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContextBuilderTest {

    @TempDir
    Path project;

    private static final PatchPolicy POLICY = PatchPolicy.defaults();
    private static final ContextBuilder.Limits LIMITS = ContextBuilder.Limits.defaults();

    private static ParsedBuild fixture(String name) throws IOException {
        try (InputStream in = ContextBuilderTest.class.getResourceAsStream("/maven/" + name)) {
            return BuildOutputParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void write(String rel, String content) throws IOException {
        Path p = project.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static CompileError error(String file, int line) {
        return new CompileError(file, line, 1, "cannot find symbol", DiagnosticCategory.SYMBOL_NOT_FOUND, "raw");
    }

    private static ParsedBuild errors(String... files) {
        List<CompileError> list = new ArrayList<>();
        for (String f : files) {
            list.add(error(f, 3));
        }
        return new ParsedBuild(list, List.of(), Optional.empty());
    }

    private RepairContext build(ParsedBuild d) {
        return ContextBuilder.build(project, d, POLICY, LIMITS);
    }

    private static List<String> paths(RepairContext c) {
        return c.files().stream().map(RepairContext.SourceFile::path).toList();
    }

    @Test
    void readsTheFileNamedByACompileErrorEvenWhenTheLogHasAnotherMachinesPath() throws IOException {
        write("src/main/java/fx/Broken.java", "package fx;\nclass Broken {}\n");

        // The real fixture was produced on Windows under C:/Users/Mdu/samples.
        RepairContext c = build(fixture("compile-error.log"));

        assertEquals(List.of("src/main/java/fx/Broken.java"), paths(c));
        assertTrue(c.files().get(0).content().contains("class Broken"));
        assertFalse(c.files().get(0).truncated());
        assertEquals(2, c.diagnostics().compileErrors().size());
    }

    @Test
    void usesTheProjectPrefixWhenTheLogPathMatchesThisMachine() throws IOException {
        write("src/main/java/fx/A.java", "class A {}\n");
        String abs = project.toRealPath().toString().replace('\\', '/') + "/src/main/java/fx/A.java";

        RepairContext c = build(errors(abs));

        assertEquals(List.of("src/main/java/fx/A.java"), paths(c));
    }

    @Test
    void relativizeHandlesPrefixMarkerAndStrangers() {
        assertEquals("src/main/java/A.java", ContextBuilder.relativize("/proj/src/main/java/A.java", "/proj"));
        assertEquals("src/main/java/A.java", ContextBuilder.relativize("/proj/src/main/java/A.java", "/proj/"));
        assertEquals("src/test/java/T.java", ContextBuilder.relativize("C:/Users/x/p/src/test/java/T.java", "/elsewhere"));
        assertEquals("C:/other/Thing.java".equals(ContextBuilder.relativize("C:/other/Thing.java", "/p")), false);
        assertNull(ContextBuilder.relativize("C:/other/Thing.java", "/p"));
        assertEquals("src/main/java/A.java", ContextBuilder.relativize("src/main/java/A.java", "/p"));
    }

    @Test
    void failingTestPullsInTheTestClassAndTheClassUnderTest() throws IOException {
        write("src/test/java/fx/CalcTest.java", "class CalcTest {}\n");
        write("src/main/java/fx/Calc.java", "class Calc {}\n");

        RepairContext c = build(fixture("failing-test.log"));

        assertEquals(List.of("src/test/java/fx/CalcTest.java", "src/main/java/fx/Calc.java"), paths(c));
    }

    @Test
    void neverReadsFilesOutsideTheAllowedRoots() throws IOException {
        write("pom.xml", "<project><password>hunter2</password></project>");
        write("src/main/resources/application.properties", "db.password=hunter2\n");
        write(".env", "TOKEN=hunter2\n");
        write("src/main/java/fx/A.java", "class A {}\n");

        RepairContext c = build(errors("pom.xml", "src/main/resources/application.properties", ".env",
                "/proj/src/main/java/fx/A.java"));

        assertEquals(List.of("src/main/java/fx/A.java"), paths(c));
        assertFalse(c.render().contains("hunter2"), "secrets must never reach the prompt");
    }

    @Test
    void rejectsTraversalInDiagnosticPaths() throws IOException {
        Path outside = Files.createTempFile("secret", ".java");
        Files.writeString(outside, "TOP SECRET\n");
        try {
            RepairContext c = build(errors("src/main/java/../../../" + outside.getFileName(),
                    "/x/src/main/java/../../../../../" + outside.toString().replaceFirst("^/", "")));

            assertEquals(List.of(), paths(c));
            assertFalse(c.render().contains("TOP SECRET"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void skipsMissingFilesQuietly() {
        RepairContext c = build(errors("src/main/java/fx/Ghost.java"));

        assertEquals(List.of(), paths(c));
    }

    @Test
    void skipsSymlinksThatPointOutsideTheProject(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("Evil.java"), "TOP SECRET\n");
        Files.createDirectories(project.resolve("src/main/java"));
        try {
            Files.createSymbolicLink(project.resolve("src/main/java/fx"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }

        RepairContext c = build(errors("src/main/java/fx/Evil.java"));

        assertEquals(List.of(), paths(c));
        assertFalse(c.render().contains("TOP SECRET"));
    }

    @Test
    void truncatesLongFilesAtALineBoundaryAndSaysSo() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append("// line ").append(i).append('\n');
        }
        write("src/main/java/fx/Big.java", big.toString());
        ContextBuilder.Limits tight = new ContextBuilder.Limits(5, 500, 60_000, 20);

        RepairContext c = ContextBuilder.build(project, errors("src/main/java/fx/Big.java"), POLICY, tight);

        RepairContext.SourceFile f = c.files().get(0);
        assertTrue(f.truncated());
        assertTrue(f.content().length() <= 500);
        assertTrue(f.content().endsWith("\n"), "cut at a line boundary");
        assertTrue(c.notes().stream().anyMatch(n -> n.contains("truncated")), c.notes().toString());
        assertTrue(c.render().contains("(truncated)"));
    }

    @Test
    void honoursTheMaxFilesBoundAndReportsWhatWasLeftOut() throws IOException {
        for (String n : List.of("A", "B", "C", "D")) {
            write("src/main/java/fx/" + n + ".java", "class " + n + " {}\n");
        }
        ContextBuilder.Limits two = new ContextBuilder.Limits(2, 20_000, 60_000, 20);

        RepairContext c = ContextBuilder.build(project, errors("src/main/java/fx/A.java", "src/main/java/fx/B.java",
                "src/main/java/fx/C.java", "src/main/java/fx/D.java"), POLICY, two);

        assertEquals(List.of("src/main/java/fx/A.java", "src/main/java/fx/B.java"), paths(c));
        assertTrue(c.notes().stream().anyMatch(n -> n.contains("2 further")), c.notes().toString());
    }

    @Test
    void limitsTheNumberOfDiagnosticsAndSaysSo() {
        String[] many = new String[30];
        for (int i = 0; i < many.length; i++) {
            many[i] = "src/main/java/fx/A.java";
        }

        RepairContext c = ContextBuilder.build(project, errors(many), POLICY,
                new ContextBuilder.Limits(5, 20_000, 60_000, 5));

        assertEquals(5, c.diagnostics().compileErrors().size());
        assertTrue(c.notes().stream().anyMatch(n -> n.contains("limited to the first 5")), c.notes().toString());
    }

    @Test
    void totalBudgetStopsFurtherFilesFromBeingAdded() throws IOException {
        write("src/main/java/fx/A.java", "x".repeat(3000) + "\n");
        write("src/main/java/fx/B.java", "y".repeat(3000) + "\n");
        ContextBuilder.Limits small = new ContextBuilder.Limits(5, 20_000, 6_000, 20);

        RepairContext c = ContextBuilder.build(project, errors("src/main/java/fx/A.java", "src/main/java/fx/B.java"),
                POLICY, small);

        int total = c.files().stream().mapToInt(f -> f.content().length()).sum();
        assertTrue(total < 6_000, "file text must stay inside the total budget, was " + total);
        assertTrue(c.files().size() >= 1);
    }

    @Test
    void sameFailureAlwaysGivesTheSameContextText() throws IOException {
        write("src/test/java/fx/CalcTest.java", "class CalcTest {}\n");
        write("src/main/java/fx/Calc.java", "class Calc {}\n");

        String one = build(fixture("failing-test.log")).render();
        String two = build(fixture("failing-test.log")).render();

        assertEquals(one, two);
    }

    @Test
    void renderedPromptStatesTheRulesAndTheDiagnostics() throws IOException {
        write("src/main/java/fx/Broken.java", "class Broken {}\n");

        String text = build(fixture("compile-error.log")).render();

        assertTrue(text.contains("exactly one JSON object"));
        assertTrue(text.contains("src/main/java, src/test/java"));
        assertTrue(text.contains("Do not modify build configuration"));
        assertTrue(text.contains("incompatible types"));
        assertTrue(text.contains("### src/main/java/fx/Broken.java"));
    }
    @Test
    void promptExampleIsNotTheDemoFix() {
        // The worked example in the prompt must not be usable as-is to repair
        // the demo fixture, or the demo proves nothing about the model's reasoning.
        // Guards against a future contributor "helpfully" making the example match.
        RepairContext ctx = build(ParsedBuild.empty());
        String prompt = ctx.render();

        assertFalse(prompt.contains("return \"x\""), "prompt contains the demo's broken line");
        assertFalse(prompt.contains("return a + b"), "prompt contains the demo's fix");
        assertFalse(prompt.contains("class Calc"), "prompt names the demo class");
        assertFalse(prompt.contains("int add("), "prompt names the demo method");
        assertFalse(prompt.contains("fx.Calc"), "prompt names the demo package");
        // and it should still contain a well-formed example, just an unrelated one
        assertTrue(prompt.contains("Greeter"), "prompt should show a worked example");
        assertTrue(prompt.contains("--- a/"), "prompt example should show the diff header format");
        assertTrue(prompt.contains("+++ b/"), "prompt example should show the diff header format");
    }

    @Test
    void emptyDiagnosticsStillProduceAValidContext() {
        RepairContext c = build(ParsedBuild.empty());

        assertEquals(List.of(), paths(c));
        assertTrue(c.render().contains("no structured diagnostics"));
    }

    @Test
    void protectedOrUnlistedExtensionsAreNotReadEither() throws IOException {
        write("src/main/java/fx/notes.txt", "x\n");

        RepairContext c = build(errors("src/main/java/fx/notes.txt"));

        assertEquals(List.of(), paths(c));
    }
}
