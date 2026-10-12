package dev.compileloop.build;

import java.util.Locale;

/**
 * Coarse, stable classification of a diagnostic. Deliberately small: the raw
 * message and excerpt are always kept alongside it, so a wrong or missing
 * category never hides information.
 */
public enum DiagnosticCategory {
    SYNTAX_ERROR,
    SYMBOL_NOT_FOUND,
    TYPE_MISMATCH,
    MISSING_PACKAGE,
    METHOD_MISMATCH,
    MISSING_RETURN,
    UNREPORTED_EXCEPTION,
    UNINITIALIZED_VARIABLE,
    DUPLICATE_DEFINITION,
    ABSTRACT_NOT_IMPLEMENTED,
    OTHER_COMPILE_ERROR,
    ASSERTION_FAILURE,
    TEST_ERROR;

    /** Classify a javac message by its first line. */
    public static DiagnosticCategory forCompileMessage(String message) {
        String first = message == null ? "" : message.lines().findFirst().orElse("");
        String m = first.toLowerCase(Locale.ROOT);
        if (m.contains("cannot find symbol")) {
            return SYMBOL_NOT_FOUND;
        }
        if (m.contains("incompatible types") || m.contains("bad operand types")) {
            return TYPE_MISMATCH;
        }
        if (m.contains("package ") && m.contains("does not exist")) {
            return MISSING_PACKAGE;
        }
        if (m.contains("cannot be applied to") || m.contains("no suitable method")
                || m.contains("no suitable constructor")) {
            return METHOD_MISMATCH;
        }
        if (m.contains("missing return statement")) {
            return MISSING_RETURN;
        }
        if (m.contains("unreported exception")) {
            return UNREPORTED_EXCEPTION;
        }
        if (m.contains("might not have been initialized")) {
            return UNINITIALIZED_VARIABLE;
        }
        if (m.contains("is already defined")) {
            return DUPLICATE_DEFINITION;
        }
        if (m.contains("is not abstract and does not override")) {
            return ABSTRACT_NOT_IMPLEMENTED;
        }
        if (m.endsWith(" expected") || m.contains("illegal start of")
                || m.contains("reached end of file while parsing") || m.contains("not a statement")
                || m.contains("class, interface, enum, or record expected")) {
            return SYNTAX_ERROR;
        }
        return OTHER_COMPILE_ERROR;
    }
}
