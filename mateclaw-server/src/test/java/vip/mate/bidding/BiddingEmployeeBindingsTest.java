package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.runtime.SkillRuntimeService;

@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bidding_employee_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class BiddingEmployeeBindingsTest {
    private static final AtomicLong IDS = new AtomicLong(9_730_000L);
    @TempDir Path temp;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AgentService agents;
    @Autowired AgentBindingService agentBindings;
    @Autowired SkillRuntimeService skills;
    @Autowired BiddingSkillPackages packages;
    @Autowired BiddingEmployeeBindings employeeBindings;
    @Autowired BiddingProjectService projects;
    @MockBean BiddingAccess access;

    private long skillId;
    private Path skillDirectory;
    private BiddingTypes.Scope scope;
    private AgentEntity analyst;
    private AgentEntity writer;
    private AgentEntity reviewer;

    @BeforeEach void setUp() throws Exception {
        doNothing().when(access).requireOwner(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        doNothing().when(access).requireApprover(org.mockito.ArgumentMatchers.any());
        long modelId = IDS.incrementAndGet();
        String modelName = "bidding-test-model-" + modelId;
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?, 'dashscope',?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                modelId, "bidding model", modelName);
        skillId = IDS.incrementAndGet();
        skillDirectory = Files.createDirectory(temp.resolve("skill-" + skillId));
        Files.writeString(skillDirectory.resolve("SKILL.md"), "---\nname: bidding-skill-" + skillId + "\ndescription: test\n---\n# v1");
        Files.writeString(skillDirectory.resolve("input.schema.json"), "{\"version\":1}");
        Files.createDirectories(skillDirectory.resolve("references"));
        Files.writeString(skillDirectory.resolve("references/rules.md"), "rule-v1");
        jdbc.update("INSERT INTO mate_skill(id,name,skill_type,version,skill_content,config_json,enabled,builtin,workspace_id,create_time,update_time,deleted) VALUES(?,?,'custom','1.0.0',?,?,TRUE,FALSE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                skillId, "bidding-skill-" + skillId, Files.readString(skillDirectory.resolve("SKILL.md")), json.writeValueAsString(Map.of("skillDir", skillDirectory.toString())));
        skills.refreshActiveSkills();
        assertNotNull(skills.findActiveSkillById(skillId, 1L), "seeded skill should be active in the real runtime");

        analyst = createAgent("analyst", modelName, 1L);
        writer = createAgent("writer", modelName, 1L);
        reviewer = createAgent("reviewer", modelName, 1L);
        for (AgentEntity agent : List.of(analyst, writer, reviewer)) agentBindings.setSkillBindings(agent.getId(), List.of(skillId));
        scope = new BiddingTypes.Scope("1", "44", "unused-until-project-created");
    }

    @Test void assignmentPinsAuthorizedSkillsAndProjectEditsPreserveIt() {
        BiddingTypes.Scope projectScope = new BiddingTypes.Scope(scope.workspaceId(), scope.actorId(), null);
        var created = projects.create(projectScope, new BiddingTypes.NewProject("create-" + UUID.randomUUID(), "P", "lot", null));
        scope = new BiddingTypes.Scope(scope.workspaceId(), scope.actorId(), created.path("id").asText());
        var payload = json.createObjectNode();
        payload.put("analystAgentId", analyst.getId().toString());
        payload.put("writerAgentId", writer.getId().toString());
        payload.put("reviewerAgentId", reviewer.getId().toString());
        var assignCommand = new BiddingTypes.Command("assign-" + UUID.randomUUID(),
                json.convertValue(created.path("ref"), BiddingTypes.Ref.class), "ASSIGN_EMPLOYEES", payload);
        var assigned = employeeBindings.assign(scope, assignCommand);
        assertEquals(assigned.toString(), employeeBindings.assign(scope, assignCommand).toString(), "an identical operation must replay its persisted result");
        var project = assigned.path("result");
        assertEquals(analyst.getId().toString(), employeeBindings.resolve(scope, "analyst"));
        assertEquals(writer.getId().toString(), employeeBindings.resolve(scope, "writer"));
        assertEquals(1, project.path("bindings").path("writer").path("skillPins").size());
        assertEquals(employeeBindings.configDigest(scope, writer.getId().toString()),
                project.path("bindings").path("writer").path("configDigest").asText(),
                "the stored fingerprint must include the immutable skill pin created during assignment");
        String oldDigest = project.path("bindings").path("writer").path("skillPins").get(0).path("digest").asText();
        assertEquals("{\"version\":1}", packages.read(scope, oldDigest, "input.schema.json"));
        employeeBindings.validate(scope, writer.getId().toString(), project.path("bindings").path("writer").path("configDigest").asText());

        var updated = projects.execute(scope, new BiddingTypes.Command("edit-" + UUID.randomUUID(),
                json.convertValue(project.path("ref"), BiddingTypes.Ref.class), "UPDATE_PROJECT", json.createObjectNode().put("name", "updated")));
        assertEquals(writer.getId().toString(), updated.path("result").path("bindings").path("writer").path("agentId").asText());
        assertEquals(oldDigest, updated.path("result").path("bindings").path("writer").path("skillPins").get(0).path("digest").asText());
        assertEquals(4, projects.execute(scope, new BiddingTypes.Command("edit-again-" + UUID.randomUUID(),
                json.convertValue(updated.path("result").path("ref"), BiddingTypes.Ref.class), "UPDATE_PROJECT", json.createObjectNode().put("lotName", "next")))
                .path("result").path("version").asInt());
    }

    @Test void pinKeepsOldSchemaAfterActiveFilesChangeAndNewPinGetsNewDigest() throws Exception {
        BiddingTypes.Scope packageScope = new BiddingTypes.Scope("1", "44", "immutable-package-test");
        BiddingTypes.SkillPin first = packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId));
        Files.writeString(skillDirectory.resolve("input.schema.json"), "{\"version\":2}");
        Files.writeString(skillDirectory.resolve("SKILL.md"), "---\nname: bidding-skill-" + skillId + "\ndescription: test\n---\n# v2");
        skills.refreshActiveSkills();
        BiddingTypes.SkillPin second = packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId));
        assertNotEquals(first.digest(), second.digest());
        assertEquals("{\"version\":1}", packages.read(packageScope, first.digest(), "input.schema.json"));
        assertEquals("{\"version\":2}", packages.read(packageScope, second.digest(), "input.schema.json"));
    }

    @Test void realAgentBindingsRejectCrossWorkspaceDisabledAndUnassignedSkills() {
        BiddingTypes.Scope packageScope = new BiddingTypes.Scope("1", "44", "authorization-test");
        assertThrows(BiddingApiException.class, () -> packages.pin(new BiddingTypes.Scope("2", "44", "authorization-test"), analyst.getId().toString(), Long.toString(skillId)));
        jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=?", analyst.getId());
        assertThrows(BiddingApiException.class, () -> packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId)));
        jdbc.update("UPDATE mate_agent SET enabled=TRUE,skills_disabled=TRUE WHERE id=?", analyst.getId());
        assertEquals(Set.of(), agentBindings.getBoundSkillIds(analyst.getId()));
        assertThrows(BiddingApiException.class, () -> packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId)));
    }

    @Test void effectiveToolConfigurationSeparatesInheritedFromExplicitlyRestrictedAndHidesCredentials() {
        AgentEntity unbound = createAgent("unbound", analyst.getModelName(), 1L);
        BiddingTypes.Scope configScope = new BiddingTypes.Scope("1", "44", "config-test");
        assertNull(agentBindings.getEffectiveToolNames(unbound.getId()));
        String inherited = employeeBindings.configDigest(configScope, unbound.getId().toString());
        assertNull(agentBindings.getBoundToolNames(unbound.getId()));
        jdbc.update("UPDATE mate_agent SET tools_disabled=TRUE WHERE id=?", unbound.getId());
        assertEquals(Set.of(), agentBindings.getBoundToolNames(unbound.getId()));
        assertNotNull(agentBindings.getEffectiveToolNames(unbound.getId()));
        assertNotEquals(inherited, employeeBindings.configDigest(configScope, unbound.getId().toString()));
        var employeeRows = employeeBindings.employees(configScope);
        assertFalse(employeeRows.toString().contains("test-key"));
    }

    @Test void missingOrChangedModelConfigurationIsReportedWithoutReadingCredentials() {
        BiddingTypes.Scope configScope = new BiddingTypes.Scope("1", "44", "model-test");
        String original = employeeBindings.configDigest(configScope, writer.getId().toString());
        assertNotNull(employeeBindings.modelConfigId(configScope, writer.getId().toString()));
        jdbc.update("UPDATE mate_model_config SET update_time=DATEADD('SECOND', 30, update_time) WHERE model_name=?", writer.getModelName());
        assertThrows(BiddingApiException.class, () -> employeeBindings.validate(configScope, writer.getId().toString(), original));
        jdbc.update("UPDATE mate_agent SET model_name='unconfigured-bidding-model' WHERE id=?", writer.getId());
        BiddingApiException missing = assertThrows(BiddingApiException.class,
                () -> employeeBindings.validate(configScope, writer.getId().toString(), original));
        assertEquals("MODEL_CONFIG_MISSING", missing.code());
    }

    private AgentEntity createAgent(String role, String modelName, long workspace) {
        AgentEntity agent = new AgentEntity();
        agent.setName("bidding-" + role + "-" + IDS.incrementAndGet());
        agent.setDescription("Task 3 integration test"); agent.setAgentType("react"); agent.setSystemPrompt("test");
        agent.setMaxIterations(10); agent.setWorkspaceId(workspace); agent.setModelName(modelName);
        return agents.createAgent(agent);
    }
}
