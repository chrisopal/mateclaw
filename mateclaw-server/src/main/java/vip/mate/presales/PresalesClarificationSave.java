package vip.mate.presales;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Clarification rules; the caller owns project authority, transaction and item persistence. */
final class PresalesClarificationSave {
    private PresalesClarificationSave() {}

    enum Status {
        OPEN,
        ANSWERED
    }

    /** Raw status remains unparsed until question validation, preserving error precedence. */
    record Draft(
            String question,
            String status,
            String requirementId,
            String ownerId,
            String answer,
            String answerSourceId) {}

    record Decision(Status status, String ownerId, String answeredBy, String answeredAt) {}

    static Decision decide(
            Draft draft,
            String actor,
            Consumer<String> requireProjectRequirement,
            UnaryOperator<String> resolveWorkspaceOwner) {
        PresalesProjectItems.text(draft.question(), "question", 5000);
        Status status;
        try {
            status = Status.valueOf(draft.status());
        } catch (IllegalArgumentException invalid) {
            throw new PresalesRejected(400, "INVALID_REQUEST", "Invalid status");
        }
        if (!draft.requirementId().isBlank())
            requireProjectRequirement.accept(draft.requirementId());
        String owner =
                draft.ownerId().isBlank() ? null : resolveWorkspaceOwner.apply(draft.ownerId());
        if (status == Status.ANSWERED) {
            PresalesProjectItems.text(draft.answer(), "answer", 10000);
            PresalesProjectItems.text(draft.answerSourceId(), "answer source", 2000);
            return new Decision(status, owner, actor, LocalDateTime.now(ZoneOffset.UTC).toString());
        }
        return new Decision(status, owner, null, null);
    }
}
