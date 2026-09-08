package vip.mate.semantic.core.ontology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional value constraints for a property definition. */
public record PropertyConstraints(List<String> allowedValues, String minimum, String maximum) {

    public PropertyConstraints {
        if (allowedValues != null) {
            allowedValues = Collections.unmodifiableList(new ArrayList<>(allowedValues));
        }
    }
}
