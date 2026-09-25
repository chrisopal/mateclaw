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

    @BeforeEach void setUp() {
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

    @AfterEach void tearDown() { db.shutdown(); }

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
    }

    @Test void authorizedSourceBlockIsReadAndReceiptedAgainstTheActiveAttempt() {
        BiddingTypes.Ref ref = new BiddingTypes.Ref("source", "source-1", 3, "source-digest");
        BiddingTypes.Claim sourceClaim = new BiddingTypes.Claim(claim.scope(), claim.taskId(), claim.attemptId(),
                claim.token(), claim.attemptNo(), claim.cycleAttempt(), claim.deadlineAt(), claim.agentId(), claim.skill(),
                claim.modelConfigId(), claim.configDigest(), List.of(ref), claim.input());
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
