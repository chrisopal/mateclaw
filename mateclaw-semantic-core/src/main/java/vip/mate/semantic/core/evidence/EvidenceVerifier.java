package vip.mate.semantic.core.evidence;

import java.util.ArrayList;
import java.util.List;

import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/** Validates immutable evidence coordinates against the captured source text. */
public final class EvidenceVerifier {

    public ValidationReport verify(SourceSnapshot snapshot, Evidence evidence) {
        List<Violation> violations = new ArrayList<>();
        if (snapshot == null) {
            add(violations, "REQUIRED", "snapshot", "source snapshot is required");
        }
        if (evidence == null) {
            add(violations, "REQUIRED", "evidence", "evidence is required");
        }
        if (snapshot == null || evidence == null) {
            return new ValidationReport(violations);
        }
        if (!snapshot.snapshotId().equals(evidence.snapshotId())) {
            add(violations, "SNAPSHOT_MISMATCH", "snapshotId", "evidence does not belong to this snapshot");
            return new ValidationReport(violations);
        }
        int length = snapshot.text().codePointCount(0, snapshot.text().length());
        if (evidence.startCodePoint() < 0 || evidence.endCodePoint() < evidence.startCodePoint()
                || evidence.endCodePoint() > length) {
            add(violations, "INVALID_SPAN", "span", "evidence span is outside the snapshot code point range");
            return new ValidationReport(violations);
        }
        String extracted;
        try {
            int start = snapshot.text().offsetByCodePoints(0, evidence.startCodePoint());
            int end = snapshot.text().offsetByCodePoints(0, evidence.endCodePoint());
            extracted = snapshot.text().substring(start, end);
        } catch (IndexOutOfBoundsException exception) {
            add(violations, "INVALID_SPAN", "span", "evidence span cannot be resolved");
            return new ValidationReport(violations);
        }
        if (!extracted.equals(evidence.exactQuote())) {
            add(violations, "QUOTE_MISMATCH", "exactQuote", "evidence quote does not match the snapshot span");
        }
        return new ValidationReport(violations);
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }
}
