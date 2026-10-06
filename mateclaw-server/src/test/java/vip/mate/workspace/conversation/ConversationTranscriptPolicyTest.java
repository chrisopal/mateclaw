package vip.mate.workspace.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

@ExtendWith(MockitoExtension.class)
class ConversationTranscriptPolicyTest {
    @Mock ConversationMapper mapper;
    @Mock AuthService auth;
    @Mock ObjectProvider<ConversationTranscriptPolicy> policies;
    @InjectMocks ConversationService service;
    ConversationEntity row;

    @BeforeEach
    void setup() {
        row = new ConversationEntity();
        row.setConversationId("execution");
        row.setConversationKind(ConversationTranscriptPolicy.KIND);
        row.setUsername("alice");
        when(mapper.selectOne(any())).thenReturn(row);
        service.setTranscriptPolicies(policies);
    }

    @Test
    void ownerCannotMutateAndMissingPolicyCannotRead() {
        when(policies.orderedStream()).thenReturn(Stream.empty());
        assertFalse(service.isConversationOwner("execution", "alice"));
        assertFalse(service.isUserMessageAllowed("execution"));
        assertFalse(service.canReadTranscript("execution", "alice"));
        verifyNoInteractions(auth);
    }

    @Test
    void policyReevaluatesEveryReadAndRevocationCannotFallBackToOwner() {
        var policy = mock(ConversationTranscriptPolicy.class);
        when(policies.orderedStream()).thenAnswer(invocation -> Stream.of(policy));
        when(policy.supports(row)).thenReturn(true);
        when(policy.canRead(row, "alice")).thenReturn(true, false);
        assertTrue(service.canReadTranscript("execution", "alice"));
        assertFalse(service.canReadTranscript("execution", "alice"));
        assertFalse(service.isConversationOwner("execution", "alice"));
        verifyNoInteractions(auth);
    }

    @Test
    void multiplePoliciesOrProviderFailureDeny() {
        var policy = mock(ConversationTranscriptPolicy.class);
        when(policy.supports(row)).thenReturn(true);
        when(policies.orderedStream())
                .thenReturn(Stream.of(policy, policy))
                .thenThrow(new IllegalStateException("domain unavailable"));
        assertFalse(service.canReadTranscript("execution", "admin"));
        assertFalse(service.canReadTranscript("execution", "admin"));
        verify(policy, never()).canRead(any(), any());
    }

    @Test
    void ordinaryOwnerStillReadsAndWritesWithoutDomainPolicy() {
        row.setConversationKind("primary");
        assertTrue(service.canReadTranscript("execution", "alice"));
        assertTrue(service.isUserMessageAllowed("execution"));
        verifyNoInteractions(policies);
    }

    @Test
    void executionCreationRefusesToReclassifyExistingMessages() {
        row.setConversationKind("primary");
        row.setAgentId(7L);
        row.setWorkspaceId(1L);
        row.setMessageCount(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getOrCreateExecutionConversation("execution", 7L, "alice", 1L));
        verify(mapper, never()).updateById(any(ConversationEntity.class));
    }
}
