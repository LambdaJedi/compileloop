package dev.compileloop.build;

import dev.compileloop.repair.RepairEngine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@code mvn -B test} inside a Docker container instead of on the host.
 *
 * <p>The project is first copied to a temporary directory (the same
 * {@link dev.compileloop.repair.Workspace} rules apply: no VCS data, no build
 * output, no symlinks). That copy — never the caller's directory — is
 * bind-mounted into the container at {@code /workspace}. The container runs
 * with a memory cap, a CPU cap, and its own Maven cache volume so repeat
 * builds are fast. On timeout, the container is killed by name.
 *
 * <p>This is <strong>not</strong> a security sandbox in the strong sense:
 * Docker isolation depends on configuration and is not a universal guarantee.
 * It does protect the caller's directory from writes and separates the build
 * from the host process tree, which is the property the repair loop needs.
 *
 * <p>Requires the {@code docker} CLI on {@code PATH} and a running daemon.
 * {@link #isAvailable()} reports whether both are true; when false, callers
 * should fall back to {@link BuildRunner}.
 */
public final class DockerBuildExecutor implements RepairEngine.BuildExecutor {

    private static final String WORKSPACE_MOUNT = "/workspace";
    private static final String M2_VOLUME = "compileloop-m2";
    private static final Set<String> EXCLUDED_DIRS =
            Set.of(".git", "target", ".idea", ".vscode", ".compileloop");

    public record Config(
            String image,
            long memoryBytes,
            double cpus,
            boolean networkDisabled,
            boolean useM2CacheVolume
    ) {
        public Config {
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("image is required");
            }
            if (memoryBytes < 128L * 1024 * 1024) {
                throw new IllegalArgumentException("memory must be at least 128 MiB");
            }
            if (cpus <= 0.0) {
                throw new IllegalArgumentException("cpus must be positive");
            }
        }

        public static Config defaults() {
            return new Config(
                    "maven:3.9-eclipse-temurin-17",
                    2L * 1024 * 1024 * 1024,
                    2.0,
                    false,
                    true);
        }

        public Config withImage(String v) {
            return new Config(v, memoryBytes, cpus, networkDisabled, useM2CacheVolume);
        }

        public Config withMemory(long v) {
            return new Config(image, v, cpus, networkDisabled, useM2CacheVolume);
        }

        public Config withCpus(double v) {
            return new Config(image, memoryBytes, v, networkDisabled, useM2CacheVolume);
        }

        public Config withNetworkDisabled(boolean v) {
            return new Config(image, memoryBytes, cpus, v, useM2CacheVolume);
        }

        public Config withM2CacheVolume(boolean v) {
            return new Config(image, memoryBytes, cpus, networkDisabled, v);
        }
    }

    /** True if the docker CLI runs and the daemon answers. Best-effort. */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private final Config config;

    public DockerBuildExecutor() {
        this(Config.defaults());
    }

    public DockerBuildExecutor(Config config) {
        this.config = config;
    }

    @Override
    public BuildResult run(Path projectDirectory, Duration timeout) throws IOException {
        Path source = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            return startError(source, "Not a directory: " + source);
        }
        if (!Files.isRegularFile(source.resolve("pom.xml"))) {
            return startError(source,
                    "No pom.xml found in " + source + ". CompileLoop currently supports Maven projects only.");
        }

        Path mountRoot = Files.createTempDirectory("compileloop-docker-");
        try {
            copyProject(source, mountRoot);
            return runInContainer(source, mountRoot, timeout);
        } finally {
            dev.compileloop.repair.Workspace.deleteTree(mountRoot);
        }
    }

    // ---- container invocation --------------------------------------------------------

    private BuildResult runInContainer(Path original, Path mountRoot, Duration timeout) throws IOException {
        Path runDir = Files.createDirectories(
                original.resolve("target/compileloop/runs/" + BuildRunner.newRunId(Instant.now())));
        Path logPath = runDir.resolve("build.log");
        Instant startedAt = Instant.now();
        String containerName = "compileloop-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--memory=" + config.memoryBytes());
        cmd.add("--cpus=" + config.cpus());
        if (config.networkDisabled()) {
            cmd.add("--network=none");
        }
        if (config.useM2CacheVolume()) {
            cmd.add("-v");
            cmd.add(M2_VOLUME + ":/root/.m2");
        }
        cmd.add("-v");
        cmd.add(toMountPath(mountRoot) + ":" + WORKSPACE_MOUNT);
        cmd.add("-w");
        cmd.add(WORKSPACE_MOUNT);
        cmd.add(config.image());
        cmd.add("mvn");
        cmd.add("-B");
        cmd.add("test");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return startError(original, "Could not start docker: " + e.getMessage());
        }

        BoundedTail tail = new BoundedTail(256 * 1024);
        AtomicBoolean ioFailed = new AtomicBoolean(false);
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "compileloop-docker-io");
            t.setDaemon(true);
            return t;
        });

        Future<?> ioTask = io.submit(() -> {
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

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(containerName);
            process.destroyForcibly();
            io.shutdownNow();
            return buildResult(original, BuildOutcome.CANCELLED, -1, false, startedAt,
                    logPath, tail.asString(), runDir);
        }

        if (!finished) {
            killContainer(containerName);
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            io.shutdownNow();
            return buildResult(original, BuildOutcome.TIMEOUT, -1, true, startedAt,
                    logPath, tail.asString(), runDir);
        }

        try {
            ioTask.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // reader still finishing; log file is the source of truth
        }
        io.shutdown();

        if (ioFailed.get()) {
            return buildResult(original, BuildOutcome.INFRA_ERROR, -1, false, startedAt,
                    logPath, tail.asString(), runDir);
        }

        int exit = process.exitValue();
        BuildOutcome outcome = exit == 0 ? BuildOutcome.PASS : BuildOutcome.FAIL;
        return buildResult(original, outcome, exit, false, startedAt, logPath, tail.asString(), runDir);
    }

    private void killContainer(String name) {
        try {
            Process kill = new ProcessBuilder("docker", "kill", name)
                    .redirectErrorStream(true)
                    .start();
            try (var in = kill.getInputStream()) {
                in.readAllBytes();
            }
            kill.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // the container may already be gone; --rm handles cleanup
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private static String toMountPath(Path p) {
        // Docker on Windows accepts either //c/Users/... or C:/Users/... .
        // Forward slashes work everywhere.
        return p.toString().replace('\\', '/');
    }

    private static BuildResult startError(Path project, String message) {
        Instant started = Instant.now();
        return new BuildResult("mvn -B test (docker)", BuildOutcome.START_ERROR, -1, false,
                started, Duration.between(started, Instant.now()).toMillis(), null, message,
                ParsedBuild.empty(), project, null, null);
    }

    private static BuildResult buildResult(Path project, BuildOutcome outcome, int exit, boolean timedOut,
                                           Instant started, Path log, String tail, Path runDir) {
        ParsedBuild parsed = (outcome == BuildOutcome.PASS || outcome == BuildOutcome.FAIL)
                ? BuildOutputParser.parse(tail) : ParsedBuild.empty();
        Path existingLog = Files.isRegularFile(log) ? log : null;
        return new BuildResult("mvn -B test (docker)", outcome, exit, timedOut, started,
                Duration.between(started, Instant.now()).toMillis(), existingLog, tail, parsed,
                project, runDir.getFileName().toString(), runDir);
    }

    private static void copyProject(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (!dir.equals(source)) {
                    String name = dir.getFileName().toString();
                    if (a.isSymbolicLink() || a.isOther() || EXCLUDED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (a.isRegularFile() && !a.isSymbolicLink()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

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
