package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Legacy wire adapter. Shape validation and copying belong to the command boundary. */
final class PresalesClarificationCodec {
    private PresalesClarificationCodec() {}

    static PresalesClarificationSave.Draft decode(ObjectNode value) {
        return new PresalesClarificationSave.Draft(
                value.path("question").asText(), value.path("status").asText("OPEN"),
                value.path("requirementId").asText(), value.path("ownerId").asText(),
                value.path("answer").asText(), value.path("answerSourceId").asText());
    }

    static void apply(ObjectNode value, PresalesClarificationSave.Decision decision) {
        value.put("status", decision.status().name());
        if (decision.ownerId() != null) value.put("ownerId", decision.ownerId());
        if (decision.answeredBy() != null) {
            value.put("answeredBy", decision.answeredBy()).put("answeredAt", decision.answeredAt());
        } else {
            value.remove(List.of("answeredBy", "answeredAt"));
        }
    }
}
