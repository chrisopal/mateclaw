package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

class PresalesAtomicResultAcceptanceTest {
    @Test
    void actorRevocationWaitsForResultAuthorityTransaction() throws Exception {
        revocationWaitsForResultAuthorityTransaction("mate_user", "id", "9");
    }

    @Test
    void employeeRevocationWaitsForResultAuthorityTransaction() throws Exception {
        revocationWaitsForResultAuthorityTransaction("mate_agent", "id", "7");
    }

    @Test
    void sourceWithdrawalWaitsForResultAuthorityTransaction() throws Exception {
        revocationWaitsForResultAuthorityTransaction("mate_semantic_graph", "id", "g");
    }

    @Test
    void sourceEditWaitsForResultAuthorityTransaction() throws Exception {
        revocationWaitsForResultAuthorityTransaction("mate_wiki_raw_material", "id", "33");
    }

    private void revocationWaitsForResultAuthorityTransaction(
            String table, String key, String value) throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:presales_authority_" + table + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE mate_presales_project(id VARCHAR PRIMARY KEY, workspace_id VARCHAR, "
                        + "version INT, name VARCHAR, status VARCHAR, body_json CLOB)");
        jdbc.execute(
                "CREATE TABLE mate_presales_operation(workspace_id VARCHAR, actor_id VARCHAR, "
                        + "operation_id VARCHAR, request_hash VARCHAR, response_json CLOB)");
        authorityTables(jdbc);
        var json = new ObjectMapper();
        String sources =
                table.startsWith("mate_wiki_") || table.equals("mate_semantic_graph")
                        ? ",\"sources\":[{\"kbId\":\"3\",\"sourceRef\":\"33\",\"graphId\":\"g\"}]"
                        : "";
        jdbc.update(
                "INSERT INTO mate_presales_project VALUES(?,?,?,?,?,?)",
                "p",
                "1",
                2,
                "case",
                "ACTIVE",
                "{\"id\":\"p\",\"version\":2,\"status\":\"ACTIVE\",\"tasks\":[{\"id\":\"t\","
                        + "\"status\":\"RUNNING\",\"authority\":\"UNTRUSTED_DRAFT\",\"skill\":\"S1\",\"agentId\":\"7\",\"modelConfigId\":\"17\","
                        + "\"contextSnapshot\":{\"caseRef\":\"p\",\"actorId\":\"9\",\"projectVersion\":2"
                        + sources
                        + "}}]}");
        var access = mock(PresalesAccess.class);
        when(access.require("1", "member")).thenReturn("9");
        var runtime = mock(PresalesEmployeeRuntime.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PresalesEmployeeRuntime> employees = mock(ObjectProvider.class);
        when(employees.getIfAvailable()).thenReturn(runtime);
        when(employees.getObject()).thenReturn(runtime);
        var service =
                new PresalesService(
                        jdbc,
                        json,
                        access,
                        mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(vip.mate.semantic.config.SemanticProperties.class),
                        mock(ObjectProvider.class),
                        mock(PresalesArtifactRenderer.class),
                        employees,
                        new ProjectAuthorityFence(
                                jdbc, mock(org.mybatis.spring.SqlSessionTemplate.class)));
        var candidate =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"t\",\"status\":\"SUCCEEDED\",\"skill\":\"S1\",\"agentId\":\"7\","
                                        + "\"modelConfigId\":\"17\",\"contextSnapshot\":{\"caseRef\":\"p\",\"actorId\":\"9\",\"projectVersion\":2"
                                        + sources
                                        + "},"
                                        + "\"result\":{\"schemaVersion\":1}}");
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        ignored -> {
                            checked.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            throw PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE");
                        })
                .when(runtime)
                .revalidate(any(), any(), any(), any());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acceptance =
                    executor.submit(
                            () ->
                                    assertThrows(
                                            SemanticApiException.class,
                                            () ->
                                                    transaction.execute(
                                                            status ->
                                                                    service.saveEmployeeTask(
                                                                            "1",
                                                                            "p",
                                                                            new PresalesDtos
                                                                                    .Command(
                                                                                    2,
                                                                                    "finish",
                                                                                    "SAVE_AI_TASK",
                                                                                    candidate)))));
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var revocation =
                    executor.submit(
                            () -> {
                                started.countDown();
                                transaction.executeWithoutResult(
                                        status ->
                                                jdbc.update(
                                                        "UPDATE "
                                                                + table
                                                                + " SET enabled=FALSE WHERE "
                                                                + key
                                                                + "=?",
                                                        value));
                            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revocation.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            acceptance.get(5, TimeUnit.SECONDS);
            revocation.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertFalse(jdbc.queryForObject("SELECT enabled FROM " + table, Boolean.class));
    }

    @Test
    void lateRevocationUnderProjectLockCannotCommitSuccess() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:presales_atomic_result;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE mate_presales_project(id VARCHAR PRIMARY KEY, workspace_id VARCHAR, "
                        + "version INT, name VARCHAR, status VARCHAR, body_json CLOB)");
        jdbc.execute(
                "CREATE TABLE mate_presales_operation(workspace_id VARCHAR, actor_id VARCHAR, "
                        + "operation_id VARCHAR, request_hash VARCHAR, response_json CLOB)");
        authorityTables(jdbc);
        var json = new ObjectMapper();
        var project =
                (ObjectNode)
                        json.readTree(
                                """
                                {"id":"p","workspaceId":"1","version":2,"name":"case",
                                "status":"ACTIVE","tasks":[{"id":"t","status":"RUNNING","authority":"UNTRUSTED_DRAFT","skill":"S1",
                                "agentId":"7","modelConfigId":"17",
                                "contextSnapshot":{"caseRef":"p","actorId":"9","projectVersion":2}}]}
                                """);
        jdbc.update(
                "INSERT INTO mate_presales_project VALUES(?,?,?,?,?,?)",
                "p",
                "1",
                2,
                "case",
                "ACTIVE",
                json.writeValueAsString(project));
        var access = mock(PresalesAccess.class);
        when(access.require("1", "member")).thenReturn("9");
        var runtime = mock(PresalesEmployeeRuntime.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PresalesEmployeeRuntime> employees = mock(ObjectProvider.class);
        when(employees.getIfAvailable()).thenReturn(runtime);
        when(employees.getObject()).thenReturn(runtime);
        var service =
                new PresalesService(
                        jdbc,
                        json,
                        access,
                        mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(vip.mate.semantic.config.SemanticProperties.class),
                        mock(ObjectProvider.class),
                        mock(PresalesArtifactRenderer.class),
                        employees,
                        new ProjectAuthorityFence(
                                jdbc, mock(org.mybatis.spring.SqlSessionTemplate.class)));
        var candidate =
                (ObjectNode)
                        json.readTree(
                                """
                                {"id":"t","status":"SUCCEEDED","skill":"S1","agentId":"7",
                                "modelConfigId":"17",
                                "contextSnapshot":{"caseRef":"p","actorId":"9","projectVersion":2},
                                "result":{"schemaVersion":1}}
                                """);
        doThrow(PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE"))
                .when(runtime)
                .revalidate(any(), any(), any(), any());

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(
                SemanticApiException.class,
                () ->
                        transaction.execute(
                                status ->
                                        service.saveEmployeeTask(
                                                "1",
                                                "p",
                                                new PresalesDtos.Command(
                                                        2, "finish", "SAVE_AI_TASK", candidate))));

        verify(runtime).revalidate(any(), any(), any(), any());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id='p'", Integer.class));
        assertEquals(
                "RUNNING",
                json.readTree(
                                jdbc.queryForObject(
                                        "SELECT body_json FROM mate_presales_project WHERE id='p'",
                                        String.class))
                        .path("tasks")
                        .get(0)
                        .path("status")
                        .asText());

        // A worker result must not introduce sources that were absent from the durable task.
        clearInvocations(runtime);
        candidate
                .with("contextSnapshot")
                .putArray("sources")
                .addObject()
                .put("kbId", "3")
                .put("sourceRef", "33")
                .put("graphId", "g");
        SemanticApiException changedTask =
                assertThrows(
                        SemanticApiException.class,
                        () ->
                                transaction.execute(
                                        status ->
                                                service.saveEmployeeTask(
                                                        "1",
                                                        "p",
                                                        new PresalesDtos.Command(
                                                                2,
                                                                "finish-changed",
                                                                "SAVE_AI_TASK",
                                                                candidate))));
        assertEquals("TASK_SCOPE_CHANGED", changedTask.code());
        verify(runtime, never()).revalidate(any(), any(), any(), any());
    }

    private static void authorityTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE mate_user(id BIGINT PRIMARY KEY, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id VARCHAR PRIMARY KEY, workspace_id BIGINT, "
                        + "user_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY, workspace_id BIGINT, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE mate_model_config(id BIGINT PRIMARY KEY, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT,"
                        + " deleted INT, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY, kb_id BIGINT,"
                        + " original_content VARCHAR, extracted_text VARCHAR, deleted INT, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_graph(id VARCHAR PRIMARY KEY, workspace_id BIGINT, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_source_governance(graph_id VARCHAR, source_id VARCHAR, state VARCHAR)");
        jdbc.update("INSERT INTO mate_user VALUES(9, TRUE)");
        jdbc.update("INSERT INTO mate_workspace VALUES(1)");
        jdbc.update("INSERT INTO mate_workspace_member VALUES('m', 1, 9, 0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7, 1, TRUE)");
        jdbc.update("INSERT INTO mate_model_config VALUES(17, TRUE)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(3, 1, 0, TRUE)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(33, 3, 'source', NULL, 0, TRUE)");
        jdbc.update("INSERT INTO mate_semantic_graph VALUES('g', 1, TRUE)");
    }
}
