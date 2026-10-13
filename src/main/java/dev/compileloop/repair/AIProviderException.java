package dev.compileloop.repair;

/** A provider could not produce a usable proposal. The kind says why, so callers can react. */
public class AIProviderException extends Exception {

    public enum Kind {
        /** No model reachable, model missing, or the service answered with an error. */
        UNAVAILABLE,
        /** The model did not answer within the time limit. */
        TIMEOUT,
        /** The answer was not valid according to the strict proposal format. */
        MALFORMED_RESPONSE,
        /** The prepared context does not fit the model's limit. */
        CONTEXT_OVERFLOW
    }

    private final Kind kind;

    public AIProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AIProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
