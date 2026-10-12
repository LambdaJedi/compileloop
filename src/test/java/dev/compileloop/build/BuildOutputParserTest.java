package dev.compileloop.build;

import dev.compileloop.build.ParsedBuild.CompileError;
import dev.compileloop.build.ParsedBuild.TestFailure;
import dev.compileloop.build.ParsedBuild.TestSummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures in src/test/resources/maven are real Maven 3.9 output captured on Windows. */
class BuildOutputParserTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = BuildOutputParserTest.class.getResourceAsStream("/maven/" + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesCompileErrorsFromRealOutput() throws IOException {
        ParsedBuild b = BuildOutputParser.parse(resource("compile-error.log"));

        List<CompileError> errors = b.compileErrors();
        assertEquals(2, errors.size(), "block and summary copies must not double-count");

        CompileError first = errors.get(0);
        assertEquals("C:/Users/Mdu/samples/src/main/java/fx/Broken.java", first.file());
        assertEquals(3, first.line());
        assertEquals(13, first.column());
        assertEquals("incompatible types: java.lang.String cannot be converted to int", first.message());

        CompileError second = errors.get(1);
        assertEquals(4, second.line());
        assertEquals(16, second.column());
        assertEquals("cannot find symbol\nsymbol:   method undefinedMethod()\nlocation: class fx.Broken",
                second.message());

        assertTrue(b.hasCompileErrors());
        assertFalse(b.hasTestFailures());
        assertEquals(Optional.empty(), b.testSummary());
    }

    @Test
    void parsesTestFailureFromRealOutput() throws IOException {
        ParsedBuild b = BuildOutputParser.parse(resource("failing-test.log"));

        assertFalse(b.hasCompileErrors());
        assertEquals(1, b.testFailures().size());

        TestFailure f = b.testFailures().get(0);
        assertEquals("fx.CalcTest", f.testClass());
        assertEquals("adds", f.testMethod());
        assertEquals("FAILURE", f.kind());
        assertEquals("org.opentest4j.AssertionFailedError: expected: <5> but was: <4>", f.message());
        assertEquals(5, f.line(), "line comes from the first stack frame inside the test class");

        assertEquals(Optional.of(new TestSummary(2, 1, 0, 0)), b.testSummary());
    }

    @Test
    void summaryOnlyFallbackWhenBlockIsMissing() throws IOException {
        // Simulates output truncated from the front: only the final goal-failure summary survives.
        String full = resource("compile-error.log");
        String tail = full.substring(full.indexOf("[ERROR] Failed to execute goal"));

        List<CompileError> errors = BuildOutputParser.parse(tail).compileErrors();

        assertEquals(2, errors.size());
        assertEquals(3, errors.get(0).line());
        assertEquals("cannot find symbol\nsymbol:   method undefinedMethod()\nlocation: class fx.Broken",
                errors.get(1).message());
    }

    @Test
    void passingBuildHasNoProblems() {
        String out = """
                [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.1 s -- in fx.T
                [INFO]
                [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1
                [INFO] BUILD SUCCESS
                """;

        ParsedBuild b = BuildOutputParser.parse(out);

        assertFalse(b.hasCompileErrors());
        assertFalse(b.hasTestFailures());
        assertEquals(Optional.of(new TestSummary(3, 0, 0, 1)), b.testSummary());
    }

    @Test
    void unexpectedExceptionIsReportedAsError() {
        String out = String.join("\n",
                "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.05 s <<< FAILURE! -- in fx.NpeTest",
                "[ERROR] fx.NpeTest.boom -- Time elapsed: 0.01 s <<< ERROR!",
                "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"s\" is null",
                "        at fx.Util.len(Util.java:9)",
                "        at fx.NpeTest.boom(NpeTest.java:12)",
                "        at java.base/java.lang.reflect.Method.invoke(Method.java:569)",
                "",
                "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0");

        ParsedBuild b = BuildOutputParser.parse(out);

        TestFailure f = b.testFailures().get(0);
        assertEquals("ERROR", f.kind());
        assertEquals("boom", f.testMethod());
        assertEquals(12, f.line(), "skips Util.java:9 because Util is not the test class");
        assertTrue(f.message().startsWith("java.lang.NullPointerException"));
    }

    @Test
    void classLevelErrorHasEmptyMethod() {
        String out = String.join("\n",
                "[ERROR] fx.BrokenSetupTest -- Time elapsed: 0.02 s <<< ERROR!",
                "java.lang.ExceptionInInitializerError",
                "        at fx.BrokenSetupTest.<clinit>(BrokenSetupTest.java:4)",
                "");

        TestFailure f = BuildOutputParser.parse(out).testFailures().get(0);

        assertEquals("fx.BrokenSetupTest", f.testClass());
        assertEquals("", f.testMethod());
    }

    @Test
    void handlesWindowsLineEndings() throws IOException {
        String crlf = resource("failing-test.log").replace("\n", "\r\n");

        ParsedBuild b = BuildOutputParser.parse(crlf);

        assertEquals(1, b.testFailures().size());
        assertEquals("org.opentest4j.AssertionFailedError: expected: <5> but was: <4>",
                b.testFailures().get(0).message());
        assertEquals(Optional.of(new TestSummary(2, 1, 0, 0)), b.testSummary());
    }

    @Test
    void emptyAndNullInputAreSafe() {
        assertFalse(BuildOutputParser.parse("").hasCompileErrors());
        assertFalse(BuildOutputParser.parse(null).hasTestFailures());
        assertEquals(Optional.empty(), BuildOutputParser.parse("garbage\n[INFO] nothing here").testSummary());
    }

    @Test
    void linuxStylePathsAreLeftAlone() {
        String out = "[ERROR] /home/me/p/src/main/java/A.java:[1,1] boom\n";
        String out2 = String.join("\n",
                "[ERROR] COMPILATION ERROR :",
                "[ERROR] /home/me/p/src/main/java/A.java:[1,1] boom",
                "[INFO] 1 error");

        assertEquals("/home/me/p/src/main/java/A.java",
                BuildOutputParser.parse(out2).compileErrors().get(0).file());
        assertEquals(1, BuildOutputParser.parse(out).compileErrors().size());
    }

    @Test
    void compileErrorsCarryCategoryAndRawExcerptFromRealOutput() throws IOException {
        List<CompileError> errors = BuildOutputParser.parse(resource("compile-error.log")).compileErrors();

        assertEquals(DiagnosticCategory.TYPE_MISMATCH, errors.get(0).category());
        assertEquals("[ERROR] /C:/Users/Mdu/samples/src/main/java/fx/Broken.java:[3,13] "
                + "incompatible types: java.lang.String cannot be converted to int",
                errors.get(0).rawExcerpt());

        assertEquals(DiagnosticCategory.SYMBOL_NOT_FOUND, errors.get(1).category());
        assertTrue(errors.get(1).rawExcerpt().contains("symbol:   method undefinedMethod()"));
        assertTrue(errors.get(1).rawExcerpt().contains("location: class fx.Broken"));
    }

    @Test
    void testFailuresCarryCategoryAndRawExcerptFromRealOutput() throws IOException {
        TestFailure f = BuildOutputParser.parse(resource("failing-test.log")).testFailures().get(0);

        assertEquals(DiagnosticCategory.ASSERTION_FAILURE, f.category());
        assertTrue(f.rawExcerpt().startsWith("[ERROR] fx.CalcTest.adds -- Time elapsed"));
        assertTrue(f.rawExcerpt().contains("expected: <5> but was: <4>"));
        assertTrue(f.rawExcerpt().contains("at fx.CalcTest.adds(CalcTest.java:5)"));
        assertFalse(f.rawExcerpt().contains("[INFO]"), "excerpt stops at the end of this failure");
    }

    @Test
    void unexpectedExceptionHasTestErrorCategory() {
        String out = String.join("\n",
                "[ERROR] fx.NpeTest.boom -- Time elapsed: 0.01 s <<< ERROR!",
                "java.lang.NullPointerException: x",
                "        at fx.NpeTest.boom(NpeTest.java:12)",
                "");

        assertEquals(DiagnosticCategory.TEST_ERROR, BuildOutputParser.parse(out).testFailures().get(0).category());
    }

    @Test
    void classifiesCommonJavacMessages() {
        assertEquals(DiagnosticCategory.SYMBOL_NOT_FOUND, DiagnosticCategory.forCompileMessage("cannot find symbol\nsymbol: x"));
        assertEquals(DiagnosticCategory.TYPE_MISMATCH, DiagnosticCategory.forCompileMessage("incompatible types: possible lossy conversion from double to int"));
        assertEquals(DiagnosticCategory.MISSING_PACKAGE, DiagnosticCategory.forCompileMessage("package org.foo does not exist"));
        assertEquals(DiagnosticCategory.METHOD_MISMATCH, DiagnosticCategory.forCompileMessage("method f in class A cannot be applied to given types;"));
        assertEquals(DiagnosticCategory.MISSING_RETURN, DiagnosticCategory.forCompileMessage("missing return statement"));
        assertEquals(DiagnosticCategory.UNREPORTED_EXCEPTION, DiagnosticCategory.forCompileMessage("unreported exception java.io.IOException; must be caught or declared to be thrown"));
        assertEquals(DiagnosticCategory.UNINITIALIZED_VARIABLE, DiagnosticCategory.forCompileMessage("variable x might not have been initialized"));
        assertEquals(DiagnosticCategory.DUPLICATE_DEFINITION, DiagnosticCategory.forCompileMessage("variable x is already defined in method main(java.lang.String[])"));
        assertEquals(DiagnosticCategory.ABSTRACT_NOT_IMPLEMENTED, DiagnosticCategory.forCompileMessage("A is not abstract and does not override abstract method f() in B"));
        assertEquals(DiagnosticCategory.SYNTAX_ERROR, DiagnosticCategory.forCompileMessage("';' expected"));
        assertEquals(DiagnosticCategory.SYNTAX_ERROR, DiagnosticCategory.forCompileMessage("reached end of file while parsing"));
        assertEquals(DiagnosticCategory.OTHER_COMPILE_ERROR, DiagnosticCategory.forCompileMessage("something javac invented tomorrow"));
        assertEquals(DiagnosticCategory.OTHER_COMPILE_ERROR, DiagnosticCategory.forCompileMessage(null));
    }
}
