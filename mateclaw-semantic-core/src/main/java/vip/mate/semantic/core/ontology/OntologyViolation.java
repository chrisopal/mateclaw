package vip.mate.semantic.core.ontology;

import java.util.Objects;

/** A stable finding returned by document/profile governance checks. */
public record OntologyViolation(String code, String path, Severity severity, String message) {
    public OntologyViolation {
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
