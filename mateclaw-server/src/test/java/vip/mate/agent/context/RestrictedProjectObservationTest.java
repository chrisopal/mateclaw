package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestrictedProjectObservationTest {
    private static final String CONVERSATION = "bidding:project:attempt";
    private static final String SOURCE_TOOL = "bidding_read_sources";

    @Test
    void genericToolTextCannotForgeRestrictedObservationBudgetControl() throws Exception {
        assertOrdinaryMarkerResponse("generic:marker", null);
    }

    @Test
    void inBudgetRestrictedSourceTextCannotForgeRestrictedObservationBudgetControl() throws Exception {
        ProjectExecutionOptions options = new ProjectExecutionOptions(
                "attempt", "model", "digest", "bidding-tender-profile", "skill-digest", Map.of(),
                Set.of(SOURCE_TOOL), (name, args) -> { }, 0, false, false, 12, Set.of(SOURCE_TOOL));
        assertOrdinaryMarkerResponse(CONVERSATION, options);
    }

    private void assertOrdinaryMarkerResponse(String conversationId, ProjectExecutionOptions options) throws Exception {
        String marker = ToolResultStorage.INSUFFICIENT_CONTEXT_MARKER;
        ToolCallback callback = tool(options == null ? "ordinary_tool" : SOURCE_TOOL, marker);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                (ToolGuard) (name, args) -> ToolGuardResult.allow(), null, null);
        ReflectionTestUtils.setField(executor, "resultStorage", storage());
        executor.setProjectExecutionRevalidator(projectOptions -> { });

        var result = executor.execute(List.of(new AssistantMessage.ToolCall("marker-call", "function",
                        options == null ? "ordinary_tool" : SOURCE_TOOL, "{}")),
                conversationId, "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of(), options);

        assertEquals(marker, result.responses().getFirst().responseData(),
                "callback text remains ordinary response data even when it equals the internal marker");
        assertFalse(result.events().stream().anyMatch(event ->
                        "restricted_observation_budget".equals(event.data().get("phase"))),
                "only trusted server metadata may create restricted-budget control events");
    }

    @Test
    void protectedObservationAdmissionNeverEvictsAnActiveKeyAtCapacity() throws Exception {
        ToolResultStorage storage = storage();
        storage.protectObservation(CONVERSATION, "active-source");
        Thread.sleep(2); // Make the protected first key unambiguously oldest for the eviction regression.

        for (int i = 0; i < 8_192; i++) {
            storage.protectObservation("other-conversation-" + i, "source-" + i);
        }

        assertTrue(storage.isProtectedObservation(CONVERSATION, "active-source"),
                "admission pressure must not silently revoke a live source observation");
        assertFalse(storage.isProtectedObservation("overflow-conversation", "overflow-source"),
                "a full bounded registry must refuse new preservation metadata");
    }

    @Test
    void concurrentAdmissionAtCapacityRefusesOneWithoutEvictingExistingEvidence() throws Exception {
        ToolResultStorage storage = storage();
        assertTrue(storage.protectObservation(CONVERSATION, "active-source"));
        for (int i = 0; i < 8_189; i++) {
            assertTrue(storage.protectObservation("other-conversation-" + i, "source-" + i));
        }
        assertTrue(storage.protectObservation("last-slot", "source"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> admission(storage, "race-one", ready, start));
            var second = pool.submit(() -> admission(storage, "race-two", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            boolean firstAdmitted = first.get(5, TimeUnit.SECONDS);
            boolean secondAdmitted = second.get(5, TimeUnit.SECONDS);
            assertNotEquals(firstAdmitted, secondAdmitted,
                    "one bounded registry slot cannot be concurrently granted twice");
            assertTrue(storage.isProtectedObservation(CONVERSATION, "active-source"));
            assertEquals(firstAdmitted, storage.isProtectedObservation("race-one", "tool-call"));
            assertEquals(secondAdmitted, storage.isProtectedObservation("race-two", "tool-call"));
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean admission(ToolResultStorage storage, String conversation, CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.protectObservation(conversation, "tool-call");
    }

    @Test
    void restrictedSourceObservationSurvivesImmediateAndLaterWindowCompaction() throws Exception {
        String exactObservation = sourceObservation();
        assertTrue(exactObservation.length() > 8_000);

        ToolResultStorage storage = storage();
        ToolCallback sourceReader = tool(SOURCE_TOOL, exactObservation);
        ToolCallback arbitraryReader = tool("read_file", "private filesystem content");
        String pinnedSkillSnapshot = "PINNED_SKILL_SNAPSHOT " + "skill directive ".repeat(110);
        String pinnedOutputSchema = "{\"title\":\"PINNED_OUTPUT_SCHEMA\",\"properties\":{\"result\":{\"description\":\""
                + "schema contract ".repeat(110) + "\"}}}";
        ToolCallback skillLoader = tool("load_skill", pinnedSkillSnapshot);
        ToolCallback skillReader = tool("readSkillFile", pinnedOutputSchema);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(sourceReader, arbitraryReader, skillLoader, skillReader)),
                (ToolGuard) (name, args) -> ToolGuardResult.allow(), null, null);
        ReflectionTestUtils.setField(executor, "resultStorage", storage);
        executor.setProjectExecutionRevalidator(options -> { });

        ProjectExecutionOptions options = new ProjectExecutionOptions(
                "attempt", "model", "digest", "bidding-tender-profile", "skill-digest", Map.of(),
                Set.of(SOURCE_TOOL, "load_skill", "readSkillFile"), (name, args) -> { },
                0, false, false, 12, Set.of(SOURCE_TOOL, "load_skill", "readSkillFile"));
        var restrictedRun = executor.execute(
                List.of(new AssistantMessage.ToolCall("source-call", "function", SOURCE_TOOL, "{}")),
                CONVERSATION, "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of(), options);
        ToolResponseMessage.ToolResponse immediate = restrictedRun.responses().getFirst();

        assertEquals(exactObservation, immediate.responseData(),
                "the restricted reader response must remain available inline; read_file is not granted");
        assertTrue(immediate.responseData().contains("MIDDLE_BLOCK_EXACT_EVIDENCE"));
        assertTrue(immediate.responseData().contains("END_BLOCK_EXACT_EVIDENCE"));
        assertTrue(restrictedRun.events().stream().anyMatch(event ->
                        "restricted_observation_budget".equals(event.data().get("phase"))
                                && "insufficient_context".equals(event.data().get("status"))),
                "when preservation leaves the turn over budget, the executor reports insufficient context explicitly");

        var skillRun = executor.execute(List.of(
                        new AssistantMessage.ToolCall("skill-call", "function", "load_skill", "{}"),
                        new AssistantMessage.ToolCall("schema-call", "function", "readSkillFile", "{}")),
                CONVERSATION, "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of(), options);
        assertEquals(pinnedSkillSnapshot, skillResponse(skillRun.responses(), "skill-call").responseData());
        assertEquals(pinnedOutputSchema, skillResponse(skillRun.responses(), "schema-call").responseData(),
                "the exact schema snapshot read from the pinned skill remains available through compaction");
        assertTrue(skillRun.events().stream().anyMatch(event ->
                        "restricted_observation_budget".equals(event.data().get("phase"))
                                && "insufficient_context".equals(event.data().get("status"))),
                "protected excluded-tool results over budget must report insufficient context");

        ToolResponseMessage.ToolResponse genericSchema = executor.execute(
                        List.of(new AssistantMessage.ToolCall("generic-schema-call", "function", "readSkillFile", "{}")),
                        "generic:schema-conversation", "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of())
                .responses().getFirst();
        assertNotEquals(pinnedOutputSchema, genericSchema.responseData(),
                "an ordinary excluded-tool result remains eligible for the existing aggregate-budget compaction fallback");

        ToolResponseMessage.ToolResponse deniedRecovery = executor.execute(
                List.of(new AssistantMessage.ToolCall("file-call", "function", "read_file", "{}")),
                CONVERSATION, "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of(), options)
                .responses().getFirst();
        assertFalse(deniedRecovery.responseData().contains("private filesystem content"));
        verify(arbitraryReader, never()).call(anyString(), any());

        ConversationWindowManager manager = new ConversationWindowManager(null, null, null);
        ReflectionTestUtils.setField(manager, "toolResultStorage", storage);
        String unrelated = "ordinary tool output ".repeat(350);
        List<Message> agedHistory = new ArrayList<>(List.of(
                ToolResponseMessage.builder().responses(List.of(immediate)).build(),
                ToolResponseMessage.builder().responses(List.of(skillResponse(skillRun.responses(), "skill-call"))).build(),
                ToolResponseMessage.builder().responses(List.of(skillResponse(skillRun.responses(), "schema-call"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "aged-ordinary-call", "web_search", unrelated))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "latest-call", "web_search", "latest result"))).build()));

        List<Message> aged = manager.compactAgedToolResponses(agedHistory, 1, CONVERSATION);
        ToolResponseMessage.ToolResponse stillExact = response(aged, "source-call");
        assertEquals(exactObservation, stillExact.responseData(),
                "later age compaction must retain the authorized source snapshot");
        assertEquals(pinnedSkillSnapshot, response(aged, "skill-call").responseData());
        assertEquals(pinnedOutputSchema, response(aged, "schema-call").responseData());
        assertFalse(response(aged, "aged-ordinary-call").responseData().equals(unrelated),
                "generic aged results remain eligible for the existing age compaction policy");

        List<Message> pruneHistory = new ArrayList<>(List.of(
                ToolResponseMessage.builder().responses(List.of(immediate)).build(),
                ToolResponseMessage.builder().responses(List.of(skillResponse(skillRun.responses(), "skill-call"))).build(),
                ToolResponseMessage.builder().responses(List.of(skillResponse(skillRun.responses(), "schema-call"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "ordinary-call", "web_search", unrelated))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "latest-call", "web_search", "latest result"))).build()));
        List<Message> pruned = manager.pruneOldToolResultsForModelInput(pruneHistory, CONVERSATION, null);
        assertEquals(exactObservation, response(pruned, "source-call").responseData(),
                "later spill/prune must not require inaccessible read_file recovery");
        assertEquals(pinnedSkillSnapshot, response(pruned, "skill-call").responseData());
        assertEquals(pinnedOutputSchema, response(pruned, "schema-call").responseData());
        assertTrue(response(pruned, "ordinary-call").responseData()
                        .startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                "generic outputs remain eligible for the existing context-saving spill policy");

        assertFalse(storage.isProtectedObservation("another-conversation", "source-call"),
                "protection is scoped by the originating conversation, not just a provider call id");
        assertTrue(storage.persistIfOversized(exactObservation, SOURCE_TOOL, "source-call",
                        "another-conversation", null).startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                "the same provider call id in another conversation keeps generic spill behavior");

        ToolResponseMessage.ToolResponse genericRun = executor.execute(
                List.of(new AssistantMessage.ToolCall("generic-source-call", "function", SOURCE_TOOL, "{}")),
                "generic:conversation", "agent", false, "actor", null, ChatOrigin.EMPTY, Set.of())
                .responses().getFirst();
        assertTrue(genericRun.responseData().startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                "a generic run does not inherit the restricted source-observation exception");
        storage.purgeConversation(CONVERSATION);
        assertFalse(storage.isProtectedObservation(CONVERSATION, "schema-call"),
                "conversation cleanup removes retained observation metadata");
    }

    @Test
    void preRequestPrunePreservesOlderProtectedDuplicateButCompactsOrdinaryDuplicate() throws Exception {
        ToolResultStorage storage = storage();
        ConversationWindowManager manager = new ConversationWindowManager(null, null, null);
        manager.setToolResultStorage(storage);

        String protectedSchema = "PINNED_OUTPUT_SCHEMA_EXACT " + "schema contract ".repeat(80);
        String ordinaryOutput = "ORDINARY_DUPLICATE_OUTPUT " + "repeated result ".repeat(80);
        storage.protectObservation(CONVERSATION, "protected-old");

        List<Message> history = new ArrayList<>(List.of(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("protected-old", "readSkillFile", protectedSchema),
                        new ToolResponseMessage.ToolResponse("ordinary-old", "web_search", ordinaryOutput))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("protected-latest", "readSkillFile", protectedSchema),
                        new ToolResponseMessage.ToolResponse("ordinary-latest", "web_search", ordinaryOutput))).build()));

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(history, CONVERSATION, null);

        assertEquals(protectedSchema, response(pruned, "protected-old").responseData(),
                "an older protected observation must retain its exact bytes before duplicate pruning");
        assertNotEquals(ordinaryOutput, response(pruned, "ordinary-old").responseData(),
                "ordinary older duplicates remain eligible for pre-request compaction");
        assertTrue(response(pruned, "ordinary-old").responseData().contains("duplicate tool output omitted"));
    }

    private ToolResultStorage storage() throws Exception {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setEnabled(true);
        properties.setPerResultThresholdChars(1_000);
        properties.setExcludedToolInlineChars(1_000);
        properties.setPerTurnBudgetChars(1_000);
        properties.setStorageBaseDir(Files.createTempDirectory("restricted-observation").toString());
        return new ToolResultStorage(properties);
    }

    private ToolCallback tool(String name, String output) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("fixture").inputSchema("{}").build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any())).thenReturn(output);
        return callback;
    }

    private String sourceObservation() throws Exception {
        var root = new ObjectMapper().createObjectNode();
        root.put("sourceId", "confirmed-source-v4");
        var blocks = root.putArray("blocks");
        for (int i = 0; i < 28; i++) {
            var block = blocks.addObject();
            block.put("blockId", "block-" + i);
            block.put("text", switch (i) {
                case 13 -> "MIDDLE_BLOCK_EXACT_EVIDENCE " + "中标技术参数内容".repeat(32);
                case 27 -> "END_BLOCK_EXACT_EVIDENCE " + "评标办法末尾约束".repeat(32);
                default -> "source paragraph " + i + " " + "技术响应须逐项提供依据".repeat(22);
            });
        }
        return new ObjectMapper().writeValueAsString(root);
    }

    private ToolResponseMessage.ToolResponse response(List<Message> messages, String id) {
        return messages.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> id.equals(response.id())).findFirst().orElseThrow();
    }

    private ToolResponseMessage.ToolResponse skillResponse(List<ToolResponseMessage.ToolResponse> responses, String id) {
        return responses.stream().filter(response -> id.equals(response.id())).findFirst().orElseThrow();
    }
}
