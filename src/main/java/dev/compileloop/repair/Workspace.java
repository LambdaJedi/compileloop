package dev.compileloop.repair;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A throw-away copy of a project. Patches are applied and builds are run here,
 * never in the user's own tree. The copy leaves out VCS data, build output and IDE
 * files, and it never copies symbolic links or junctions (they could point
 * outside the project). Closing it deletes the copy.
 */
public final class Workspace implements AutoCloseable {

    /** Directories that are never copied. */
    static final Set<String> EXCLUDED_DIRS = Set.of(".git", "target", ".idea", ".vscode", ".compileloop");

    public record Limits(int maxFiles, long maxTotalBytes) {
        public static Limits defaults() {
            return new Limits(20_000, 200L * 1024 * 1024);
        }
    }

    private final Path root;
    private final List<String> skipped;
    private boolean closed;

    private Workspace(Path root, List<String> skipped) {
        this.root = root;
        this.skipped = List.copyOf(skipped);
    }

    public static Workspace copyOf(Path source, Limits limits) throws IOException {
        Path src = source.toRealPath();
        if (!Files.isDirectory(src)) {
            throw new IOException("not a directory: " + source);
        }
        Path root = Files.createTempDirectory("compileloop-ws-");
        List<String> skipped = new ArrayList<>();
        int[] files = {0};
        long[] bytes = {0};
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    if (!dir.equals(src)) {
                        String name = dir.getFileName().toString();
                        if (a.isSymbolicLink() || a.isOther()) {
                            skipped.add(src.relativize(dir).toString().replace('\\', '/') + " (link)");
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (EXCLUDED_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    Files.createDirectories(root.resolve(src.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                    String rel = src.relativize(file).toString();
                    if (a.isSymbolicLink() || a.isOther() || !a.isRegularFile()) {
                        skipped.add(rel.replace('\\', '/') + " (link or special file)");
                        return FileVisitResult.CONTINUE;
                    }
                    if (++files[0] > limits.maxFiles()) {
                        throw new IOException("the project has more than " + limits.maxFiles()
                                + " files; refusing to copy it");
                    }
                    bytes[0] += a.size();
                    if (bytes[0] > limits.maxTotalBytes()) {
                        throw new IOException("the project is larger than " + limits.maxTotalBytes()
                                + " bytes; refusing to copy it");
                    }
                    Files.copy(file, root.resolve(rel), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            deleteTree(root);
            throw e;
        }
        return new Workspace(root.toRealPath(), skipped);
    }

    public Path root() {
        return root;
    }

    /** Paths that were deliberately not copied because they are links or special files. */
    public List<String> skipped() {
        return skipped;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            deleteTree(root);
        }
    }

    /** Deletes a directory tree without ever following a link. Best effort. */
    static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    deleteOne(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    deleteOne(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    deleteOne(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort: a leftover temp directory is harmless
        }
    }

    private static void deleteOne(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException first) {
            try {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // leave it
            }
        }
    }
}
