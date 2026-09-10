package vip.mate.semantic.object;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import vip.mate.semantic.web.OntologyDtos;

/** HTTP contract for an explicit, non-persisting complete-object policy check. */
public final class CompleteObjectValidationDtos {
    private CompleteObjectValidationDtos() {}

    public record ValidationRequest(
            Long expectedGraphVersion,
            String expectedOntologyRevisionId,
            String entityId,
            List<String> assertions) {}

    public record ValidationView(
            String graphId,
            @JsonSerialize(using = vip.mate.semantic.web.SemanticCounterSerializer.class)
            long graphVersion,
            String ontologyRevisionId,
            String policyVersion,
            String entityId,
            boolean valid,
            List<OntologyDtos.Violation> violations) {}
}
