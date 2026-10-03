package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.semantic.source.SourceGovernanceReadService;
import vip.mate.semantic.source.repository.SourceGovernanceReadRepository;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiSourceReadRepository;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiSourceReadService;
import vip.mate.workspace.core.service.ProjectSourceAccess;

class PresalesSourceAuthorizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private PresalesSourceAuthorization authorization;

    @BeforeEach
    void setup() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:presales_source_authorization_"
                                        + UUID.randomUUID()
                                        + ";DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY,workspace_id BIGINT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY,kb_id BIGINT,deleted INT,extracted_text CLOB,original_content CLOB)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY,workspace_id BIGINT,enabled BOOLEAN,deleted INT,wiki_disabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_agent_wiki_kb(agent_id BIGINT,kb_id BIGINT,enabled BOOLEAN,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_source_governance(graph_id VARCHAR,source_kind VARCHAR,source_id VARCHAR,state VARCHAR)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,0),(2,10,0),(3,20,0)");
        jdbc.update(
                "INSERT INTO mate_wiki_raw_material VALUES(101,1,0,'','original'),(102,2,0,'second','original'),(103,3,0,'foreign','foreign')");
        jdbc.update("INSERT INTO mate_agent VALUES(7,10,TRUE,0,FALSE)");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,1,TRUE,0)");
        var wiki = mock(WikiKnowledgeBaseService.class);
        when(wiki.getById(anyLong()))
                .thenAnswer(
                        call -> {
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT * FROM mate_wiki_knowledge_base WHERE id=?",
                                            new Object[] {call.getArgument(0)});
                            if (rows.isEmpty()) return null;
                            var row = rows.getFirst();
                            var kb = new WikiKnowledgeBaseEntity();
                            kb.setId(((Number) row.get("ID")).longValue());
                            kb.setWorkspaceId(((Number) row.get("WORKSPACE_ID")).longValue());
                            kb.setDeleted((Integer) row.get("DELETED"));
                            return kb;
                        });
        authorization =
                new PresalesSourceAuthorization(
                        json,
                        wiki,
                        new ProjectSourceAccess(jdbc),
                        new WikiSourceReadService(new WikiSourceReadRepository(jdbc)),
                        new SourceGovernanceReadService(new SourceGovernanceReadRepository(jdbc)));
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void digestChecksUseExactPersistedUtf8TextWithoutExtractionOrTrimming() {
        for (String text : new String[] {"original", " \t", "中文😀"}) {
            jdbc.update(
                    "UPDATE mate_wiki_raw_material SET extracted_text=? WHERE id=101",
                    text.equals("original") ? "" : text);
            authorization.currentSource("10", "101", digest(text), true);
            denied(
                    409,
                    "SOURCE_CHANGED",
                    () -> authorization.currentSource("10", "101", digest("different"), true));
            authorization.currentSource("10", "101", "old digest ignored", false);
        }
        jdbc.update(
                "UPDATE mate_wiki_raw_material SET extracted_text=NULL,original_content=NULL WHERE id=101");
        authorization.currentSource("10", "101", digest(""), true);
        assertNull(
                jdbc.queryForObject(
                        "SELECT original_content FROM mate_wiki_raw_material WHERE id=101",
                        String.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_source_governance", Integer.class));
    }

    @Test
    void currentReadsRejectDeletedNullDeletedMissingAndForeignWorkspace() {
        for (String id : new String[] {"103", "999"})
            denied(
                    403,
                    "SOURCE_UNAVAILABLE",
                    () -> authorization.currentSource("10", id, "", false));
        for (String table : new String[] {"mate_wiki_raw_material", "mate_wiki_knowledge_base"}) {
            int id = table.equals("mate_wiki_raw_material") ? 101 : 1;
            for (Integer deleted : new Integer[] {1, null}) {
                jdbc.update("UPDATE " + table + " SET deleted=? WHERE id=?", deleted, id);
                denied(
                        403,
                        "SOURCE_UNAVAILABLE",
                        () -> authorization.currentSource("10", "101", "", false));
            }
            jdbc.update("UPDATE " + table + " SET deleted=0 WHERE id=?", id);
        }
    }

    @Test
    void materialChecksRunBeforeHistoryAndRetainLegacyNullDeletedKbBehavior() {
        var project = project(false);
        project.withArray("materials").addObject().put("kbId", "999");
        baseline(project, "g", "999");
        var error =
                denied(
                        403,
                        "MATERIAL_UNAVAILABLE",
                        () -> authorization.authorizeMaterials("10", project));
        assertEquals("Project material access was revoked", error.getMessage());
        project.withArray("materials").removeAll().addObject().put("kbId", "1");
        project.withArray("baselines").removeAll();
        jdbc.update("UPDATE mate_wiki_knowledge_base SET deleted=NULL WHERE id=1");
        authorization.authorizeMaterials("10", project);
        denied(
                403,
                "SOURCE_UNAVAILABLE",
                () -> authorization.currentSource("10", "101", "", false));
    }

    @Test
    void historicalReadsResolveActualKbAndApplyEmployeeIntersectionOnlyWhenBound() {
        var project = project(false);
        baseline(project, "g", "102").put("kbId", "1").put("textDigest", "old digest");
        authorization.authorizeMaterials("10", project);
        project.put("agentId", "7");
        denied(403, "SOURCE_UNAVAILABLE", () -> authorization.authorizeMaterials("10", project));
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,2,TRUE,0)");
        authorization.authorizeMaterials("10", project);
        jdbc.update("UPDATE mate_agent_wiki_kb SET enabled=FALSE WHERE kb_id=2");
        denied(403, "SOURCE_UNAVAILABLE", () -> authorization.authorizeMaterials("10", project));
    }

    @Test
    void baselineBlankGraphAndCrossKindWithdrawalStillDenyButTaskBlankGraphDoesNot() {
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('','OTHER_KIND','101','WITHDRAWN')");
        var project = project(false);
        baseline(project, "", "101");
        var error =
                denied(
                        403,
                        "SOURCE_UNAVAILABLE",
                        () -> authorization.authorizeMaterials("10", project));
        assertEquals("Historical evidence source withdrawn", error.getMessage());
        project.withArray("baselines").removeAll();
        project.withArray("tasks")
                .addObject()
                .putObject("contextSnapshot")
                .putArray("sources")
                .addObject()
                .put("sourceRef", "101")
                .put("graphId", "");
        authorization.authorizeMaterials("10", project);
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('g','OTHER_KIND','101','WITHDRAWN')");
        ((ObjectNode) project.path("tasks").get(0).path("contextSnapshot").path("sources").get(0))
                .put("graphId", "g");
        assertEquals(
                "Task source withdrawn",
                denied(
                                403,
                                "SOURCE_UNAVAILABLE",
                                () -> authorization.authorizeMaterials("10", project))
                        .getMessage());
    }

    @Test
    void frozenCandidateRequiresCurrentBindingsForEmployeeButDoesNotMutateSnapshot() {
        var project = project(true);
        project.withArray("materials").addObject().put("kbId", "1");
        var snapshot =
                project.withArray("releases")
                        .addObject()
                        .put("status", "PENDING")
                        .putObject("handoffSnapshot");
        snapshot.putArray("materials").addObject().put("kbId", "2");
        var before = project.deepCopy();
        denied(
                403,
                "SOURCE_UNAVAILABLE",
                () -> authorization.authorizeReleaseSources("10", project));
        assertEquals(before, project);
        project.put("agentId", "");
        authorization.authorizeReleaseSources("10", project);
        assertEquals(before.path("releases"), project.path("releases"));
    }

    @Test
    void scalarFrozenReferencesUseFrozenGraphAndMalformedProvenanceFailsClosed() {
        var project = project(false);
        var snapshot = project.withArray("releases").addObject().putObject("handoffSnapshot");
        snapshot.putArray("materials").addObject().put("kbId", "1").put("graphId", "g");
        snapshot.putArray("sourceRefs").add("101");
        var before = project.deepCopy();
        authorization.authorizeReleaseSources("10", project);
        assertEquals(before, project);
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('g','RAW','101','WITHDRAWN')");
        denied(
                403,
                "SOURCE_UNAVAILABLE",
                () -> authorization.authorizeReleaseSources("10", project));
        assertEquals(before, project);
        snapshot.withArray("sourceRefs").removeAll().addObject();
        assertEquals(
                "Release source provenance is unavailable",
                denied(
                                403,
                                "SOURCE_UNAVAILABLE",
                                () -> authorization.authorizeReleaseSources("10", project))
                        .getMessage());
    }

    private ObjectNode project(boolean employee) {
        var project = json.createObjectNode().put("agentId", employee ? "7" : "");
        for (String key : new String[] {"materials", "baselines", "tasks", "releases"})
            project.putArray(key);
        return project;
    }

    private ObjectNode baseline(ObjectNode project, String graph, String source) {
        return project.withArray("baselines")
                .addObject()
                .putArray("references")
                .addObject()
                .put("graphId", graph)
                .putArray("sources")
                .addObject()
                .put("sourceRef", source);
    }

    private PresalesSourceAuthorization.Denied denied(int status, String code, Runnable action) {
        var error = assertThrows(PresalesSourceAuthorization.Denied.class, action::run);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
        return error;
    }

    private String digest(String text) {
        return PresalesArtifactRenderer.digest(text.getBytes(StandardCharsets.UTF_8));
    }
}
