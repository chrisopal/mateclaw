package vip.mate.semantic.core.fact;

import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.StatementId;

/** Immutable revision of one addressable statement. */
public record StatementRevision(
        StatementId statementId,
        int revision,
        StatementContent content,
        ReviewStatus reviewStatus) {

    public enum ReviewStatus {
        PROPOSED,
        ACCEPTED,
        REJECTED,
        RETRACTED
    }

    public StatementRevision {
        Objects.requireNonNull(statementId, "statementId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    public StatementRevision(StatementId statementId, int revision, StatementContent content) {
        this(statementId, revision, content, ReviewStatus.PROPOSED);
    }
}
