package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;

import com.fasterxml.jackson.databind.JsonNode;
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
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.llm.routing.ProviderRouter;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.common.result.R;

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
    @Autowired BiddingController controller;
    @Autowired ModelConfigService modelConfigs;
    @Autowired ModelProviderService modelProviders;
    @Autowired ProviderRouter providerRouter;
    @MockBean BiddingAccess access;

    private static final Map<String, List<String>> ROLE_SKILLS = Map.of(
            "analyst", List.of("bidding-tender-profile", "bidding-elimination-analysis", "bidding-requirement-analysis", "bidding-scoring-analysis"),
            "writer", List.of("bidding-outline-planning", "bidding-technical-writing", "bidding-document-export"),
            "reviewer", List.of("bidding-technical-review"));
    private final Map<String, Long> skillIds = new java.util.LinkedHashMap<>();
    private final Map<Long, Path> skillDirectories = new java.util.HashMap<>();
    private BiddingTypes.Scope scope;
    private AgentEntity analyst;
    private AgentEntity writer;
    private AgentEntity reviewer;

    @BeforeEach void setUp() throws Exception {
        doNothing().when(access).requireOwner(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        doNothing().when(access).requireApprover(org.mockito.ArgumentMatchers.any());
        List<String> seededNames = new java.util.ArrayList<>(ROLE_SKILLS.values().stream().flatMap(List::stream).toList());
        seededNames.add("unrelated-global-skill");
        String seededMarkers = String.join(",", java.util.Collections.nCopies(seededNames.size(), "?"));
        jdbc.update("DELETE FROM mate_agent_skill WHERE skill_id IN (SELECT id FROM mate_skill WHERE name IN (" + seededMarkers + "))", seededNames.toArray());
        jdbc.update("DELETE FROM mate_skill WHERE name IN (" + seededMarkers + ")", seededNames.toArray());
        skills.refreshActiveSkills();
        // A dummy provider credential makes the seeded test model genuinely configured to the platform resolver.
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key', enabled=TRUE WHERE provider_id='dashscope'");
        long modelId = IDS.incrementAndGet();
        String modelName = "bidding-test-model-" + modelId;
        String configuredProvider = modelConfigs.getDefaultModel().getProvider();
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?, ?,?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                modelId, "bidding model", configuredProvider, modelName);
        skillIds.clear(); skillDirectories.clear();
        for (String skillName : ROLE_SKILLS.values().stream().flatMap(List::stream).toList()) seedSkill(skillName, "# " + skillName);
        skills.refreshActiveSkills();
        for (String skillName : ROLE_SKILLS.values().stream().flatMap(List::stream).toList()) {
            var active = skills.findActiveSkillById(skillIds.get(skillName), 1L);
            assertNotNull(active, "seeded skill should be active in the real runtime: " + skillName);
            assertEquals(skillName, active.getName(), "skill runtime name should match its persistent internal id; active=" + active.getName());
            assertNotNull(skills.findActiveSkill(skillName, 1L), "workspace name lookup; visible=" + skills.getActiveSkills(1L).stream().map(s -> s.getId() + ":" + s.getName()).filter(s -> s.contains(skillIds.get(skillName).toString())).toList());
        }

        analyst = createAgent("analyst", modelName, 1L);
        writer = createAgent("writer", modelName, 1L);
        reviewer = createAgent("reviewer", modelName, 1L);
        agentBindings.setSkillBindings(analyst.getId(), roleSkillIds("analyst"));
        agentBindings.setSkillBindings(writer.getId(), roleSkillIds("writer"));
        agentBindings.setSkillBindings(reviewer.getId(), roleSkillIds("reviewer"));
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
        assertEquals(ROLE_SKILLS.get("writer").size(), project.path("bindings").path("writer").path("skillPins").size());
        assertEquals(ROLE_SKILLS.get("analyst").size(), project.path("bindings").path("analyst").path("skillPins").size());
        assertEquals(ROLE_SKILLS.get("reviewer").size(), project.path("bindings").path("reviewer").path("skillPins").size());
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
        long skillId = skillIds.get("bidding-tender-profile");
        Path skillDirectory = skillDirectories.get(skillId);
        BiddingTypes.SkillPin first = packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId));
        Files.writeString(skillDirectory.resolve("input.schema.json"), "{\"version\":2}");
        Files.writeString(skillDirectory.resolve("SKILL.md"), "---\nname: bidding-tender-profile\ndescription: test\n---\n# v2");
        skills.refreshActiveSkills();
        BiddingTypes.SkillPin second = packages.pin(packageScope, analyst.getId().toString(), Long.toString(skillId));
        assertNotEquals(first.digest(), second.digest());
        assertEquals("{\"version\":1}", packages.read(packageScope, first.digest(), "input.schema.json"));
        assertEquals("{\"version\":2}", packages.read(packageScope, second.digest(), "input.schema.json"));
    }

    @Test void realAgentBindingsRejectCrossWorkspaceDisabledAndUnassignedSkills() {
        BiddingTypes.Scope packageScope = new BiddingTypes.Scope("1", "44", "authorization-test");
        long skillId = skillIds.get("bidding-tender-profile");
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

    @Test void changedModelConfigurationAndUnconfiguredOverrideUseSafePlatformFallback() {
        BiddingTypes.Scope configScope = new BiddingTypes.Scope("1", "44", "model-test");
        String original = employeeBindings.configDigest(configScope, writer.getId().toString());
        assertNotNull(employeeBindings.modelConfigId(configScope, writer.getId().toString()));
        jdbc.update("UPDATE mate_model_config SET update_time=DATEADD('SECOND', 30, update_time) WHERE id=?",
                Long.parseLong(employeeBindings.modelConfigId(configScope, writer.getId().toString())));
        assertThrows(BiddingApiException.class, () -> employeeBindings.validate(configScope, writer.getId().toString(), original));
        jdbc.update("UPDATE mate_agent SET model_name='unconfigured-bidding-model' WHERE id=?", writer.getId());
        assertEquals(Long.toString(modelConfigs.getDefaultModel().getId()), employeeBindings.modelConfigId(configScope, writer.getId().toString()));
        String fallbackDigest = employeeBindings.configDigest(configScope, writer.getId().toString());
        assertNotEquals(original, fallbackDigest);
        assertDoesNotThrow(() -> employeeBindings.validate(configScope, writer.getId().toString(), fallbackDigest));
    }

    @Test void assignmentsPinOnlyRoleRequiredSkillsAndRejectMissingRequiredSkill() throws Exception {
        long unrelated = seedSkill("unrelated-global-skill", "x".repeat(2 * 1024 * 1024 + 16));
        Path dir = skillDirectories.get(unrelated);
        for (AgentEntity agent : List.of(analyst, writer, reviewer)) {
            var grants = new java.util.ArrayList<>(agentBindings.getBoundSkillIds(agent.getId()));
            grants.add(unrelated);
            agentBindings.setSkillBindings(agent.getId(), grants);
        }
        skills.refreshActiveSkills();
        var created = newProject();
        var projectScope = scopeFor(created);
        var assigned = employeeBindings.assign(projectScope, assignCommand(created));
        assertEquals(ROLE_SKILLS.get("analyst").size(), assigned.path("result").path("bindings").path("analyst").path("skillPins").size());
        assertEquals(ROLE_SKILLS.get("writer").size(), assigned.path("result").path("bindings").path("writer").path("skillPins").size());
        assertTrue(Files.size(dir.resolve("SKILL.md")) > 2 * 1024 * 1024);

        var missingName = ROLE_SKILLS.get("writer").getFirst();
        jdbc.update("UPDATE mate_skill SET enabled=FALSE WHERE id=?", skillIds.get(missingName));
        skills.refreshActiveSkills();
        var second = newProject();
        BiddingApiException failure = assertThrows(BiddingApiException.class, () -> employeeBindings.assign(scopeFor(second), assignCommand(second)));
        assertEquals("EMPLOYEE_SKILL_UNAVAILABLE", failure.code());
    }

    @Test void effectiveModelUsesPlatformSelectionAndConfiguredProviderFallback() {
        BiddingTypes.Scope configScope = new BiddingTypes.Scope("1", "44", "model-selection-test");
        String configuredDigest = employeeBindings.configDigest(configScope, analyst.getId().toString());
        AgentEntity persisted = agents.getAgent(analyst.getId());
        ModelConfigEntity configured = modelConfigs.resolveModel(persisted.getModelName());
        assertEquals(Long.toString(configured.getId()), employeeBindings.modelConfigId(configScope, analyst.getId().toString()));

        String unconfiguredProvider = "task3-unconfigured-provider";
        jdbc.update("INSERT INTO mate_model_provider(provider_id,name,api_key_prefix,chat_model,api_key,base_url,generate_kwargs,is_custom,is_local,support_model_discovery,support_connection_check,freeze_url,require_api_key,auth_type,create_time,update_time) VALUES(?,?,?,?,?,?,?,FALSE,FALSE,FALSE,FALSE,FALSE,TRUE,'api_key',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                unconfiguredProvider, "Task3 unconfigured provider", null, "task3-disabled-model", "", "https://example.invalid", null);
        assertFalse(modelProviders.isProviderConfigured(unconfiguredProvider));
        long disabledProviderModelId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?,?,?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                disabledProviderModelId, "disabled provider model", unconfiguredProvider, "task3-disabled-model");
        jdbc.update("UPDATE mate_agent SET model_name='task3-disabled-model' WHERE id=?", analyst.getId());
        ModelConfigEntity expectedFallback = modelConfigs.listByType("chat").stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .filter(m -> { try { return modelProviders.isProviderConfigured(m.getProvider()); } catch (RuntimeException e) { return false; } })
                .findFirst().orElseThrow();
        assertEquals(Long.toString(expectedFallback.getId()), employeeBindings.modelConfigId(configScope, analyst.getId().toString()));
        assertNotEquals(configuredDigest, employeeBindings.configDigest(configScope, analyst.getId().toString()));

        jdbc.update("UPDATE mate_agent SET model_name='absent-task3-model' WHERE id=?", analyst.getId());
        assertEquals(Long.toString(modelConfigs.getDefaultModel().getId()), employeeBindings.modelConfigId(configScope, analyst.getId().toString()));
        String absent = employeeBindings.configDigest(configScope, analyst.getId().toString());
        long selectedId = Long.parseLong(employeeBindings.modelConfigId(configScope, analyst.getId().toString()));
        jdbc.update("UPDATE mate_model_config SET update_time=DATEADD('SECOND', 40, update_time) WHERE id=?", selectedId);
        assertNotEquals(absent, employeeBindings.configDigest(configScope, analyst.getId().toString()));
    }

    @Test void employeeEndpointReturnsAuthorizedSkillsWithoutCredentials() {
        org.mockito.Mockito.when(access.require("1", "viewer")).thenReturn("44");
        var response = controller.employees("1");
        var payload = json.valueToTree(response);
        var rows = payload.path("data");
        var analystRow = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> analyst.getId().toString().equals(row.path("id").asText())).findFirst().orElseThrow();
        assertTrue(analystRow.path("available").asBoolean());
        assertEquals(ROLE_SKILLS.get("analyst").size(), analystRow.path("skills").size());
        Set<String> names = new java.util.HashSet<>();
        analystRow.path("skills").forEach(s -> { assertTrue(s.hasNonNull("id")); names.add(s.path("name").asText()); });
        assertEquals(Set.copyOf(ROLE_SKILLS.get("analyst")), names);
        assertFalse(payload.toString().contains("test-key"));
        org.mockito.Mockito.verify(access).require("1", "viewer");
    }

    @Test void archivedProjectsRejectNewBindingsButStillReplayExactPriorOperation() throws Exception {
        var created = newProject();
        BiddingTypes.Scope projectScope = new BiddingTypes.Scope("1", "44", created.path("id").asText());
        var command = assignCommand(created);
        var partialPayload = json.createObjectNode().put("analystAgentId", analyst.getId().toString()).putNull("writerAgentId").putNull("reviewerAgentId");
        var partialCommand = new BiddingTypes.Command(command.operationId(), command.expected(), command.action(), partialPayload);
        var assigned = employeeBindings.assign(projectScope, partialCommand);
        var archived = projects.execute(projectScope, new BiddingTypes.Command("archive-" + UUID.randomUUID(),
                json.convertValue(assigned.path("ref"), BiddingTypes.Ref.class), "ARCHIVE_PROJECT", json.createObjectNode()));
        assertEquals("ARCHIVED", archived.path("result").path("stage").asText());
        assertEquals(assigned.toString(), employeeBindings.assign(projectScope, partialCommand).toString());
        var newOperation = new BiddingTypes.Command("new-archived-" + UUID.randomUUID(),
                json.convertValue(archived.path("ref"), BiddingTypes.Ref.class), "ASSIGN_EMPLOYEES", partialPayload);
        BiddingApiException rejected = assertThrows(BiddingApiException.class, () -> employeeBindings.assign(projectScope, newOperation));
        assertEquals("PROJECT_ARCHIVED", rejected.code());
    }

    @Test void analystOnlyAssignmentWorksWhileFuturePhaseRolesRemainExplicitlyUnboundAndUnready() throws Exception {
        for (String role : List.of("writer", "reviewer")) for (String required : ROLE_SKILLS.get(role)) {
            jdbc.update("UPDATE mate_skill SET enabled=FALSE WHERE id=?", skillIds.get(required));
        }
        skills.refreshActiveSkills();
        JsonNode created = newProject();
        BiddingTypes.Scope projectScope = scopeFor(created);
        var payload = json.createObjectNode().put("analystAgentId", analyst.getId().toString()).putNull("writerAgentId").putNull("reviewerAgentId");
        var command = new BiddingTypes.Command("analyst-only-" + UUID.randomUUID(), json.convertValue(created.path("ref"), BiddingTypes.Ref.class), "ASSIGN_EMPLOYEES", payload);
        var assigned = employeeBindings.assign(projectScope, command).path("result");
        assertEquals(analyst.getId().toString(), assigned.path("bindings").path("analyst").path("agentId").asText());
        assertTrue(assigned.path("bindings").path("writer").path("agentId").isNull());
        assertTrue(assigned.path("bindings").path("reviewer").path("agentId").isNull());
        assertEquals(ROLE_SKILLS.get("analyst").size(), assigned.path("bindings").path("analyst").path("skillPins").size());
        assertEquals(0, assigned.path("bindings").path("writer").path("skillPins").size());

        var rows = json.valueToTree(controller.employees("1")).path("data");
        var analystRow = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> analyst.getId().toString().equals(row.path("id").asText())).findFirst().orElseThrow();
        assertTrue(analystRow.path("roles").path("analyst").path("available").asBoolean());
        assertFalse(analystRow.path("roles").path("writer").path("available").asBoolean());
        assertFalse(analystRow.path("roles").path("reviewer").path("available").asBoolean());
        assertTrue(analystRow.path("roles").path("writer").path("missingSkills").size() > 0);
    }

    private long seedSkill(String name, String content) throws Exception {
        long id = IDS.incrementAndGet();
        Path directory = Files.createDirectory(temp.resolve("skill-" + id));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: test\n---\n" + content);
        Files.writeString(directory.resolve("input.schema.json"), "{\"version\":1}");
        skillIds.put(name, id); skillDirectories.put(id, directory);
        jdbc.update("INSERT INTO mate_skill(id,name,skill_type,version,skill_content,config_json,enabled,builtin,workspace_id,create_time,update_time,deleted) VALUES(?,?,'custom','1.0.0',?,?,TRUE,FALSE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                id, name, Files.readString(directory.resolve("SKILL.md")), json.writeValueAsString(Map.of("skillDir", directory.toString())));
        return id;
    }

    private List<Long> roleSkillIds(String role) { return ROLE_SKILLS.get(role).stream().map(skillIds::get).toList(); }
    private JsonNode newProject() {
        return projects.create(new BiddingTypes.Scope(scope.workspaceId(), scope.actorId(), null),
                new BiddingTypes.NewProject("create-" + UUID.randomUUID(), "P", "lot", null));
    }
    private BiddingTypes.Scope scopeFor(JsonNode project) { return new BiddingTypes.Scope("1", "44", project.path("id").asText()); }
    private BiddingTypes.Command assignCommand(JsonNode created) {
        var payload = json.createObjectNode();
        payload.put("analystAgentId", analyst.getId().toString());
        payload.put("writerAgentId", writer.getId().toString());
        payload.put("reviewerAgentId", reviewer.getId().toString());
        return new BiddingTypes.Command("assign-" + UUID.randomUUID(), json.convertValue(created.path("ref"), BiddingTypes.Ref.class), "ASSIGN_EMPLOYEES", payload);
    }

    private AgentEntity createAgent(String role, String modelName, long workspace) {
        AgentEntity agent = new AgentEntity();
        agent.setName("bidding-" + role + "-" + IDS.incrementAndGet());
        agent.setDescription("Task 3 integration test"); agent.setAgentType("react"); agent.setSystemPrompt("test");
        agent.setMaxIterations(10); agent.setWorkspaceId(workspace); agent.setModelName(modelName);
        return agents.createAgent(agent);
    }
}
