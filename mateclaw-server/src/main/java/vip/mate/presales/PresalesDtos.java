package vip.mate.presales;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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
            @JsonDeserialize(using = PresalesExpectedVersion.class) Integer expectedVersion,
            String operationId) {}

    public record Command(
            @JsonDeserialize(using = PresalesExpectedVersion.class) Integer expectedVersion,
            String operationId,
            String action,
            ObjectNode payload) {
        @JsonIgnore
        CommandAction parsedAction() {
            return CommandAction.from(action);
        }
    }

    public record Generate(
            @JsonDeserialize(using = PresalesExpectedVersion.class) Integer expectedVersion,
            String operationId,
            String skill,
            String taskGoal) {}

    public record Cancel(String operationId) {}

    public record Update(Integer expectedVersion, String operationId, ObjectNode payload) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Update fromJson(ObjectNode payload) {
            return new Update(
                    PresalesExpectedVersion.read(payload.get("expectedVersion")),
                    payload.path("operationId").asText(),
                    payload);
        }
    }

    enum CommandKind {
        UPDATE_PROJECT,
        ARCHIVE,
        BIND_MATERIAL,
        SAVE_REQUIREMENT,
        SAVE_CLARIFICATION,
        UNBIND_MATERIAL,
        CANCEL_AI_TASK,
        SAVE_AI_TASK,
        SAVE_CONTEXT,
        SAVE_REVIEW,
        CREATE_RELEASE,
        APPROVE_RELEASE,
        PUBLISH_RELEASE,
        APPROVE_BASELINE,
        SAVE_FIT_GAP,
        SAVE_SOLUTION,
        UNKNOWN
    }

    record CommandAction(CommandKind kind, String raw) {
        static CommandAction from(String action) {
            String raw = java.util.Objects.toString(action, "");
            CommandKind kind;
            try {
                kind = CommandKind.valueOf(raw);
            } catch (IllegalArgumentException unknown) {
                kind = CommandKind.UNKNOWN;
            }
            return new CommandAction(kind, raw);
        }
    }

    public record ErrorData(String code) {
        public ErrorData {
            java.util.Objects.requireNonNull(code);
        }
    }

    public record Employee(String id, String name, boolean enabled, boolean available) {
        public Employee {
            // Keep the original Map.of projection's rejection of malformed eligible identities.
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(name);
        }
    }

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
