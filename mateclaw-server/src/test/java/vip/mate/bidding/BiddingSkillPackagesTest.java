package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

class BiddingSkillPackagesTest {
    @TempDir Path temp;
    private AgentService agents;
    private AgentBindingService bindings;
    private SkillRuntimeService skills;
    private JdbcTemplate jdbc;
    private BiddingSkillPackages packages;
    private final BiddingTypes.Scope scope = new BiddingTypes.Scope("17", "23", "project-a");

    @BeforeEach void setUp() {
        agents = mock(AgentService.class);
        bindings = mock(AgentBindingService.class);
        skills = mock(SkillRuntimeService.class);
        var db = new EmbeddedDatabaseBuilder().setName("bidding-skill-" + java.util.UUID.randomUUID())
                .setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE mate_bidding_skill_package (id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64), project_id VARCHAR(64), skill_id VARCHAR(128), version VARCHAR(128), digest VARCHAR(64), files_json CLOB, created_at TIMESTAMP)");
        packages = new BiddingSkillPackages(agents, bindings, skills, jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test void digestIncludesReferencedSchemaAndIgnoresMapInsertionOrder() {
        var a = new LinkedHashMap<String, String>(); a.put("SKILL.md", "read schema"); a.put("output.schema.json", "v1");
        var b = new LinkedHashMap<String, String>(); b.put("output.schema.json", "v1"); b.put("SKILL.md", "read schema");
        assertEquals(BiddingSkillPackages.digest(a), BiddingSkillPackages.digest(b));
        assertNotEquals(BiddingSkillPackages.digest(a), BiddingSkillPackages.digest(Map.of("SKILL.md", "read schema", "output.schema.json", "v2")));
    }

    @Test void pinKeepsOriginalFilesAfterActiveSkillChanges() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("skill"));
        Files.writeString(dir.resolve("SKILL.md"), "version one");
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("references/input.schema.json"), "{\"v\":1}");
        ResolvedSkill skill = activeSkill(dir);
        configureAgent(skill);
        BiddingTypes.SkillPin pin = packages.pin(scope, "31", "41");
        Files.writeString(dir.resolve("SKILL.md"), "version two");
        assertEquals("version one", packages.read(scope, pin.digest(), "SKILL.md"));
        assertEquals("{\"v\":1}", packages.read(scope, pin.digest(), "references/input.schema.json"));
        assertNotEquals(pin.digest(), BiddingSkillPackages.digest(Map.of("SKILL.md", "version two", "references/input.schema.json", "{\"v\":1}")));
    }

    @Test void newScoringPinUsesUpgradedBundledSchemaAndLeavesHistoricalPackageImmutable() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("bidding-scoring-analysis"));
        String skill = resourceText("skills/bidding-scoring-analysis/SKILL.md");
        String schema = resourceText("skills/bidding-scoring-analysis/output.schema.json");
        Files.writeString(dir.resolve("SKILL.md"), skill);
        Files.writeString(dir.resolve("output.schema.json"), schema);
        ResolvedSkill active = ResolvedSkill.builder().id(41L).name("bidding-scoring-analysis").content(skill)
                .skillDir(dir).enabled(true).runtimeAvailable(true).dependencyReady(true).securityBlocked(false).build();
        AgentEntity agent = new AgentEntity(); agent.setId(31L); agent.setWorkspaceId(17L); agent.setEnabled(true);
        agent.setDeleted(0); agent.setRuntimeType("native"); agent.setAgentType("react"); agent.setModelName("ready-model");
        when(agents.getAgent(31L)).thenReturn(agent);
        when(bindings.getBoundSkillIds(31L)).thenReturn(Set.of(41L));
        when(bindings.getEffectiveToolNames(31L)).thenReturn(Set.of("bidding_read_sources"));
        when(skills.findActiveSkillById(41L, 17L)).thenReturn(active);
        when(skills.findActiveSkill("bidding-scoring-analysis", 17L)).thenReturn(active);

        Map<String, String> historicalFiles = Map.of("SKILL.md", "historical pinned skill", "output.schema.json", "historical schema bytes");
        String historicalDigest = BiddingSkillPackages.digest(historicalFiles);
        String historicalId = "historical-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                historicalId, scope.workspaceId(), scope.projectId(), "41", "old", historicalDigest,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(historicalFiles));
        String storedBefore = jdbc.queryForObject("SELECT files_json FROM mate_bidding_skill_package WHERE id=?", String.class, historicalId);

        BiddingTypes.SkillPin pin = packages.pin(scope, "31", "41");

        assertEquals("bidding-scoring-analysis", pin.files().get("SKILL.md").lines().filter(line -> line.startsWith("name:")).findFirst().orElseThrow().substring(6));
        assertTrue(jsonTree(pin.files().get("output.schema.json")).path("properties").path("totalChecks")
                .path("items").path("properties").has("criterionIds"));
        assertEquals(BiddingSkillPackages.digest(Map.of("SKILL.md", skill, "output.schema.json", schema)), pin.digest());
        assertEquals(storedBefore, jdbc.queryForObject("SELECT files_json FROM mate_bidding_skill_package WHERE id=?", String.class, historicalId));
        assertEquals(historicalDigest, jdbc.queryForObject("SELECT digest FROM mate_bidding_skill_package WHERE id=?", String.class, historicalId));
        assertTrue(packages.read(scope, pin.digest(), "output.schema.json").contains("criterionIds"));
    }

    @Test void newExportPinCarriesOnlyTheBundledControlledToolGrant() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("bidding-document-export"));
        String skillText = resourceText("skills/bidding-document-export/SKILL.md");
        Files.writeString(dir.resolve("SKILL.md"), skillText);
        ResolvedSkill active = ResolvedSkill.builder().id(42L).name("bidding-document-export")
                .content(skillText).skillDir(dir).enabled(true).runtimeAvailable(true)
                .dependencyReady(true).securityBlocked(false).build();
        AgentEntity agent = new AgentEntity(); agent.setId(31L); agent.setWorkspaceId(17L); agent.setEnabled(true);
        agent.setDeleted(0); agent.setRuntimeType("native"); agent.setAgentType("react"); agent.setModelName("ready-model");
        when(agents.getAgent(31L)).thenReturn(agent);
        when(bindings.getBoundSkillIds(31L)).thenReturn(Set.of(42L));
        when(bindings.getEffectiveToolNames(31L)).thenReturn(Set.of("bidding_read_sources", "bidding_export_document"));
        when(skills.findActiveSkillById(42L, 17L)).thenReturn(active);
        when(skills.findActiveSkill("bidding-document-export", 17L)).thenReturn(active);

        BiddingTypes.SkillPin pin = packages.pin(scope, "31", "42");

        assertEquals(BiddingSkillPackages.digest(Map.of("SKILL.md", skillText)), pin.digest());
        assertTrue(pin.files().get("SKILL.md").contains("version: 1.0.1"));
        assertTrue(pin.files().get("SKILL.md").contains("- bidding_export_document"));
        assertEquals(skillText, packages.read(scope, pin.digest(), "SKILL.md"));
    }

    @Test void readRejectsTraversalAbsoluteAndFilesOutsidePinnedPackage() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("skill"));
        Files.writeString(dir.resolve("SKILL.md"), "safe");
        ResolvedSkill skill = activeSkill(dir); configureAgent(skill);
        BiddingTypes.SkillPin pin = packages.pin(scope, "31", "41");
        for (String path : new String[] {"../secret", "/etc/passwd", "examples/other.md"}) {
            assertThrows(BiddingApiException.class, () -> packages.read(scope, pin.digest(), path), path);
        }
    }

    @Test void pinRejectsSymlinkedPackageFiles() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("skill"));
        Files.writeString(dir.resolve("SKILL.md"), "safe");
        Files.createDirectories(dir.resolve("references"));
        Path outside = Files.writeString(temp.resolve("outside.md"), "secret");
        try { Files.createSymbolicLink(dir.resolve("references/escape.md"), outside); }
        catch (UnsupportedOperationException | java.io.IOException e) { return; }
        ResolvedSkill skill = activeSkill(dir); configureAgent(skill);
        BiddingTypes.SkillPin pin = packages.pin(scope, "31", "41");
        assertThrows(BiddingApiException.class, () -> packages.read(scope, pin.digest(), "references/escape.md"));
    }

    private ResolvedSkill activeSkill(Path dir) {
        return ResolvedSkill.builder().id(41L).name("review-skill").content("ignored cache").skillDir(dir)
            .enabled(true).runtimeAvailable(true).dependencyReady(true).securityBlocked(false).build();
    }

    private void configureAgent(ResolvedSkill skill) {
        AgentEntity agent = new AgentEntity(); agent.setId(31L); agent.setWorkspaceId(17L); agent.setEnabled(true);
        agent.setDeleted(0); agent.setRuntimeType("native"); agent.setAgentType("react"); agent.setModelName("ready-model");
        when(agents.getAgent(31L)).thenReturn(agent);
        when(bindings.getBoundSkillIds(31L)).thenReturn(Set.of(41L));
        when(bindings.getEffectiveToolNames(31L)).thenReturn(Set.of("read_source"));
        when(skills.findActiveSkillById(41L, 17L)).thenReturn(skill);
        when(skills.findActiveSkill("review-skill", 17L)).thenReturn(skill);
    }

    private String resourceText(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource " + path);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode jsonTree(String value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
    }

    @Test void pinRejectsCrossWorkspaceDisabledAndUnboundEmployees() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("skill")); Files.writeString(dir.resolve("SKILL.md"), "safe");
        ResolvedSkill skill = activeSkill(dir); configureAgent(skill);
        AgentEntity agent = agents.getAgent(31L);
        agent.setWorkspaceId(99L);
        assertThrows(BiddingApiException.class, () -> packages.pin(scope, "31", "41"));
        agent.setWorkspaceId(17L); agent.setEnabled(false);
        assertThrows(BiddingApiException.class, () -> packages.pin(scope, "31", "41"));
        agent.setEnabled(true);
        when(bindings.getBoundSkillIds(31L)).thenReturn(Set.of());
        assertThrows(BiddingApiException.class, () -> packages.pin(scope, "31", "41"));
        when(bindings.getBoundSkillIds(31L)).thenReturn(null);
        when(skills.findActiveSkillById(41L, 17L)).thenReturn(null);
        assertThrows(BiddingApiException.class, () -> packages.pin(scope, "31", "41"));
    }
}
