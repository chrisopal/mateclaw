package vip.mate.semantic.core.fact;

import java.util.Objects;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;

/** Stable identity of an entity within one semantic graph. */
public record Entity(EntityId entityId, GraphScope scope, String typeKey, String displayName) {

    public Entity {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(scope, "scope");
        if (typeKey == null || typeKey.isBlank()) {
            throw new IllegalArgumentException("typeKey must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
