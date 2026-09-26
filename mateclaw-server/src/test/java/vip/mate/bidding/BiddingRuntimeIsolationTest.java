package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import vip.mate.agent.context.AgentWorkspaceResolver;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.usage.SkillUsageService;
import vip.mate.tool.builtin.SkillFileTool;
import vip.mate.tool.builtin.SkillLoadTool;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiddingRuntimeIsolationTest {
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private BiddingAccess access;
    private BiddingDependencies dependencies;
    private BiddingEmployeeBindings employeeBindings;
    private BiddingEmployeeRuntime runtime;
    private BiddingTypes.Claim claim;

    @BeforeEach void setUp(org.junit.jupiter.api.TestInfo testInfo) {
        if (testInfo.getTestMethod().map(method -> method.getName()
                .equals("runtimeBeanStartsWithoutOptionalAgentExecutionGraph")).orElse(false)) return;
        db = new EmbeddedDatabaseBuilder().setName("bidding-runtime-" + java.util.UUID.randomUUID())
                .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE mate_bidding_task (id VARCHAR(64) PRIMARY KEY, status VARCHAR(24), active_attempt_id VARCHAR(64), workspace_id VARCHAR(64), project_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE mate_bidding_attempt (id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64), token VARCHAR(128), state VARCHAR(24), tool_receipts_json CLOB)");
        jdbc.execute("CREATE TABLE mate_bidding_skill_package (workspace_id VARCHAR(64), project_id VARCHAR(64), skill_id VARCHAR(64), version VARCHAR(128), digest VARCHAR(64), files_json CLOB)");
        jdbc.update("INSERT INTO mate_bidding_task VALUES ('task-1','RUNNING','attempt-1','workspace-1','project-1')");
        jdbc.update("INSERT INTO mate_bidding_attempt VALUES ('attempt-1','task-1','secret-token','RUNNING','[]')");
        jdbc.update("INSERT INTO mate_bidding_skill_package VALUES ('workspace-1','project-1','skill','v1','skill-digest','{\"SKILL.md\":\"# pinned\"}')");
        access = mock(BiddingAccess.class);
        dependencies = mock(BiddingDependencies.class);
        employeeBindings = mock(BiddingEmployeeBindings.class);
        when(employeeBindings.modelConfigId(any(), any())).thenReturn("7");
        runtime = new BiddingEmployeeRuntime(access, dependencies, employeeBindings, jdbc, mock(vip.mate.agent.AgentService.class),
                mock(vip.mate.workspace.conversation.ConversationService.class),
                mock(vip.mate.agent.repository.AgentMapper.class));
        claim = new BiddingTypes.Claim(new BiddingTypes.Scope("workspace-1", "actor-1", "project-1"),
                "task-1", "attempt-1", "secret-token", 1, 1, Instant.now().plusSeconds(60), "42",
                new BiddingTypes.SkillPin("skill", "v1", "skill-digest", Map.of("SKILL.md", "# pinned")),
                "7", "config-digest", List.of(), new ObjectMapper().createObjectNode());
    }

    @AfterEach void tearDown() { if (db != null) db.shutdown(); }

    @Test void runtimeBeanStartsWithoutOptionalAgentExecutionGraph() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(BiddingAccess.class, () -> mock(BiddingAccess.class));
            context.registerBean(BiddingDependencies.class, () -> mock(BiddingDependencies.class));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.register(BiddingEmployeeRuntime.class);
            context.refresh();

            assertNotNull(context.getBean(BiddingEmployeeRuntime.class));
            assertFalse(context.containsBean("biddingEmployeeBindings"));
            assertFalse(context.containsBean("agentService"));
        }
    }

    @Test void acceptsOnlyDatabaseBackedActiveAttemptAndTrustedOptions() {
        ToolContext context = context(claim);
        assertEquals(claim, runtime.claim(context));
        verify(access).requireActor(claim.scope(), "actor-1");
        verify(dependencies).validate(claim.scope(), List.of());
        verify(employeeBindings).validate(claim.scope(), "42", "config-digest");

        assertThrows(BiddingApiException.class, () -> runtime.claim(context(claim, "workspace-other")));
        jdbc.update("UPDATE mate_bidding_task SET active_attempt_id='attempt-2' WHERE id='task-1'");
        assertThrows(BiddingApiException.class, () -> runtime.claim(context));
    }

    @Test void rejectsClaimForgedThroughOrdinaryToolContextValues() {
        ToolContext forged = new ToolContext(Map.of("mateclaw.biddingClaim", claim));
        BiddingApiException error = assertThrows(BiddingApiException.class, () -> BiddingToolScope.claim(forged));
        assertEquals("CLAIM_REQUIRED", error.code());
    }

    @Test void skillLoadReadsPinnedPackageEvenWhenActiveVersionHasChanged() {
        String activeVersion = "# changed active skill";
        var skillRuntime = mock(SkillRuntimeService.class);
        var active = vip.mate.skill.runtime.model.ResolvedSkill.builder().id(77L).name("pinned").content(activeVersion).build();
        when(skillRuntime.findActiveSkill(any(), any())).thenReturn(active);
        var resolver = mock(AgentWorkspaceResolver.class);
        SkillFileTool fileTool = new SkillFileTool(skillRuntime, mock(SkillFileAccessPolicy.class),
                mock(SkillUsageService.class), resolver);
        SkillLoadTool loadTool = new SkillLoadTool(skillRuntime, fileTool, resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(fileTool, "projectExecutionRevalidator", runtime);
        org.springframework.test.util.ReflectionTestUtils.setField(loadTool, "projectExecutionRevalidator", runtime);
        var options = new vip.mate.agent.execution.ProjectExecutionOptions("attempt-1", "7", "config-digest",
                "pinned", "skill-digest", Map.of("SKILL.md", "# pinned original", "output.schema.json", "{}"),
                java.util.Set.of("load_skill", "readSkillFile"), new BiddingToolScope(claim),
                0, false, false, 12);
        ToolContext context = new ToolContext(Map.of(vip.mate.agent.execution.ProjectExecutionOptions.TOOL_CONTEXT_KEY,
                options));

        assertEquals("# pinned original", loadTool.loadSkill("pinned", null, context));
        assertEquals("{}", fileTool.readSkillFile("pinned", "output.schema.json", null, null, context));
        assertTrue(loadTool.loadSkill("pinned", "references/new-active.md", context).startsWith("Error:"));
        verify(skillRuntime, never()).findActiveSkill(any(), any());

        jdbc.update("UPDATE mate_bidding_task SET active_attempt_id='attempt-revoked' WHERE id='task-1'");
        assertThrows(BiddingApiException.class, () -> fileTool.readSkillFile(
                "pinned", "SKILL.md", null, null, context));
        assertThrows(BiddingApiException.class, () -> loadTool.loadSkill("pinned", null, context));
    }

    @Test void authorizedSourceBlockIsReadAndReceiptedAgainstTheActiveAttempt() throws Exception {
        BiddingTypes.Ref ref = new BiddingTypes.Ref("source", "source-1", 3, "source-digest");
        var input = new ObjectMapper().createObjectNode();
        input.putArray("blocks").addObject().put("id", "block-1").put("sourceId", "source-1").put("version", 3);
        BiddingTypes.Claim sourceClaim = new BiddingTypes.Claim(claim.scope(), claim.taskId(), claim.attemptId(),
                claim.token(), claim.attemptNo(), claim.cycleAttempt(), claim.deadlineAt(), claim.agentId(), claim.skill(),
                claim.modelConfigId(), claim.configDigest(), List.of(ref), input);
        BiddingRepository repository = mock(BiddingRepository.class);
        when(repository.source("workspace-1", "project-1", "source-1", 3)).thenReturn(
                new BiddingRepository.SourceRow("row-1", "source-1", 3, "pdf", "source-digest", null,
                        "bid.pdf", "[{\"id\":\"block-1\",\"text\":\"scope clause\",\"quality\":\"READABLE\"}]",
                        "READY", "DONE", "[]", null));
        BiddingReadTool tool = new BiddingReadTool(runtime, repository, dependencies, jdbc, new ObjectMapper());
        var options = new vip.mate.agent.execution.ProjectExecutionOptions("attempt-1", "7", "config-digest",
                "pinned", "skill-digest", sourceClaim.skill().files(), java.util.Set.of("bidding_read_source"),
                new BiddingToolScope(sourceClaim), 0, false, false, 12);
        ToolContext context = new ToolContext(Map.of(vip.mate.agent.execution.ProjectExecutionOptions.TOOL_CONTEXT_KEY,
                options));

        assertEquals("scope clause", tool.readSource("source-1", 3, "block-1", context));
        String receipts = jdbc.queryForObject("SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class);
        assertTrue(receipts.contains("block-1"));
        assertTrue(receipts.contains("source-digest"));
        assertThrows(BiddingApiException.class, () -> tool.readSource("source-2", 3, "block-1", context));
        BiddingApiException unassigned = assertThrows(BiddingApiException.class,
                () -> tool.readSource("source-1", 3, "block-2", context));
        assertEquals("BLOCK_NOT_ASSIGNED", unassigned.code());
        assertEquals(1, new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class)).size());

        jdbc.update("UPDATE mate_bidding_attempt SET tool_receipts_json='[]' WHERE id='attempt-1'");
        org.mockito.Mockito.clearInvocations(access);
        java.util.concurrent.atomic.AtomicInteger actorChecks = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (actorChecks.incrementAndGet() == 3)
                throw BiddingAccess.error(403, "ACTOR_REVOKED", "Actor membership was revoked");
            return null;
        }).when(access).requireActor(any(), any());
        assertThrows(BiddingApiException.class, () -> tool.readSource("source-1", 3, "block-1", context));
        assertEquals("[]", jdbc.queryForObject(
                "SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class));
    }

    @Test void batchReadsOnlyAssignedBlocksAndWritesOneReceiptPerBlock() throws Exception {
        BiddingTypes.Ref ref = new BiddingTypes.Ref("source", "source-1", 3, "source-digest");
        var input = new ObjectMapper().createObjectNode();
        var assigned = input.putArray("blocks");
        assigned.addObject().put("id", "block-1").put("sourceId", "source-1").put("version", 3);
        assigned.addObject().put("id", "block-2").put("sourceId", "source-1").put("version", 3);
        BiddingTypes.Claim sourceClaim = new BiddingTypes.Claim(claim.scope(), claim.taskId(), claim.attemptId(),
                claim.token(), claim.attemptNo(), claim.cycleAttempt(), claim.deadlineAt(), claim.agentId(), claim.skill(),
                claim.modelConfigId(), claim.configDigest(), List.of(ref), input);
        BiddingRepository repository = mock(BiddingRepository.class);
        when(repository.source("workspace-1", "project-1", "source-1", 3)).thenReturn(
                new BiddingRepository.SourceRow("row-1", "source-1", 3, "pdf", "source-digest", null,
                        "bid.pdf", "[{\"id\":\"block-1\",\"text\":\"first clause\",\"quality\":\"READABLE\"},"
                                + "{\"id\":\"block-2\",\"text\":\"second clause\",\"quality\":\"READABLE\"}]",
                        "READY", "DONE", "[]", null));
        BiddingReadTool tool = new BiddingReadTool(runtime, repository, dependencies, jdbc, new ObjectMapper());
        var options = new vip.mate.agent.execution.ProjectExecutionOptions("attempt-1", "7", "config-digest",
                "pinned", "skill-digest", sourceClaim.skill().files(), java.util.Set.of("bidding_read_sources"),
                new BiddingToolScope(sourceClaim), 0, false, false, 12);
        ToolContext context = new ToolContext(Map.of(vip.mate.agent.execution.ProjectExecutionOptions.TOOL_CONTEXT_KEY,
                options));

        String result = tool.readSources("[{\"sourceId\":\"source-1\",\"version\":3,\"blockId\":\"block-1\"},"
                + "{\"sourceId\":\"source-1\",\"version\":3,\"blockId\":\"block-2\"}]", context);
        var parsed = new ObjectMapper().readTree(result);
        assertEquals(2, parsed.path("count").asInt());
        assertEquals("first clause", parsed.path("blocks").get(0).path("text").asText());
        assertEquals("second clause", parsed.path("blocks").get(1).path("text").asText());
        var receipts = new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class));
        assertEquals(2, receipts.size());
        assertTrue(receipts.findValuesAsText("tool").stream().allMatch("bidding_read_source"::equals));
        assertEquals(List.of("block-1", "block-2"), receipts.findValuesAsText("blockId"));

        BiddingApiException error = assertThrows(BiddingApiException.class, () -> tool.readSources(
                "[{\"sourceId\":\"source-1\",\"version\":3,\"blockId\":\"not-assigned\"}]", context));
        assertEquals("BLOCK_NOT_ASSIGNED", error.code());
        assertEquals(2, new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class)).size());
        verify(repository, times(1)).source("workspace-1", "project-1", "source-1", 3);
    }

    @Test void batchReceiptFailureRollsBackEarlierReceipts() throws Exception {
        BiddingTypes.Ref ref = new BiddingTypes.Ref("source", "source-1", 3, "source-digest");
        var input = new ObjectMapper().createObjectNode();
        var assigned = input.putArray("blocks");
        assigned.addObject().put("id", "block-1").put("sourceId", "source-1").put("version", 3);
        assigned.addObject().put("id", "block-2").put("sourceId", "source-1").put("version", 3);
        BiddingTypes.Claim sourceClaim = new BiddingTypes.Claim(claim.scope(), claim.taskId(), claim.attemptId(),
                claim.token(), claim.attemptNo(), claim.cycleAttempt(), claim.deadlineAt(), claim.agentId(), claim.skill(),
                claim.modelConfigId(), claim.configDigest(), List.of(ref), input);
        BiddingRepository repository = mock(BiddingRepository.class);
        when(repository.source("workspace-1", "project-1", "source-1", 3)).thenReturn(
                new BiddingRepository.SourceRow("row-1", "source-1", 3, "pdf", "source-digest", null,
                        "bid.pdf", "[{\"id\":\"block-1\",\"text\":\"first clause\",\"quality\":\"READABLE\"},"
                                + "{\"id\":\"block-2\",\"text\":\"second clause\",\"quality\":\"READABLE\"}]",
                        "READY", "DONE", "[]", null));
        BiddingReadTool tool = spy(new BiddingReadTool(runtime, repository, dependencies, jdbc, new ObjectMapper()));
        var receiptCalls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (receiptCalls.incrementAndGet() == 2)
                throw BiddingAccess.error(409, "ATTEMPT_STALE", "Task attempt is no longer active");
            return invocation.callRealMethod();
        }).when(tool).appendReceipt(any(BiddingTypes.Claim.class),
                org.mockito.ArgumentMatchers.<String, Object>anyMap());
        var options = new vip.mate.agent.execution.ProjectExecutionOptions("attempt-1", "7", "config-digest",
                "pinned", "skill-digest", sourceClaim.skill().files(), java.util.Set.of("bidding_read_sources"),
                new BiddingToolScope(sourceClaim), 0, false, false, 12);
        ToolContext context = new ToolContext(Map.of(vip.mate.agent.execution.ProjectExecutionOptions.TOOL_CONTEXT_KEY,
                options));

        BiddingApiException error = assertThrows(BiddingApiException.class, () ->
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db))
                        .executeWithoutResult(status -> tool.readSources(
                                "[{\"sourceId\":\"source-1\",\"version\":3,\"blockId\":\"block-1\"},"
                                        + "{\"sourceId\":\"source-1\",\"version\":3,\"blockId\":\"block-2\"}]", context)));
        assertEquals("ATTEMPT_STALE", error.code());
        assertEquals("[]", jdbc.queryForObject(
                "SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id='attempt-1'", String.class));
    }

    private ToolContext context(BiddingTypes.Claim value) { return context(value, value.scope().workspaceId()); }
    private ToolContext context(BiddingTypes.Claim value, String workspaceId) {
        BiddingTypes.Claim scoped = new BiddingTypes.Claim(new BiddingTypes.Scope(workspaceId,
                value.scope().actorId(), value.scope().projectId()), value.taskId(), value.attemptId(), value.token(),
                value.attemptNo(), value.cycleAttempt(), value.deadlineAt(), value.agentId(), value.skill(),
                value.modelConfigId(), value.configDigest(), value.inputRefs(), value.input());
        var policy = new BiddingToolScope(scoped);
        var options = new vip.mate.agent.execution.ProjectExecutionOptions(scoped.attemptId(), scoped.modelConfigId(),
                scoped.configDigest(), "pinned", scoped.skill().digest(), scoped.skill().files(),
                java.util.Set.of("bidding_read_source"), policy, 0, false, false, 12);
        return new ToolContext(Map.of(vip.mate.agent.execution.ProjectExecutionOptions.TOOL_CONTEXT_KEY, options));
    }
}
