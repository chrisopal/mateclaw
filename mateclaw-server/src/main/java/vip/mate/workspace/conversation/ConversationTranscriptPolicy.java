package vip.mate.workspace.conversation;

import vip.mate.workspace.conversation.model.ConversationEntity;

/** Domain-owned, current authority for protected execution evidence. Never grants write access. */
public interface ConversationTranscriptPolicy {
    String KIND = "project_execution";

    boolean supports(ConversationEntity conversation);

    boolean canRead(ConversationEntity conversation, String authenticatedUsername);
}
