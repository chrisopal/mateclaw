package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.ProjectSourceAccess;

class PresalesSourceScopeTest {
    @Test
    void contextCannotIncludeKbOutsideEmployeeScopeAndRevocationInvalidatesSnapshot()
            throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:presales_source_scope;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY, kb_id BIGINT, title VARCHAR, "
                        + "extracted_text VARCHAR, original_content VARCHAR, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_source_governance(graph_id VARCHAR, source_id VARCHAR, state VARCHAR)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY, workspace_id BIGINT, enabled BOOLEAN, "
                        + "deleted INT, wiki_disabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_agent_wiki_kb(agent_id BIGINT, kb_id BIGINT, enabled BOOLEAN, deleted INT)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,0),(2,10,0)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(11,1,'A','only A',NULL,0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7,10,TRUE,0,FALSE)");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,2,TRUE,0)");
        var access = mock(PresalesAccess.class);
        when(access.require("10", "member")).thenReturn("9");
        var json = new ObjectMapper();
        ObjectNode project =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"p\",\"version\":1,\"agentId\":\"7\","
                                        + "\"materials\":[{\"kbId\":\"1\",\"role\":\"PROJECT\"}]}");
        var provider =
                new PresalesContextProvider(jdbc, json, access, new ProjectSourceAccess(jdbc));

        assertEquals(
                "SOURCE_UNAVAILABLE",
                assertThrows(
                                SemanticApiException.class,
                                () -> provider.snapshot("10", project, "S1", "goal"))
                        .code());

        jdbc.update("DELETE FROM mate_agent_wiki_kb");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,1,TRUE,0)");
        ObjectNode snapshot = provider.snapshot("10", project, "S1", "goal");
        assertEquals(1, snapshot.path("sources").size());

        jdbc.update("UPDATE mate_agent SET wiki_disabled=TRUE WHERE id=7");
        assertEquals(
                "SOURCE_UNAVAILABLE",
                assertThrows(
                                SemanticApiException.class,
                                () -> provider.revalidate("10", project, snapshot))
                        .code());
    }

    @Test
    void nameBasedToolReadUsesTheSameEmployeeKbScope() {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:presales_name_scope;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute(
                "CREATE TABLE mate_presales_project(id VARCHAR PRIMARY KEY, workspace_id BIGINT, body_json CLOB)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT, "
                        + "name VARCHAR, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY, workspace_id BIGINT, enabled BOOLEAN, "
                        + "deleted INT, wiki_disabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_agent_wiki_kb(agent_id BIGINT, kb_id BIGINT, enabled BOOLEAN, deleted INT)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,'A',0),(2,10,'B',0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7,10,TRUE,0,FALSE)");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,2,TRUE,0)");
        jdbc.update(
                "INSERT INTO mate_presales_project VALUES(?,?,?)",
                "p",
                10,
                "{\"agentId\":\"7\",\"materials\":[{\"kbId\":\"1\"}],\"tasks\":[{\"runId\":\"run\","
                        + "\"status\":\"RUNNING\",\"contextSnapshot\":{\"sources\":[]}}]}");
        var policy =
                new PresalesToolPolicy(jdbc, new ObjectMapper(), new ProjectSourceAccess(jdbc));
        var origin = ChatOrigin.web("presales:10:p:run", "9", 10L, null).withAgent(7L);
        String args = "{\"agentId\":\"7\",\"kbName\":\"A\",\"query\":\"scope\"}";

        assertFalse(policy.evaluate("wiki_search_pages", args, origin).allowed());
        jdbc.update("DELETE FROM mate_agent_wiki_kb");
        jdbc.update("INSERT INTO mate_agent_wiki_kb VALUES(7,1,TRUE,0)");
        assertTrue(policy.evaluate("wiki_search_pages", args, origin).allowed());
        jdbc.update("UPDATE mate_agent_wiki_kb SET enabled=FALSE");
        assertFalse(policy.evaluate("wiki_search_pages", args, origin).allowed());
    }
}
