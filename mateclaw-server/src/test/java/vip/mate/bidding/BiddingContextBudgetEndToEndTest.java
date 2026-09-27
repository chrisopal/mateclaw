package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

@SpringBootTest(classes = vip.mate.MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:bidding_context_budget_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "spring.ai.dashscope.api-key=test-key", "mateclaw.semantic.enabled=false",
                "mateclaw.presales.enabled=false", "mateclaw.bidding.enabled=true",
                "mateclaw.bidding.scheduler-enabled=false", "spring.main.web-application-type=none"
        })
class BiddingContextBudgetEndToEndTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired WorkspaceService workspaces;
    @Autowired BiddingProjectService projects;
    @Autowired BiddingEmployeeBindings employeeBindings;
    @Autowired BiddingEmployeeRuntime runtime;
    @Autowired BiddingTaskService tasks;
    @Autowired ModelConfigService models;
    @Autowired AgentService agents;
    @Autowired AvailableProviderPool providerPool;
    @Autowired vip.mate.agent.graph.executor.ToolResultProperties resultProperties;
    @Autowired vip.mate.agent.graph.executor.ToolResultStorage resultStorage;
    @MockBean ProviderChatModelFactory providerFactory;

    @Test
    void oversizedPinnedObservationTerminatesBeforeAnotherProviderCallAndPersistsValidationFailure() throws Exception {
        runScenario(900, 0, 700, true, false);
    }

    @Test
    void pinnedObservationThatExceedsLaterWindowStopsBeforeAnotherProviderCall() throws Exception {
        runScenario(30_000, 4096, 2_500, true, false);
    }

    @Test
    void inBudgetPinnedObservationAllowsNormalSecondProviderCall() throws Exception {
        runScenario(30_000, 0, 20, false, false);
    }

    @Test
    void registryCapacityRejectsNewEvidenceAndPersistsFailureWithoutAnotherProviderCall() throws Exception {
        runScenario(30_000, 0, 700, true, true);
    }

    private void runScenario(int turnBudgetChars, int contextWindowTokens, int skillRepeat,
            boolean expectBudgetFailure, boolean fillProtectionRegistry) throws Exception {
        resultProperties.setPerTurnBudgetChars(turnBudgetChars);
        resultProperties.setPerResultThresholdChars(200);
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key',enabled=TRUE,chat_model='OpenAIChatModel' WHERE provider_id='openai'");
        providerPool.add("openai");

        UserEntity user = new UserEntity();
        user.setUsername("context-budget-" + java.util.UUID.randomUUID()); user.setPassword("pw");
        user.setRole("user"); user.setDeleted(0); auth.createUser(user);
        WorkspaceEntity workspace = new WorkspaceEntity(); workspace.setName("context-budget-" + java.util.UUID.randomUUID());
        workspace.setDeleted(0);
        String workspaceId = workspaces.create(workspace, user.getId()).getId().toString();
        BiddingTypes.Scope scope = new BiddingTypes.Scope(workspaceId, user.getId().toString(), null);
        var project = projects.create(scope, new BiddingTypes.NewProject(
                java.util.UUID.randomUUID().toString(), "上下文预算回归", "一标段", user.getId().toString()));
        String projectId = project.path("id").asText();
        scope = new BiddingTypes.Scope(workspaceId, user.getId().toString(), projectId);

        long modelId = Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits());
        String modelName = "context-budget-" + modelId;
        String provider = "openai";
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,max_input_tokens,create_time,update_time,deleted) VALUES(?,?,?,?,'chat',TRUE,FALSE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                modelId, "context budget test", provider, modelName, contextWindowTokens);
        AgentEntity agent = new AgentEntity();
        agent.setName("context-budget-" + modelId); agent.setDescription("controlled context budget runtime");
        agent.setAgentType("react"); agent.setRuntimeType("native"); agent.setSystemPrompt("Return only the pinned JSON contract.");
        agent.setMaxIterations(8); agent.setWorkspaceId(Long.valueOf(workspaceId)); agent.setModelName(modelName);
        agent.setEnabled(true); agent = agents.createAgent(agent);

        String taskId = "budget-task-" + modelId, attemptId = "budget-attempt-" + modelId;
        String token = "budget-token-" + modelId, skillId = "budget-skill-" + modelId;
        String skillDigest = "budget-skill-digest-" + modelId;
        String skillText = "---\nname: bidding-test\ndescription: controlled test\n---\n# 固定技能\n" + "规则内容 ".repeat(skillRepeat);
        String schema = "{\"type\":\"object\"}";
        Map<String, String> files = Map.of("SKILL.md", skillText, "output.schema.json", schema);
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                "pin-" + modelId, workspaceId, projectId, skillId, "v1", skillDigest, json.writeValueAsString(files));
        BiddingTypes.Ref sourceSet = new BiddingTypes.Ref("sourceSet", "current", 1, "source-set-" + modelId);
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,'sourceSet','current',1,?,'[]','CONFIRMED',?,CURRENT_TIMESTAMP)",
                "source-set-revision-" + modelId, workspaceId, projectId, "{\"sourceRefs\":[]}", sourceSet.digest());
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,'sourceSet','current',1,?)",
                workspaceId, projectId, json.writeValueAsString(sourceSet));
        String configDigest = employeeBindings.configDigest(scope, agent.getId().toString());
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,active_attempt_id,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,0,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                taskId, workspaceId, projectId, user.getId().toString(), agent.getId().toString(), "pin-" + modelId,
                configDigest, "{\"_bidding\":{\"skillId\":\"" + skillId + "\"},\"input\":{\"task\":\"read the pinned skill\"}}",
                json.writeValueAsString(List.of(sourceSet)), attemptId);
        jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,started_at) VALUES(?,?,?,?,1,?,'RUNNING','{}',CURRENT_TIMESTAMP)",
                attemptId, workspaceId, projectId, taskId, token);
        BiddingTypes.Claim claim = new BiddingTypes.Claim(scope, taskId, attemptId, token, 1, 1,
                Instant.now().plusSeconds(60), agent.getId().toString(),
                new BiddingTypes.SkillPin(skillId, "v1", skillDigest, files), Long.toString(modelId),
                configDigest, List.of(sourceSet), json.createObjectNode().put("task", "read the pinned skill"));

        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel fake = mock(ChatModel.class);
        when(fake.stream(any(Prompt.class))).thenAnswer(invocation -> {
            if (providerCalls.incrementAndGet() == 1) {
                AssistantMessage toolRequest = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("load-call", "function", "load_skill",
                                "{\"skillName\":\"bidding-test\",\"filePath\":\"SKILL.md\"}"),
                        new AssistantMessage.ToolCall("schema-call", "function", "readSkillFile",
                                "{\"skillName\":\"bidding-test\",\"filePath\":\"output.schema.json\"}"))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(toolRequest))));
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
        });
        when(providerFactory.buildFor(any(), any())).thenReturn(fake);

        if (fillProtectionRegistry) {
            for (int i = 0; i < 8192; i++) {
                assertTrue(resultStorage.protectObservation("capacity-seed", "active-" + i));
            }
            assertTrue(resultStorage.isProtectedObservation("capacity-seed", "active-0"));
        }

        BiddingTypes.Execution execution = runtime.execute(claim);
        String conversationId = "bidding:" + projectId + ":" + attemptId;
        assertFalse(resultStorage.isProtectedObservation(conversationId, "load-call"),
                "attempt completion releases only its protection metadata");
        if (fillProtectionRegistry) {
            assertTrue(resultStorage.isProtectedObservation("capacity-seed", "active-0"),
                    "capacity refusal cannot evict an earlier active observation");
            resultStorage.releaseObservations("capacity-seed");
        }

        if (expectBudgetFailure) {
            tasks.complete(claim, execution);
            assertNull(execution.payload());
            assertEquals("INSUFFICIENT_CONTEXT", execution.failure().code());
            assertEquals("VALIDATION", execution.failure().category());
            assertEquals(1, providerCalls.get(), "the graph must stop before a second model call");
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?", String.class, taskId));
            assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?", String.class, attemptId));
            assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE id=?", String.class, attemptId)
                    .contains("INSUFFICIENT_CONTEXT"));
        } else {
            assertEquals(2, providerCalls.get(), "an in-budget pinned result should continue through normal reasoning");
            assertNotNull(execution.payload());
            assertNull(execution.failure());
            jdbc.update("DELETE FROM mate_bidding_attempt WHERE id=?", attemptId);
            jdbc.update("DELETE FROM mate_bidding_task WHERE id=?", taskId);
        }
    }
}
