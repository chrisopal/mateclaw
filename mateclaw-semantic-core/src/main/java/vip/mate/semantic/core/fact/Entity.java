package vip.mate.semantic.core.fact;

import java.util.Objects;
import java.net.URI;
import java.util.Set;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;

/** Stable identity of an entity within one semantic graph. */
public record Entity(EntityId entityId, GraphScope scope, String iri, Set<String> assertedTypes, String displayName) {

    public Entity {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(scope, "scope");
        if (iri == null || iri.isBlank() || !URI.create(iri).isAbsolute()) {
            throw new IllegalArgumentException("iri must be an absolute IRI");
        }
        Objects.requireNonNull(assertedTypes, "assertedTypes");
        assertedTypes = Set.copyOf(assertedTypes);
        assertedTypes.forEach(type -> {
            if (type == null || type.isBlank() || !URI.create(type).isAbsolute()) {
                throw new IllegalArgumentException("asserted type must be an absolute IRI");
            }
        });
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
