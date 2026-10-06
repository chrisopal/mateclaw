package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesProjectRepository;

/** Test fixtures use the real V2 writer while retaining each test's explicit fault values. */
final class PresalesStorageTestSupport {
    private PresalesStorageTestSupport() {}

    static String body(JdbcTemplate jdbc, String scope, String id) {
        return new PresalesProjectRepository(jdbc).findBody(scope, id, false).orElseThrow();
    }

    static void write(JdbcTemplate jdbc, ObjectMapper json, String scope, String id, String body) {
        long version =
                jdbc.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE workspace_id=? AND id=?",
                        Long.class,
                        scope,
                        id);
        write(jdbc, json, scope, id, body, version);
    }

    static void write(
            JdbcTemplate jdbc,
            ObjectMapper json,
            String scope,
            String id,
            String body,
            long nextVersion) {
        var repository = new PresalesProjectRepository(jdbc);
        transaction(jdbc)
                .executeWithoutResult(
                        status -> {
                            long current =
                                    jdbc.queryForObject(
                                            "SELECT version FROM mate_presales_project WHERE workspace_id=? AND id=? FOR UPDATE",
                                            Long.class,
                                            scope,
                                            id);
                            assertEquals(
                                    1,
                                    repository.updateRuntimeBody(
                                            scope,
                                            id,
                                            current,
                                            nextVersion,
                                            body,
                                            PresalesListingProjectionV1.fromBody(body, json)));
                        });
    }

    static void receipt(
            JdbcTemplate jdbc,
            ObjectMapper json,
            String scope,
            String actor,
            String operation,
            String hash,
            ObjectNode response) {
        var repository = new PresalesProjectRepository(jdbc);
        transaction(jdbc)
                .executeWithoutResult(
                        status -> {
                            String original = null;
                            String id = response.path("id").asText();
                            if (response.path("storageVersion").asInt() == 2) {
                                original = body(jdbc, scope, id);
                                // Persist historical objects before pinning their receipt; restore
                                // live facts in the
                                // same transaction. No aggregate JSON is injected into a V2 storage
                                // column.
                                write(jdbc, json, scope, id, response.toString());
                            }
                            repository.insertReceipt(
                                    scope, actor, operation, hash, response.toString());
                            if (original != null) write(jdbc, json, scope, id, original);
                        });
    }

    private static TransactionTemplate transaction(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
}
