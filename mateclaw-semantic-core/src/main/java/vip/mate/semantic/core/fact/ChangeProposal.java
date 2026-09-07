package vip.mate.semantic.core.fact;

import java.util.Objects;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.ProposalId;
import vip.mate.semantic.core.identity.SemanticIds.StatementId;

/** Independent immutable proposal for changing an existing statement. */
public record ChangeProposal(
        ProposalId proposalId,
        GraphScope scope,
        StatementId targetStatementId,
        int expectedRevision,
        StatementContent content,
        Status status) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        STALE
    }

    public ChangeProposal {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(targetStatementId, "targetStatementId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (!scope.equals(content.scope())) {
            throw new IllegalArgumentException("proposal scope must match content scope");
        }
    }

    public ChangeProposal(
            ProposalId proposalId,
            GraphScope scope,
            StatementId targetStatementId,
            int expectedRevision,
            StatementContent content) {
        this(proposalId, scope, targetStatementId, expectedRevision, content, Status.PENDING);
    }

    public ChangeProposal(
            ProposalId proposalId,
            StatementId targetStatementId,
            int expectedRevision,
            StatementContent content) {
        this(proposalId, content.scope(), targetStatementId, expectedRevision, content, Status.PENDING);
    }
}
