package vip.mate.semantic.core.conflict;

import java.util.Objects;

/** One deterministic conflict between a candidate and a competing member. */
public record ConflictFinding(
        ConflictKind kind,
        ConflictMemberRef competingMember,
        String reason) {

    public ConflictFinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(competingMember, "competingMember");
        Objects.requireNonNull(reason, "reason");
    }

    public ConflictMemberRef memberRef() {
        return competingMember;
    }

    public ConflictMemberRef member() {
        return competingMember;
    }
}
