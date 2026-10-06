package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NumericNode;

/**
 * Project revisions remain exact JSON numbers; item and semantic revisions have separate limits.
 */
final class PresalesProjectRevision {
    static final long MAX_VALUE = 9_007_199_254_740_991L;

    private PresalesProjectRevision() {}

    static Long positiveRevision(JsonNode value) {
        if (value == null) return null;
        long revision;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            revision = value.longValue();
        } else if (value.isTextual()) {
            try {
                revision = Long.parseLong(value.textValue().trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        } else return null;
        return revision > 0 && revision <= MAX_VALUE ? revision : null;
    }

    static boolean matchesRevision(JsonNode value, long revision) {
        Long exact = positiveRevision(value);
        return exact != null && exact == revision;
    }

    static long nextRevision(long current) {
        if (current < 1 || current > MAX_VALUE)
            throw new PresalesRejected(409, "VERSION_CONFLICT", "Stored version is invalid");
        if (current == MAX_VALUE)
            throw new PresalesRejected(409, "VERSION_EXHAUSTED", "Version limit reached");
        return current + 1;
    }

    /** Match JSON read-back node types so exact task-envelope equality survives persistence. */
    static NumericNode number(long revision) {
        return revision >= Integer.MIN_VALUE && revision <= Integer.MAX_VALUE
                ? IntNode.valueOf((int) revision)
                : LongNode.valueOf(revision);
    }
}
