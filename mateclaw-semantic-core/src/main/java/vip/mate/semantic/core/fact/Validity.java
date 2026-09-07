package vip.mate.semantic.core.fact;

import java.time.Instant;
import java.util.Objects;

/** A business-validity interval, or an explicit unknown interval. */
public final class Validity {

    public enum Kind {
        INTERVAL,
        UNKNOWN
    }

    private final Kind kind;
    private final Instant fromInclusive;
    private final Instant toExclusive;

    private Validity(Kind kind, Instant fromInclusive, Instant toExclusive) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromInclusive = fromInclusive;
        this.toExclusive = toExclusive;
        if (kind == Kind.UNKNOWN && (fromInclusive != null || toExclusive != null)) {
            throw new IllegalArgumentException("unknown validity cannot have interval endpoints");
        }
        if (kind == Kind.INTERVAL && fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("validity interval must be half-open and non-empty");
        }
    }

    public Validity(Instant fromInclusive, Instant toExclusive) {
        this(Kind.INTERVAL, fromInclusive, toExclusive);
    }

    public static Validity interval(Instant fromInclusive, Instant toExclusive) {
        return new Validity(Kind.INTERVAL, fromInclusive, toExclusive);
    }

    public static Validity between(Instant fromInclusive, Instant toExclusive) {
        return interval(fromInclusive, toExclusive);
    }

    public static Validity of(Instant fromInclusive, Instant toExclusive) {
        return interval(fromInclusive, toExclusive);
    }

    public static Validity unbounded() {
        return interval(null, null);
    }

    public static Validity unknown() {
        return new Validity(Kind.UNKNOWN, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public Instant fromInclusive() {
        return fromInclusive;
    }

    public Instant toExclusive() {
        return toExclusive;
    }

    public boolean isUnknown() {
        return kind == Kind.UNKNOWN;
    }

    public boolean overlaps(Validity other) {
        Objects.requireNonNull(other, "other");
        if (isUnknown() || other.isUnknown()) {
            return false;
        }
        if (toExclusive != null && other.fromInclusive != null
                && !other.fromInclusive.isBefore(toExclusive)) {
            return false;
        }
        if (other.toExclusive != null && fromInclusive != null
                && !fromInclusive.isBefore(other.toExclusive)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Validity validity)) {
            return false;
        }
        return kind == validity.kind
                && Objects.equals(fromInclusive, validity.fromInclusive)
                && Objects.equals(toExclusive, validity.toExclusive);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, fromInclusive, toExclusive);
    }

    @Override
    public String toString() {
        return isUnknown() ? "UNKNOWN" : "[" + fromInclusive + "," + toExclusive + ")";
    }
}
