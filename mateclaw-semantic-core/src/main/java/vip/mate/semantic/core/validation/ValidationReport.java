package vip.mate.semantic.core.validation;

import java.util.List;
import java.util.Objects;

/** Immutable validation result. */
public record ValidationReport(List<Violation> violations) {

    public ValidationReport {
        Objects.requireNonNull(violations, "violations");
        violations = List.copyOf(violations);
    }

    public boolean valid() {
        return violations.stream().noneMatch(violation -> violation.severity() == Violation.Severity.ERROR);
    }
}
