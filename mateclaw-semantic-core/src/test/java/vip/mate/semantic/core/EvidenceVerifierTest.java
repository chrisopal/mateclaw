package vip.mate.semantic.core;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.evidence.EvidenceVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerifierTest {

    private final EvidenceVerifier verifier = new EvidenceVerifier();

    @Test
    void verifiesCodePointOffsetsAroundSupplementaryCharacters() {
        var snapshot = SemanticFixtures.snapshot("设备😀额定380V");
        var evidence = SemanticFixtures.evidence(2, 5, "😀额定");

        var report = verifier.verify(snapshot, evidence);

        assertTrue(report.valid(), () -> "unexpected violations: " + report.violations());
    }

    @Test
    void rejectsAnExactQuoteThatDoesNotMatchTheSnapshotSpan() {
        var snapshot = SemanticFixtures.snapshot("设备😀额定380V");
        var evidence = SemanticFixtures.evidence(2, 5, "额定380");

        var report = verifier.verify(snapshot, evidence);

        assertTrue(report.violations().stream().anyMatch(violation -> violation.code().equals("QUOTE_MISMATCH")),
                () -> "unexpected violations: " + report.violations());
    }
}
