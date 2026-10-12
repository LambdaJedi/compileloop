package dev.compileloop.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BuildRunner {

    private static final int TAIL_CHARS = 256 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final Duration defaultTimeout;

    public BuildRunner() {
        this(DEFAULT_TIMEOUT);
    }

    public BuildRunner(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public BuildResult runMavenTests(Path projectDirectory) throws IOException {
        return runMavenTests(projectDirectory, defaultTimeout);
    }

    public BuildResult runMavenTests(Path projectDirectory, Duration timeout) throws IOException {
        // Absolute path: the child runs *inside* projectDirectory, so a relative
        // executable path (e.g. fixtures/pass/mvnw) would no longer resolve.
        projectDirectory = projectDirectory.toAbsolutePath().normalize();
        String commandLabel = "mvn -B test";
        Instant startedAt = Instant.now();

        // 1. Validate project directory (blueprint §8: validate before launch).
        if (!Files.isDirectory(projectDirectory)) {
            return early(commandLabel, startedAt, BuildOutcome.START_ERROR,
                    "Not a directory: " + projectDirectory);
        }

        // 2. Resolve mvn / mvnw / mvnw.cmd deliberately (blueprint §8).
        Optional<Path> maven = resolveMavenExecutable(projectDirectory);
        if (maven.isEmpty()) {
            return early(commandLabel, startedAt, BuildOutcome.START_ERROR,
                    "Could not locate mvn, mvnw, or mvnw.cmd. "
                            + "Checked project wrapper and PATH.");
        }

        Path logPath = projectDirectory.resolve("target/compileloop/build.log");
        Files.createDirectories(logPath.getParent());

        // 3. ProcessBuilder with a list — no shell string (blueprint §5, §8).
        List<String> command = List.of(maven.get().toString(), "-B", "test");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDirectory.toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return early(commandLabel, startedAt, BuildOutcome.START_ERROR,
                    "Failed to start process: " + e.getMessage());
        }

        // 4. Drain output concurrently — the "critical engineering correction".
        BoundedTail tail = new BoundedTail(TAIL_CHARS);
        AtomicBoolean ioFailed = new AtomicBoolean(false);
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "compileloop-io");
            t.setDaemon(true);
            return t;
        });

        Future<?> ioTask = io.submit(() -> {
            // The pipe MUST be drained even if the log file cannot be written,
            // otherwise Maven blocks on a full pipe until the timeout fires.
            BufferedWriter writer = null;
            try {
                writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                ioFailed.set(true);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (writer != null) {
                        try {
                            writer.write(line);
                            writer.newLine();
                        } catch (IOException e) {
                            ioFailed.set(true);
                            writer = null;
                        }
                    }
                    tail.append(line);
                    tail.append("\n");
                }
            } catch (IOException e) {
                ioFailed.set(true);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        ioFailed.set(true);
                    }
                }
            }
        });

        // 5. Wait with a wall-clock timeout (blueprint §8).
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            awaitProcess(process, SHUTDOWN_GRACE);
            awaitIo(ioTask, SHUTDOWN_GRACE);
            io.shutdownNow();
            return result(commandLabel, startedAt, BuildOutcome.CANCELLED, -1, false,
                    logPath, tail.asString());
        }

        if (!finished) {
            // 6. Kill the process tree — Process.destroyForcibly alone leaves grandchildren.
            killTree(process);
            awaitProcess(process, SHUTDOWN_GRACE);
            awaitIo(ioTask, SHUTDOWN_GRACE);
            io.shutdownNow();
            return result(commandLabel, startedAt, BuildOutcome.TIMEOUT, -1, true,
                    logPath, tail.asString());
        }

        // 7. Let the reader drain remaining buffered output.
        awaitIo(ioTask, SHUTDOWN_GRACE);
        io.shutdown();

        if (ioFailed.get()) {
            return result(commandLabel, startedAt, BuildOutcome.INFRA_ERROR, -1, false,
                    logPath, tail.asString());
        }

        int exitCode = process.exitValue();
        BuildOutcome outcome = exitCode == 0 ? BuildOutcome.PASS : BuildOutcome.FAIL;
        return result(commandLabel, startedAt, outcome, exitCode, false,
                logPath, tail.asString());
    }

    // ---- helpers ----------------------------------------------------------

    private static BuildResult early(String label, Instant start, BuildOutcome outcome, String message) {
        return new BuildResult(label, outcome, -1, false, start,
                Duration.between(start, Instant.now()).toMillis(), null, message);
    }

    private static BuildResult result(String label, Instant start, BuildOutcome outcome,
                                      int exit, boolean timedOut, Path log, String tail) {
        return new BuildResult(label, outcome, exit, timedOut, start,
                Duration.between(start, Instant.now()).toMillis(), log, tail);
    }

    static Optional<Path> resolveMavenExecutable(Path projectDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

        // Project-local wrapper first (blueprint §4, §8).
        Path wrapper = projectDirectory.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return Optional.of(wrapper);
        }

        // PATH lookup. On Windows, Maven ships mvn.cmd — pass the full path so
        // ProcessBuilder never needs a bare command name (blueprint §8).
        return findOnPath(windows ? "mvn.cmd" : "mvn");
    }

    private static Optional<Path> findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void killTree(Process process) {
        // Descendants first, then the parent. Works on Windows and Unix (Java 9+).
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void awaitProcess(Process process, Duration d) {
        try {
            process.waitFor(d.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitIo(Future<?> ioTask, Duration d) {
        try {
            ioTask.get(d.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Either the reader is still finishing (harmless after kill) or was
            // interrupted. The log file on disk remains the source of truth.
        }
    }

    /** Line-oriented tail; caps memory regardless of how chatty the build is. */
    private static final class BoundedTail {
        private final int maxChars;
        private final StringBuilder sb = new StringBuilder();

        BoundedTail(int maxChars) {
            this.maxChars = maxChars;
        }

        synchronized void append(String s) {
            sb.append(s);
            if (sb.length() > maxChars) {
                sb.delete(0, sb.length() - maxChars);
            }
        }

        synchronized String asString() {
            return sb.toString();
        }
    }
}
