package dev.compileloop.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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
        Run run = new Run("mvn -B test", projectDirectory, Instant.now());

        // 1. Validate project directory and build file (blueprint §8: validate before launch).
        if (!Files.isDirectory(projectDirectory)) {
            return early(run, BuildOutcome.START_ERROR, "Not a directory: " + projectDirectory);
        }
        if (!Files.isRegularFile(projectDirectory.resolve("pom.xml"))) {
            return early(run, BuildOutcome.START_ERROR,
                    "No pom.xml found in " + projectDirectory
                            + ". CompileLoop currently supports Maven projects only.");
        }

        // 2. Resolve mvn / mvnw / mvnw.cmd deliberately (blueprint §8).
        Optional<Path> maven = resolveMavenExecutable(projectDirectory);
        if (maven.isEmpty()) {
            return early(run, BuildOutcome.START_ERROR,
                    "Could not locate mvn, mvnw, or mvnw.cmd. "
                            + "Checked project wrapper and PATH.");
        }

        // 3. One directory per run: target/compileloop/runs/<runId>/ holds log + report.
        try {
            run.dir = createRunDirectory(projectDirectory, run.started);
        } catch (IOException e) {
            return early(run, BuildOutcome.INFRA_ERROR,
                    "Could not create the run directory: " + e.getMessage());
        }
        run.id = run.dir.getFileName().toString();
        Path logPath = run.dir.resolve("build.log");

        // ProcessBuilder with a list: no shell string (blueprint §5, §8).
        List<String> command = List.of(maven.get().toString(), "-B", "test");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDirectory.toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return finish(run, BuildOutcome.START_ERROR, -1, false,
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
            return finish(run, BuildOutcome.CANCELLED, -1, false, tail.asString());
        }

        if (!finished) {
            // 6. Kill the process tree — Process.destroyForcibly alone leaves grandchildren.
            killTree(process);
            awaitProcess(process, SHUTDOWN_GRACE);
            awaitIo(ioTask, SHUTDOWN_GRACE);
            io.shutdownNow();
            return finish(run, BuildOutcome.TIMEOUT, -1, true, tail.asString());
        }

        // 7. Let the reader drain remaining buffered output.
        awaitIo(ioTask, SHUTDOWN_GRACE);
        io.shutdown();

        if (ioFailed.get()) {
            return finish(run, BuildOutcome.INFRA_ERROR, -1, false, tail.asString());
        }

        int exitCode = process.exitValue();
        BuildOutcome outcome = exitCode == 0 ? BuildOutcome.PASS : BuildOutcome.FAIL;
        return finish(run, outcome, exitCode, false, tail.asString());
    }

    // ---- helpers ----------------------------------------------------------

    /** Mutable per-call bookkeeping: what is being run and where its artifacts go. */
    private static final class Run {
        final String label;
        final Path project;
        final Instant started;
        String id;
        Path dir;

        Run(String label, Path project, Instant started) {
            this.label = label;
            this.project = project;
            this.started = started;
        }
    }

    /** A run that never started: no artifacts, no run directory. */
    private static BuildResult early(Run run, BuildOutcome outcome, String message) {
        return new BuildResult(run.label, outcome, -1, false, run.started,
                Duration.between(run.started, Instant.now()).toMillis(), null, message,
                ParsedBuild.empty(), run.project, run.id, run.dir);
    }

    /** Build the result, attach parsed diagnostics and write report.json (best effort). */
    private static BuildResult finish(Run run, BuildOutcome outcome, int exit, boolean timedOut,
                                      String tail) {
        Path log = run.dir.resolve("build.log");
        Path existingLog = Files.isRegularFile(log) ? log : null;
        BuildResult result = new BuildResult(run.label, outcome, exit, timedOut, run.started,
                Duration.between(run.started, Instant.now()).toMillis(), existingLog, tail,
                parsedFor(outcome, existingLog, tail), run.project, run.id, run.dir);
        try {
            ReportWriter.write(result);
        } catch (IOException ignored) {
            // The result is still returned; the log is the source of truth.
        }
        return result;
    }

    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** UTC timestamp plus a random suffix, e.g. 20261012T115500Z-3f9a1c. */
    public static String newRunId(Instant at) {
        return RUN_ID_TIME.format(at) + "-"
                + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    /** Creates target/compileloop/runs/<runId>; createDirectory is atomic, so ids never collide. */
    private static Path createRunDirectory(Path project, Instant at) throws IOException {
        Path runs = project.resolve("target").resolve("compileloop").resolve("runs");
        Files.createDirectories(runs);
        for (int attempt = 0; attempt < 10; attempt++) {
            Path dir = runs.resolve(newRunId(at));
            try {
                return Files.createDirectory(dir);
            } catch (FileAlreadyExistsException retry) {
                // pick another suffix
            }
        }
        throw new IOException("could not allocate a unique run directory under " + runs);
    }

    /** Largest log we will read back into memory for parsing; beyond this, parse the tail. */
    private static final long MAX_PARSE_BYTES = 16L * 1024 * 1024;

    /**
     * Parse only builds that ran to completion (PASS/FAIL). Prefer the full log
     * so compile errors near the top survive a chatty test run; fall back to the
     * bounded tail if the log is missing, unreadable or huge.
     */
    private static ParsedBuild parsedFor(BuildOutcome outcome, Path log, String tail) {
        if (outcome != BuildOutcome.PASS && outcome != BuildOutcome.FAIL) {
            return ParsedBuild.empty();
        }
        String text = tail;
        try {
            if (log != null && Files.isRegularFile(log) && Files.size(log) <= MAX_PARSE_BYTES) {
                text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // keep the tail
        }
        return BuildOutputParser.parse(text);
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
        return findOnPath(executable, System.getenv("PATH"));
    }

    /**
     * PATH entries in the wild can be quoted ("C:\\Program Files\\x") or contain
     * characters that are illegal in a path; Path.of throws on those, which must
     * not turn "mvn not found in this entry" into a crash.
     */
    static Optional<Path> findOnPath(String executable, String pathEnv) {
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String raw : pathEnv.split(File.pathSeparator)) {
            String dir = raw.strip();
            if (dir.length() >= 2 && dir.startsWith("\"") && dir.endsWith("\"")) {
                dir = dir.substring(1, dir.length() - 1);
            }
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir, executable);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException ignored) {
                // skip this entry
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
