package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class PresalesDtos {
    private PresalesDtos() {}

    public record Create(
            String name,
            String customer,
            String ownerId,
            String agentId,
            String industry,
            String goal,
            Integer expectedVersion,
            String operationId) {}

    public record Command(
            Integer expectedVersion, String operationId, String action, ObjectNode payload) {}

    public record Capabilities(
            boolean enabled, boolean semanticEnabled, boolean canWrite, boolean canApprove) {}

    public sealed interface Source permits KnowledgeBaseSource, GraphSource {
        String kbId();

        String name();
    }

    public record KnowledgeBaseSource(String kbId, String name) implements Source {}

    public record GraphSource(String kbId, String name, String graphId, String ontologyRevisionId)
            implements Source {}

    public record TrustedStatement(
            String id,
            int revision,
            String graphId,
            String ontologyRevisionId,
            String label,
            List<String> evidenceIds) {
        public TrustedStatement {
            // Match the former valueToTree snapshot, including historical null entries.
            evidenceIds =
                    evidenceIds == null
                            ? null
                            : java.util.Collections.unmodifiableList(
                                    new java.util.ArrayList<>(evidenceIds));
        }
    }

    public record Page(List<ObjectNode> items, long total, int page, int pageSize) {}
}
