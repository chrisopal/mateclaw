package vip.mate.semantic.core.fact;

import java.net.URI;

/** A reference to a declared property or relation predicate. */
public sealed interface PredicateRef permits PredicateRef.PropertyRef, PredicateRef.RelationRef {

    String iri();

    static PropertyRef property(String iri) {
        return new PropertyRef(iri);
    }

    static RelationRef relation(String iri) {
        return new RelationRef(iri);
    }

    record PropertyRef(String iri) implements PredicateRef {
        public PropertyRef {
            requireIri(iri);
        }
    }

    record RelationRef(String iri) implements PredicateRef {
        public RelationRef {
            requireIri(iri);
        }
    }

    private static void requireIri(String iri) {
        if (iri == null || iri.isBlank() || !URI.create(iri).isAbsolute()) {
            throw new IllegalArgumentException("predicate must be an absolute IRI");
        }
    }
}
