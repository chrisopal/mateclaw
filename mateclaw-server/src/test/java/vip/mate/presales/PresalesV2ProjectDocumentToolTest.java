package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.workspace.core.service.ProjectSourceAccess;

/** Real V2 rows, real employee grants and raw documents; no mocked scope or source decision. */
class PresalesV2ProjectDocumentToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private PresalesProjectDocumentTool tool;
    private PresalesToolPolicy policy;
    private ObjectNode project;
    private static final String TEXT = "客户需求原始文档，必须按原文回读。";

    @BeforeEach
    void database() throws Exception {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:document_v2_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V217__presales_listing_projection.sql"),
                        new ClassPathResource("db/migration/h2/V223__presales_object_storage.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY,workspace_id BIGINT,name VARCHAR,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY,kb_id BIGINT,title VARCHAR,source_type VARCHAR,mime_type VARCHAR,extracted_text VARCHAR,original_content VARCHAR,content_hash VARCHAR,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY,workspace_id BIGINT,enabled BOOLEAN,deleted INT,wiki_disabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_agent_wiki_kb(agent_id BIGINT,kb_id BIGINT,enabled BOOLEAN,deleted INT)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(42,10,'项目材料',0),(43,11,'其他空间',0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7,10,TRUE,0,FALSE)");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,42,TRUE,0)");
        jdbc.update(
                "INSERT INTO mate_wiki_raw_material VALUES(101,42,'原始文档','FILE','text/plain',?,NULL,'',0)",
                TEXT);
        project =
                (ObjectNode)
                        json.readTree(
                                """
                {"storageVersion":2,"workspaceId":"10","id":"p","version":1,"name":"project","status":"ACTIVE","agentId":"7",
                 "materials":[{"id":"m","kbId":"42"}],
                 "tasks":[{"id":"t","runId":"run","status":"RUNNING","contextSnapshot":{"sources":[{"sourceRef":"101","kbId":"42"}]}}]}
                """);
        var repository = new PresalesProjectRepository(jdbc);
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(
                        status ->
                                repository.insert(
                                        new PresalesProjectRepository.ProjectRow(
                                                "p",
                                                "10",
                                                1,
                                                "project",
                                                "ACTIVE",
                                                project.toString())));
        policy = new PresalesToolPolicy(jdbc, json, new ProjectSourceAccess(jdbc), repository);
        tool = new PresalesProjectDocumentTool(jdbc, json, policy, repository);
    }

    @AfterEach
    void close() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private ChatOrigin origin() {
        return ChatOrigin.web("presales:10:p:run", "9", 10L, null).withAgent(7L);
    }

    private ToolContext context(ChatOrigin origin) {
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    @Test
    void expandedProjectAllowsPinnedDocumentAndRetainsTextDigestAndTruncation() throws Exception {
        assertFalse(
                jdbc.queryForObject("SELECT body_json FROM mate_presales_project", String.class)
                        .contains("sourceRef"));
        assertTrue(
                policy.evaluate(
                                "project_document_read",
                                "{\"sourceRef\":\"101\",\"kbId\":\"42\"}",
                                origin())
                        .allowed());
        var full = json.readTree(tool.read("101", "42", null, context(origin())));
        assertFalse(full.has("error"), full.toString());
        assertEquals(TEXT, full.path("text").asText());
        assertEquals(
                PresalesArtifactRenderer.digest(TEXT.getBytes(StandardCharsets.UTF_8)),
                full.path("digest").asText());
        assertFalse(full.path("truncated").asBoolean());
        var shortRead = json.readTree(tool.read("101", "42", 5, context(origin())));
        assertEquals(TEXT.substring(0, 5), shortRead.path("text").asText());
        assertTrue(shortRead.path("truncated").asBoolean());
        assertEquals(full.path("digest"), shortRead.path("digest"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grant",
                "source",
                "run",
                "revision",
                "employee",
                "workspace",
                "unbound",
                "unknown-source"
            })
    void expandedReadStillRejectsRevocationMissingRowsAndScopeChanges(String change)
            throws Exception {
        ChatOrigin caller = origin();
        String kb = "42", source = "101";
        switch (change) {
            case "grant" -> jdbc.update("UPDATE mate_agent_wiki_kb SET enabled=FALSE");
            case "source" -> jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1");
            case "run" -> {
                ((ObjectNode) project.path("tasks").get(0)).put("status", "CANCELLED");
                PresalesStorageTestSupport.write(jdbc, json, "10", "p", project.toString());
            }
            case "revision" ->
                    jdbc.update(
                            "DELETE FROM mate_presales_object_revision WHERE object_kind='tasks'");
            case "employee" -> caller = caller.withAgent(8L);
            case "workspace" ->
                    caller = ChatOrigin.web("presales:11:p:run", "9", 11L, null).withAgent(7L);
            case "unbound" -> kb = "43";
            case "unknown-source" -> source = "102";
            default -> throw new AssertionError(change);
        }
        var rejected = json.readTree(tool.read(source, kb, null, context(caller)));
        assertTrue(rejected.has("error"), change + ": " + rejected);
        assertFalse(rejected.has("text"));
        assertFalse(rejected.toString().contains(TEXT));
    }
}
