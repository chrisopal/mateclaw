package vip.mate.semantic.core.fact;

import java.util.Objects;

/** A reference to a declared property or relation predicate. */
public sealed interface PredicateRef permits PredicateRef.PropertyRef, PredicateRef.RelationRef {

    String key();

    static PropertyRef property(String key) {
        return new PropertyRef(key);
    }

    static RelationRef relation(String key) {
        return new RelationRef(key);
    }

    record PropertyRef(String key) implements PredicateRef {
        public PropertyRef {
            requireKey(key);
        }
    }

    record RelationRef(String key) implements PredicateRef {
        public RelationRef {
            requireKey(key);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("predicate key must not be blank");
        }
    }
}
