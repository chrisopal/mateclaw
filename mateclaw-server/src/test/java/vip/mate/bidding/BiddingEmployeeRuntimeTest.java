package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.node.ReasoningNode;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.model.AgentEntity;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bidding_runtime_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class BiddingEmployeeRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BiddingEmployeeRuntime runtime;
    @Autowired BiddingEmployeeBindings bindings;
    @Autowired BiddingSkillPackages packages;
    @Autowired AgentService agents;
    @Autowired vip.mate.agent.binding.service.AgentBindingService agentBindings;
    @Autowired ModelConfigService models;
    @Autowired vip.mate.llm.failover.AvailableProviderPool providerPool;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockBean ProviderChatModelFactory providerFactory;
    @MockBean BiddingAccess access;
    @Test void rejectsPartialFinalAnswerEvenWhenItIsValidJson() {
        var stream = Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false),
                AgentService.StreamDelta.event("project_execution_failed", Map.of(
                        "code", "STREAM_INCOMPLETE", "category", "TRANSIENT", "resultUnknown", true,
                        "partial", true, "stopped", false)));
        var result = BiddingEmployeeRuntime.readResult(stream, "skill-digest", "model-digest");
        assertNull(result.payload());
        assertTrue(result.failure().partial());

        var preservesSpecificFailure = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_execution_failed", Map.of(
                        "code", "OUTPUT_INVALID", "category", "VALIDATION", "resultUnknown", false,
                        "partial", false, "stopped", false)),
                AgentService.StreamDelta.event("project_execution_failed", Map.of(
                        "code", "STREAM_INCOMPLETE", "category", "TRANSIENT", "resultUnknown", true,
                        "partial", true, "stopped", false))), "skill-digest", "model-digest");
        assertEquals("OUTPUT_INVALID", preservesSpecificFailure.failure().code());

        var preservesFailureBeforeTransportError = BiddingEmployeeRuntime.readResult(Flux.concat(
                Flux.just(AgentService.StreamDelta.event("project_execution_failed", Map.of(
                        "code", "MODEL_AUTH_REJECTED", "category", "AUTHENTICATION", "resultUnknown", false,
                        "partial", false, "stopped", false))),
                Flux.error(new IllegalStateException("transport closed after failure event"))),
                "skill-digest", "model-digest");
        assertEquals("MODEL_AUTH_REJECTED", preservesFailureBeforeTransportError.failure().code());
        assertEquals("AUTHENTICATION", preservesFailureBeforeTransportError.failure().category());
    }

    @Test void acceptsOnlyPinnedSkillAndExplicitNormalCompletion() {
        var result = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false),
                AgentService.StreamDelta.event("project_execution_completed", Map.of(
                        "configDigest", "model-digest", "skillDigest", "skill-digest"))),
                "skill-digest", "model-digest");
        assertNotNull(result.payload());
        assertNull(result.failure());

        var incomplete = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false)), "skill-digest", "model-digest");
        assertNull(incomplete.payload());
        assertEquals("EXECUTION_NOT_COMPLETED", incomplete.failure().code());
    }

    @Test void validatesFinalObjectAgainstThePinnedOutputSchema() {
        var stream = Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false),
                AgentService.StreamDelta.event("project_execution_completed", Map.of(
                        "configDigest", "model-digest", "skillDigest", "skill-digest")));
        var accepted = BiddingEmployeeRuntime.readResult(stream, "skill-digest", "model-digest",
                "{\"type\":\"object\",\"required\":[\"items\"],\"properties\":{\"items\":{\"type\":\"array\"}},\"additionalProperties\":false}");
        assertNotNull(accepted.payload());
        assertNull(accepted.failure());

        var rejected = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"items\":\"not-an-array\",\"extra\":true}", false),
                AgentService.StreamDelta.event("project_execution_completed", Map.of(
                        "configDigest", "model-digest", "skillDigest", "skill-digest"))),
                "skill-digest", "model-digest",
                "{\"type\":\"object\",\"required\":[\"items\"],\"properties\":{\"items\":{\"type\":\"array\"}},\"additionalProperties\":false}");
        assertNull(rejected.payload());
        assertEquals("OUTPUT_INVALID", rejected.failure().code());

        var missingSchema = BiddingEmployeeRuntime.readResult(stream, "skill-digest", "model-digest", null);
        assertNull(missingSchema.payload());
        assertEquals("OUTPUT_SCHEMA_MISSING", missingSchema.failure().code());

        var unsupportedSchema = BiddingEmployeeRuntime.readResult(stream, "skill-digest", "model-digest",
                "{\"type\":\"object\",\"oneOf\":[{\"required\":[\"items\"]}]}");
        assertNull(unsupportedSchema.payload());
        assertEquals("OUTPUT_INVALID", unsupportedSchema.failure().code());
    }

    @Test void regexPatternUsesFindSemanticsWithAnchoredAlternatives() {
        String schema = "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"pattern\":\"^a|z$\"}}}";
        var result = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
                AgentService.StreamDelta.finalAnswer("{\"label\":\"abc\"}", false),
                AgentService.StreamDelta.event("project_execution_completed", Map.of(
                        "configDigest", "model-digest", "skillDigest", "skill-digest"))),
                "skill-digest", "model-digest", schema);
        assertNotNull(result.payload());
        assertNull(result.failure());
    }

    @Test void realGraphNodeEmitsFailureAfterPartialJsonWithExactlyOneProviderCall() throws Exception {
        ChatModel provider = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        ChatResponse partial = new ChatResponse(List.of(new Generation(new AssistantMessage("{\"items\":"))));
        when(provider.stream(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Flux.concat(Flux.just(partial), Flux.error(new IllegalStateException("fake stream disconnect")));
        });
        ChatStreamTracker tracker = mock(ChatStreamTracker.class);
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(tracker);
        helper.setRetryDisabled(true);
        ReasoningNode reasoning = new ReasoningNode(provider, AgentToolSet.fromCallbacks(List.of(), List.of()), null,
                false, helper, null, tracker, 1024, null);
        KeyStrategyFactory strategy = KeyStrategy.builder()
                .addStrategy(MateClawStateKeys.MESSAGES, KeyStrategy.APPEND)
                .addStrategy(MateClawStateKeys.PENDING_EVENTS, KeyStrategy.APPEND)
                .addStrategy(MateClawStateKeys.LLM_CALL_COUNT, KeyStrategy.REPLACE)
                .addStrategy(MateClawStateKeys.FINAL_ANSWER, KeyStrategy.REPLACE)
                .addStrategy(MateClawStateKeys.FINISH_REASON, KeyStrategy.REPLACE).build();
        var compiled = new StateGraph("bidding-failed-stream", strategy)
                .addNode("reason", AsyncNodeAction.node_async(reasoning))
                .addEdge(StateGraph.START, "reason").addEdge("reason", StateGraph.END)
                .compile(CompileConfig.builder().build());
        var claim = new BiddingTypes.Claim(new BiddingTypes.Scope("1", "actor", "project"), "task", "attempt",
                "token", 1, 1, java.time.Instant.now().plusSeconds(30), "42",
                new BiddingTypes.SkillPin("1", "v1", "skill-digest", Map.of("SKILL.md", "# skill")),
                "7", "cfg", List.of(), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        var options = new vip.mate.agent.execution.ProjectExecutionOptions("attempt", "7", "cfg", "skill",
                "skill-digest", Map.of("SKILL.md", "# skill", "output.schema.json", "{\"type\":\"object\"}"), java.util.Set.of(), new BiddingToolScope(claim),
                0, false, false, 12);
        Map<String, Object> input = new HashMap<>();
        input.put(MateClawStateKeys.USER_MESSAGE, "produce JSON");
        input.put(MateClawStateKeys.CONVERSATION_ID, "bidding:project:attempt");
        input.put(MateClawStateKeys.AGENT_ID, "42");
        input.put(MateClawStateKeys.SYSTEM_PROMPT, "Return JSON only");
        input.put(MateClawStateKeys.MESSAGES, List.of(new UserMessage("produce JSON")));
        input.put(MateClawStateKeys.CURRENT_ITERATION, 0);
        input.put(MateClawStateKeys.MAX_ITERATIONS, 12);
        input.put(MateClawStateKeys.PROJECT_EXECUTION_OPTIONS, options);

        var output = compiled.stream(input, RunnableConfig.builder().build()).collectList().block();
        assertNotNull(output);
        assertEquals(1, calls.get());
        var events = output.stream().flatMap(nodeOutput -> GraphEventPublisher.extractEvents(nodeOutput).stream()).toList();
        assertTrue(events.stream().anyMatch(event -> "project_execution_failed".equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> "project_execution_completed".equals(event.type())));
        var rejected = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false),
                AgentService.StreamDelta.event("project_execution_failed", Map.of("code", "STREAM_INCOMPLETE",
                        "category", "TRANSIENT", "resultUnknown", true, "partial", true, "stopped", false))),
                "skill-digest", "cfg");
        assertNull(rejected.payload());
    }

    @Test void executeUsesProductionAgentGraphAndDoesNotRetryBrokenProviderStream() {
        org.mockito.Mockito.doNothing().when(access).requireActor(any(), any());
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key', enabled=TRUE, chat_model='OpenAIChatModel' WHERE provider_id='openai'");
        providerPool.add("openai");
        long modelId = Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits());
        String modelName = "bidding-runtime-" + modelId;
        String provider = "openai";
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?, ?,?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                modelId, "bidding runtime test", provider, modelName);
        ModelConfigEntity model = models.getModel(modelId);
        AgentEntity agent = new AgentEntity();
        agent.setName("bidding-runtime-" + modelId); agent.setDescription("runtime path test");
        agent.setAgentType("react"); agent.setRuntimeType("native"); agent.setSystemPrompt("Return JSON only");
        agent.setMaxIterations(12); agent.setWorkspaceId(1L); agent.setModelName(modelName);
        agent = agents.createAgent(agent);

        String projectId = "project-" + modelId, taskId = "task-" + modelId, attemptId = "attempt-" + modelId;
        String token = "token-" + modelId;
        Map<String, String> skillFiles = Map.of("SKILL.md", "---\nname: bidding-test\ndescription: test\n---\n# pinned skill\n",
                "output.schema.json", "{\"type\":\"object\",\"required\":[\"items\"],\"properties\":{\"items\":{\"type\":\"array\"}},\"additionalProperties\":false}");
        String skillDigest = "digest-" + modelId;
        String configDigest = bindings.configDigest(new BiddingTypes.Scope("1", "actor", projectId), agent.getId().toString());
        String filesJson;
        try { filesJson = mapper.writeValueAsString(skillFiles); } catch (Exception e) { throw new AssertionError(e); }
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                "pin-" + modelId, "1", projectId, "skill-" + modelId, "v1", skillDigest, filesJson);
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,active_attempt_id,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'RUNNING',?,0,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                taskId, "1", projectId, agent.getId().toString(), "pin-" + modelId, configDigest, "{}", "[]", attemptId);
        jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,started_at) VALUES(?,?,?, ?,1,?,'RUNNING','{}',CURRENT_TIMESTAMP)",
                attemptId, "1", projectId, taskId, token);
        BiddingTypes.Ref sourceSet = new BiddingTypes.Ref("sourceSet", "current", 1, "source-set-" + modelId);
        try {
            jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,'sourceSet','current',1,?,'[]','CONFIRMED',?,CURRENT_TIMESTAMP)",
                    "revision-" + modelId, "1", projectId, "{\"sourceRefs\":[]}", sourceSet.digest());
            jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,'sourceSet','current',1,?)",
                    "1", projectId, mapper.writeValueAsString(sourceSet));
        } catch (Exception e) { throw new AssertionError(e); }
        BiddingTypes.Claim claim = new BiddingTypes.Claim(new BiddingTypes.Scope("1", "actor", projectId), taskId,
                attemptId, token, 1, 0, java.time.Instant.now().plusSeconds(60), agent.getId().toString(),
                new BiddingTypes.SkillPin("skill-" + modelId, "v1", skillDigest, skillFiles),
                Long.toString(modelId), configDigest, List.of(sourceSet), mapper.createObjectNode().put("task", "run"));

        var fixedContractCheck = BiddingEmployeeRuntime.readResult(Flux.just(
                AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", skillDigest)),
                AgentService.StreamDelta.finalAnswer("{\"items\":\"wrong type\"}", false),
                AgentService.StreamDelta.event("project_execution_completed", Map.of(
                        "configDigest", configDigest, "skillDigest", skillDigest))),
                skillDigest, configDigest, skillFiles.get("output.schema.json"));
        assertNull(fixedContractCheck.payload());
        assertEquals("OUTPUT_INVALID", fixedContractCheck.failure().code());

        AtomicInteger calls = new AtomicInteger();
        ChatModel fake = mock(ChatModel.class);
        when(fake.stream(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Flux.concat(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"items\":[]}"))))),
                    Flux.error(new IllegalStateException("controlled provider disconnect")));
        });
        when(providerFactory.buildFor(any(), any())).thenReturn(fake);

        BiddingTypes.Execution result = runtime.execute(claim);
        assertNull(result.payload());
        assertEquals("STREAM_INCOMPLETE", result.failure().code());
        assertEquals(1, calls.get());
    }
}
