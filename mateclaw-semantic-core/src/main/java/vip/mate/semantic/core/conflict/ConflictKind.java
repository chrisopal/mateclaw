package vip.mate.semantic.core.conflict;

/** Deterministic conflict outcomes for otherwise valid statement content. */
public enum ConflictKind {
    ASSERTION_CONTRADICTION,
    BUSINESS_SINGLE_VALUE,
    BUSINESS_TEMPORAL_UNCERTAINTY,
    TEMPORAL_UNCERTAINTY
}
