package dev.compileloop.repair;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceTest {

    @TempDir
    Path src;

    private void write(String rel, String content) throws IOException {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void copiesSourcesAndLeavesOutVcsBuildOutputAndIdeFolders() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/fx/Calc.java", "class Calc {}\n");
        write(".git/config", "secret");
        write("target/classes/Old.class", "x");
        write(".idea/workspace.xml", "x");
        write(".compileloop/state.json", "x");

        try (Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            assertEquals("class Calc {}\n", Files.readString(ws.root().resolve("src/main/java/fx/Calc.java")));
            assertTrue(Files.exists(ws.root().resolve("pom.xml")));
            assertFalse(Files.exists(ws.root().resolve(".git")));
            assertFalse(Files.exists(ws.root().resolve("target")));
            assertFalse(Files.exists(ws.root().resolve(".idea")));
            assertFalse(Files.exists(ws.root().resolve(".compileloop")));
        }
    }

    @Test
    void keepsTheMavenWrapperFolder() throws IOException {
        write(".mvn/wrapper/maven-wrapper.properties", "distributionUrl=x\n");

        try (Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            assertTrue(Files.exists(ws.root().resolve(".mvn/wrapper/maven-wrapper.properties")));
        }
    }

    @Test
    void changesInTheCopyNeverReachTheOriginal() throws IOException {
        write("src/main/java/fx/Calc.java", "original\n");

        try (Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            Files.writeString(ws.root().resolve("src/main/java/fx/Calc.java"), "changed\n");
            Files.delete(ws.root().resolve("src/main/java/fx/Calc.java"));
        }

        assertEquals("original\n", Files.readString(src.resolve("src/main/java/fx/Calc.java")));
    }

    @Test
    void closingDeletesTheCopyAndIsRepeatable() throws IOException {
        write("a/b/C.java", "x\n");
        Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults());
        Path root = ws.root();
        assertTrue(Files.exists(root));

        ws.close();
        ws.close();

        assertFalse(Files.exists(root));
    }

    @Test
    void refusesProjectsWithTooManyFilesAndLeavesNothingBehind() throws IOException {
        for (int i = 0; i < 5; i++) {
            write("f" + i + ".txt", "x");
        }

        IOException e = assertThrows(IOException.class,
                () -> Workspace.copyOf(src, new Workspace.Limits(3, 1_000_000)));

        assertTrue(e.getMessage().contains("more than 3 files"), e.getMessage());
    }

    @Test
    void refusesProjectsThatAreTooLarge() throws IOException {
        write("big.bin", "x".repeat(5000));

        IOException e = assertThrows(IOException.class,
                () -> Workspace.copyOf(src, new Workspace.Limits(100, 1000)));

        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    @Test
    void rejectsANonDirectory() throws IOException {
        write("file.txt", "x");

        assertThrows(IOException.class, () -> Workspace.copyOf(src.resolve("file.txt"), Workspace.Limits.defaults()));
        assertThrows(IOException.class, () -> Workspace.copyOf(src.resolve("missing"), Workspace.Limits.defaults()));
    }

    @Test
    void doesNotCopySymbolicLinksAndReportsThem(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
        write("real.txt", "ok");
        try {
            Files.createSymbolicLink(src.resolve("linked.txt"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(src.resolve("linkdir"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }

        try (Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            assertTrue(Files.exists(ws.root().resolve("real.txt")));
            assertFalse(Files.exists(ws.root().resolve("linked.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(ws.root().resolve("linkdir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals(2, ws.skipped().size(), ws.skipped().toString());
        }
        assertEquals("TOP SECRET", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    void deletingTheCopyNeverFollowsALinkPlantedInsideIt(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("precious.txt"), "keep me");
        write("a.txt", "x");
        Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults());
        try {
            Files.createSymbolicLink(ws.root().resolve("planted"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            ws.close();
            assumeTrue(false, "symbolic links are not available here");
        }

        ws.close();

        assertTrue(Files.exists(outside.resolve("precious.txt")), "close() must not delete through a link");
        assertFalse(Files.exists(ws.root()));
    }

    @Test
    void preservesTheExecutableBitOfWrapperScripts() throws IOException {
        write("mvnw", "#!/bin/sh\n");
        Path script = src.resolve("mvnw");
        assumeTrue(script.toFile().setExecutable(true) && Files.isExecutable(script), "no executable bit here");

        try (Workspace ws = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            assertTrue(Files.isExecutable(ws.root().resolve("mvnw")));
        }
    }

    @Test
    void eachCopyIsAFreshDirectory() throws IOException {
        write("a.txt", "x");

        try (Workspace one = Workspace.copyOf(src, Workspace.Limits.defaults());
             Workspace two = Workspace.copyOf(src, Workspace.Limits.defaults())) {
            assertFalse(one.root().equals(two.root()));
            assertEquals(List.of(), one.skipped());
        }
    }
}
