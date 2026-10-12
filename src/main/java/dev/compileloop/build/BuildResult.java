package dev.compileloop.build;

import java.nio.file.Path;
import java.time.Instant;

public record BuildResult(
        String commandLabel,
        BuildOutcome outcome,
        int exitCode,
        boolean timedOut,
        Instant startedAt,
        long durationMillis,
        Path logPath,
        String outputTail
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
