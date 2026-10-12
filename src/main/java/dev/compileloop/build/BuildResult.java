package dev.compileloop.build;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Outcome of one build run. {@code runId} and {@code runDirectory} are null when
 * the build never started (bad directory, no pom.xml, no Maven), because no
 * artifacts exist for such a run.
 */
public record BuildResult(
        String commandLabel,
        BuildOutcome outcome,
        int exitCode,
        boolean timedOut,
        Instant startedAt,
        long durationMillis,
        Path logPath,
        String outputTail,
        ParsedBuild parsed,
        Path projectDirectory,
        String runId,
        Path runDirectory
) {
    public boolean passed() {
        return outcome == BuildOutcome.PASS;
    }

    public boolean infrastructureFailure() {
        return outcome == BuildOutcome.START_ERROR
                || outcome == BuildOutcome.INFRA_ERROR
                || outcome == BuildOutcome.CANCELLED;
    }
}
