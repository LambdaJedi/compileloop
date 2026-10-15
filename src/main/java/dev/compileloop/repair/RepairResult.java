package dev.compileloop.repair;

import dev.compileloop.build.BuildResult;

import java.nio.file.Path;
import java.util.List;

/** Everything that happened in one repair run. */
public record RepairResult(
        Verdict verdict,
        String runId,
        Path reportDirectory,
        BuildResult baseline,
        List<AttemptRecord> attempts,
        Path patchFile,
        List<String> warnings,
        String message
) {

    public enum Verdict {
        /** The build already passed; nothing to repair. */
        ALREADY_PASSING,
        /**
         * A proposal was validated, applied in a disposable copy, and the copy's
         * build passed. The candidate patch is still a proposal: it has not been
         * applied to the user's working tree and requires human review.
         */
        VERIFIED_CANDIDATE,
        /** Attempts were used up without a verified fix. */
        NOT_FIXED,
        /** The model could not be reached or could not take the context. */
        AI_UNAVAILABLE,
        /** The build could not be run, or failed in a way this tool does not repair. */
        INFRA_ERROR
    }

    public enum Status {
        AI_ERROR, REJECTED, DID_NOT_APPLY, STILL_FAILING, BUILD_NOT_RUNNABLE, VERIFIED
    }

    /** One try: ask, validate, apply in a copy, build the copy. */
    public record AttemptRecord(
            int number,
            Status status,
            long durationMillis,
            String summary,
            List<String> assumptions,
            String rawResponse,
            String diff,
            AIProviderException.Kind aiErrorKind,
            String aiErrorMessage,
            ValidationResult validation,
            PatchApplier.ApplyResult apply,
            BuildResult build,
            boolean modifiesTests,
            String detail
    ) {
        public AttemptRecord {
            assumptions = List.copyOf(assumptions);
        }
    }

    public RepairResult {
        attempts = List.copyOf(attempts);
        warnings = List.copyOf(warnings);
    }

    /**
     * True when a candidate patch was verified by a build in a disposable copy.
     * It does NOT mean the patch has been applied or approved: the caller (a
     * human, ideally) must still review and apply it.
     */
    public boolean verifiedCandidate() {
        return verdict == Verdict.VERIFIED_CANDIDATE;
    }
}