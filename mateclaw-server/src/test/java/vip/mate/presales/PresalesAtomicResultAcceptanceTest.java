package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.semantic.web.SemanticApiException;

class PresalesAtomicResultAcceptanceTest {
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
        var json = new ObjectMapper();
        var project =
                (ObjectNode)
                        json.readTree(
                                """
                                {"id":"p","workspaceId":"1","version":2,"name":"case",
                                "status":"ACTIVE","tasks":[{"id":"t","status":"RUNNING"}]}
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
                        employees);
        var candidate =
                (ObjectNode)
                        json.readTree(
                                """
                                {"id":"t","status":"SUCCEEDED","skill":"S1",
                                "contextSnapshot":{"caseRef":"p","projectVersion":2},
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
    }
}
