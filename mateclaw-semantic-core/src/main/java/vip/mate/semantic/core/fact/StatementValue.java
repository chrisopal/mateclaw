package vip.mate.semantic.core.fact;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.EntityId;

/** The closed set of values that a statement can carry. */
public sealed interface StatementValue permits
        StatementValue.TextValue,
        StatementValue.DecimalValue,
        StatementValue.BooleanValue,
        StatementValue.DateValue,
        StatementValue.InstantValue,
        StatementValue.EntityValue {

    record TextValue(String value) implements StatementValue {
        public TextValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record DecimalValue(BigDecimal value, String unit) implements StatementValue {
        public DecimalValue {
            Objects.requireNonNull(value, "value");
        }

        public DecimalValue(String value, String unit) {
            this(new BigDecimal(Objects.requireNonNull(value, "value")), unit);
        }
    }

    record BooleanValue(boolean value) implements StatementValue {
    }

    record DateValue(LocalDate value) implements StatementValue {
        public DateValue {
            Objects.requireNonNull(value, "value");
        }

        public DateValue(String value) {
            this(LocalDate.parse(Objects.requireNonNull(value, "value")));
        }
    }

    record InstantValue(Instant value) implements StatementValue {
        public InstantValue {
            Objects.requireNonNull(value, "value");
        }

        public InstantValue(String value) {
            this(Instant.parse(Objects.requireNonNull(value, "value")));
        }
    }

    record EntityValue(EntityId entityId) implements StatementValue {
        public EntityValue {
            Objects.requireNonNull(entityId, "entityId");
        }
    }
}
