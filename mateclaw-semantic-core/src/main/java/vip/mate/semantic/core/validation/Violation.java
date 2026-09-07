package vip.mate.semantic.core.validation;

import java.util.Objects;

/** A stable, safe validation finding suitable for API mapping. */
public record Violation(String code, String path, Severity severity, String message) {

    public Violation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}
