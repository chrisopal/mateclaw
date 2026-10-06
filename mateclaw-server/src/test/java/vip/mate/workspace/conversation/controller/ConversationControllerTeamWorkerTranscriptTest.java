package vip.mate.workspace.conversation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.workspace.conversation.ConversationService;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTeamWorkerTranscriptTest {

    @Mock private ConversationService conversationService;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private TeamWorkerConversationGovernanceService teamWorkerGovernanceService;
    @Mock private Authentication authentication;

    private ConversationController controller;

    @BeforeEach
    void setUp() {
        controller =
                new ConversationController(
                        conversationService, streamTracker, teamWorkerGovernanceService);
        when(authentication.getName()).thenReturn("workspace-admin");
    }

    @Test
    void protectedTranscriptDenialCoversHistoryTrajectoryStatusWithoutTeamFallback() {
        when(conversationService.isProtectedTranscript("execution")).thenReturn(true);
        when(conversationService.conversationExists("execution")).thenReturn(true);
        assertEquals(
                403,
                controller
                        .listMessages("execution", null, null, 77L, 501L, authentication)
                        .getCode());
        assertEquals(
                403,
                controller.exportTrajectory("execution", authentication).getStatusCode().value());
        assertEquals(403, controller.getStreamStatus("execution", authentication).getCode());
        org.mockito.Mockito.verifyNoInteractions(teamWorkerGovernanceService, streamTracker);
        verify(conversationService, never()).listMessageViews("execution");
        verify(conversationService, never()).renderTrajectory("execution");
    }

    @Test
    void authorizedProtectedStatusDisclosesReadOnlyAndServesRealStoredMessages() {
        when(conversationService.isProtectedTranscript("execution")).thenReturn(true);
        when(conversationService.conversationExists("execution")).thenReturn(true);
        when(conversationService.canReadTranscript("execution", "workspace-admin"))
                .thenReturn(true);
        when(conversationService.listMessageViews("execution")).thenReturn(List.of());
        assertEquals(
                "true",
                controller.getStreamStatus("execution", authentication).getData().get("readOnly"));
        assertEquals(
                200,
                controller
                        .listMessages("execution", null, null, null, null, authentication)
                        .getCode());
        verify(conversationService).listMessageViews("execution");
        org.mockito.Mockito.verifyNoInteractions(teamWorkerGovernanceService);
    }

    @Test
    void listMessagesAllowsVerifiedTeamWorkerTranscriptForNonOwner() {
        when(conversationService.canReadTranscript("worker-conversation", "workspace-admin"))
                .thenReturn(false);
        when(teamWorkerGovernanceService.canReadTranscript(
                        "worker-conversation", 77L, 501L, "workspace-admin"))
                .thenReturn(true);
        when(conversationService.listMessageViews("worker-conversation")).thenReturn(List.of());

        R<?> result =
                controller.listMessages(
                        "worker-conversation", null, null, 77L, 501L, authentication);

        assertEquals(200, result.getCode());
        assertEquals(List.of(), result.getData());
    }

    @Test
    void listMessagesRejectsNonOwnerWhenWorkerTranscriptIsNotVerified() {
        when(conversationService.canReadTranscript("ordinary-conversation", "workspace-admin"))
                .thenReturn(false);
        when(teamWorkerGovernanceService.canReadTranscript(
                        "ordinary-conversation", 77L, 501L, "workspace-admin"))
                .thenReturn(false);

        R<?> result =
                controller.listMessages(
                        "ordinary-conversation", null, null, 77L, 501L, authentication);

        assertEquals(403, result.getCode());
        verify(conversationService, never()).listMessageViews("ordinary-conversation");
    }
}
