package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

public record OntologyValidationReport(String profile, List<OntologyViolation> violations) {
    public OntologyValidationReport {
        profile = Objects.requireNonNull(profile, "profile");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public boolean valid() {
        return violations.stream().noneMatch(v -> v.severity() == OntologyViolation.Severity.ERROR);
    }
}
