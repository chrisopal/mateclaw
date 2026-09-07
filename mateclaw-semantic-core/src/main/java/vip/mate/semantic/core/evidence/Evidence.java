package vip.mate.semantic.core.evidence;

import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.EvidenceId;
import vip.mate.semantic.core.identity.SemanticIds.SnapshotId;

/** Exact source span supporting a statement. Offsets are Unicode code points. */
public record Evidence(
        EvidenceId evidenceId,
        SnapshotId snapshotId,
        int startCodePoint,
        int endCodePoint,
        String exactQuote) {

    public Evidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(exactQuote, "exactQuote");
    }
}
