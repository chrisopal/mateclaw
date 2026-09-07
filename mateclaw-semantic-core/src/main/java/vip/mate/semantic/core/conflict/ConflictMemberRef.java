package vip.mate.semantic.core.conflict;

import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.ProposalId;
import vip.mate.semantic.core.identity.SemanticIds.StatementId;

/** Strongly typed reference to a competing revision or proposal. */
public sealed interface ConflictMemberRef
        permits ConflictMemberRef.StatementRevisionRef, ConflictMemberRef.ChangeProposalRef {

    record StatementRevisionRef(StatementId statementId, int revision) implements ConflictMemberRef {
        public StatementRevisionRef {
            Objects.requireNonNull(statementId, "statementId");
            if (revision <= 0) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    record ChangeProposalRef(ProposalId proposalId) implements ConflictMemberRef {
        public ChangeProposalRef {
            Objects.requireNonNull(proposalId, "proposalId");
        }
    }
}
