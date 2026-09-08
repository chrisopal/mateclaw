package vip.mate.semantic.core.ontology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared package-private alias normalization for all ontology term definitions. */
final class AliasNormalizer {

    private AliasNormalizer() {}

    static List<String> normalize(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String trimmed = alias.trim();
            if (!trimmed.isEmpty() && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return Collections.unmodifiableList(normalized);
    }
}
