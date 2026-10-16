package dev.compileloop;

import dev.compileloop.build.BuildRunner;
import dev.compileloop.repair.AIProvider;
import dev.compileloop.repair.ContextBuilder;
import dev.compileloop.repair.PatchPolicy;
import dev.compileloop.repair.RepairEngine;
import dev.compileloop.repair.RepairResult;
import dev.compileloop.repair.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Programmatic entry point to the repair loop.
 *
 * <pre>{@code
 * RepairResult result = CompileLoop.repair()
 *         .project(Path.of("path/to/project"))
 *         .provider(AIProvider.ollama("http://localhost:11434", "qwen2.5-coder:7b"))
 *         .maxAttempts(3)
 *         .run();
 *
 * if (result.verifiedCandidate()) {
 *     Path patch = result.patchFile();
 *     // Read the diff. Review it. Apply it yourself.
 * }
 * }</pre>
 *
 * <p>The builder collects the same {@link RepairEngine.Config} the CLI uses
 * and delegates to {@link RepairEngine#repair(Path)}. It adds no behaviour
 * of its own; every safety property (workspace isolation, patch validation,
 * stop conditions) lives in the engine.
 *
 * <p>Every field has a sensible default, so the only two required calls are
 * {@link RepairBuilder#project(Path)} and {@link RepairBuilder#provider(AIProvider)}.
 * Calling {@link RepairBuilder#run()} without either throws
 * {@link IllegalStateException} before any process is launched.
 */
public final class CompileLoop {

    private CompileLoop() {
    }

    /** Start building a repair run. */
    public static RepairBuilder repair() {
        return new RepairBuilder();
    }

    /** Fluent configuration for a single {@link #repair()} invocation. */
    public static final class RepairBuilder {

        private Path project;
        private AIProvider provider;
        private RepairEngine.BuildExecutor executor;
        private int maxAttempts = 3;
        private Duration buildTimeout = Duration.ofSeconds(300);
        private PatchPolicy policy = PatchPolicy.defaults().withProtectedPaths(List.of("src/test/java"));
        private ContextBuilder.Limits contextLimits = ContextBuilder.Limits.defaults();
        private Workspace.Limits workspaceLimits = Workspace.Limits.defaults();

        private RepairBuilder() {
        }

        /** The Maven project to repair. Required. */
        public RepairBuilder project(Path project) {
            this.project = Objects.requireNonNull(project, "project");
            return this;
        }

        /** The AI backend that proposes patches. Required. */
        public RepairBuilder provider(AIProvider provider) {
            this.provider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        /**
         * How the build is executed. The default runs Maven on the host via
         * {@link BuildRunner}. Pass a custom implementation to run the build
         * elsewhere (for example, inside a container). Optional.
         */
        public RepairBuilder executor(RepairEngine.BuildExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Maximum repair attempts. Must be between 1 and 10. Optional. */
        public RepairBuilder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Wall-clock limit for each build. Optional. */
        public RepairBuilder buildTimeout(Duration timeout) {
            this.buildTimeout = Objects.requireNonNull(timeout, "buildTimeout");
            return this;
        }

        /** The patch policy. Optional; defaults to the CLI's default policy. */
        public RepairBuilder policy(PatchPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Permit modifications under {@code src/test/java}. Off by default —
         * a "fix" that edits the tests proves nothing. Enabling this is
         * equivalent to removing that path from the policy's protected paths.
         */
        public RepairBuilder allowTestChanges(boolean allow) {
            if (allow) {
                this.policy = policy.withProtectedPaths(
                        policy.protectedPaths().stream()
                                .filter(p -> !p.startsWith("src/test"))
                                .toList());
            } else {
                if (!policy.protectedPaths().contains("src/test/java")) {
                    var next = new java.util.ArrayList<>(policy.protectedPaths());
                    next.add("src/test/java");
                    this.policy = policy.withProtectedPaths(next);
                }
            }
            return this;
        }

        /** Limits on how much of the project is shown to the model. Optional. */
        public RepairBuilder contextLimits(ContextBuilder.Limits limits) {
            this.contextLimits = Objects.requireNonNull(limits, "contextLimits");
            return this;
        }

        /** Limits on the disposable workspace. Optional. */
        public RepairBuilder workspaceLimits(Workspace.Limits limits) {
            this.workspaceLimits = Objects.requireNonNull(limits, "workspaceLimits");
            return this;
        }

        /**
         * Run the repair loop. Never throws for a failed build or a failed
         * repair: those are outcomes in the returned {@link RepairResult}.
         * Throws {@link IllegalStateException} if a required field is missing,
         * and {@link IllegalArgumentException} for an out-of-range value.
         */
        public RepairResult run() {
            if (project == null) {
                throw new IllegalStateException("project is required; call .project(Path)");
            }
            if (provider == null) {
                throw new IllegalStateException("provider is required; call .provider(AIProvider)");
            }
            RepairEngine.BuildExecutor exec = executor != null
                    ? executor
                    : new BuildRunner()::runMavenTests;
            RepairEngine.Config config = new RepairEngine.Config(
                    maxAttempts, buildTimeout, policy, contextLimits, workspaceLimits);
            return new RepairEngine(exec, provider, config).repair(project);
        }
    }
}
