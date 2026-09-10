package vip.mate.semantic.core.policy;

import java.math.BigDecimal;

/** Compares a recorded observation to separately recorded limits without rejecting the observation. */
public final class NumericComparison {
    private NumericComparison() {}

    public enum Outcome { BELOW, WITHIN, ABOVE, UNKNOWN, INCOMPARABLE_UNIT }

    public static Outcome compare(BigDecimal observed, String observedUnit,
                                  BigDecimal lower, BigDecimal upper, String limitUnit) {
        if (lower != null && upper != null && lower.compareTo(upper) > 0)
            throw new IllegalArgumentException("Lower limit exceeds upper limit");
        if (observed == null || (lower == null && upper == null)) return Outcome.UNKNOWN;
        if (observedUnit == null || observedUnit.isBlank() || limitUnit == null || limitUnit.isBlank())
            return Outcome.UNKNOWN;
        if (!observedUnit.equals(limitUnit)) return Outcome.INCOMPARABLE_UNIT;
        if (lower != null && observed.compareTo(lower) < 0) return Outcome.BELOW;
        if (upper != null && observed.compareTo(upper) > 0) return Outcome.ABOVE;
        return Outcome.WITHIN;
    }
}
