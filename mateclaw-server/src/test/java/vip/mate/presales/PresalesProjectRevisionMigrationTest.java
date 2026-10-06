package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.presales.repository.PresalesProjectRepository;

/** Real H2 upgrade/read-back/backup evidence, not a MySQL or Kingbase acceptance claim. */
class PresalesProjectRevisionMigrationTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void upgradePreservesOldBytesAndConstraintsThenRecoversTheOldLimit() throws Exception {
        var source = source();
        var db = new JdbcTemplate(source);
        flyway(source, 219).migrate();
        String body =
                " {\"id\":\"p\",\"version\":2147483647,\"name\":\"N\",\"status\":\"ACTIVE\","
                        + "\"opaque\":{\"keep\":null},\"tasks\":[{\"id\":\"t\",\"status\":\"RUNNING\"}]} \n";
        var repository = new PresalesProjectRepository(db);
        repository.insert(
                new PresalesProjectRepository.ProjectRow(
                        "p",
                        "w",
                        Integer.MAX_VALUE,
                        "N",
                        "ACTIVE",
                        body,
                        PresalesListingProjectionV1.fromBody(body, json)));
        String receipt = " {\"version\":2147483647,\"opaque\":[null,\"old\"]} \n";
        repository.insertReceipt("w", "a", "op", "0123456789abcdef".repeat(4), receipt);
        repository.insertRevision(
                "p",
                Integer.MAX_VALUE,
                "a",
                "UPDATE_PROJECT",
                body,
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        db.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,?,?,?)",
                "p",
                "release",
                "old.docx",
                "old-digest",
                "AAEC");
        var before = snapshot(db);
        assertEquals(1, flyway(source, 220).migrate().migrationsExecuted);
        flyway(source, 220).validate();
        assertEquals(before, snapshot(db));
        assertWideColumns(source);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("UPDATE mate_presales_project SET version=NULL WHERE id='p'"));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        repository.insertRevision(
                                "p",
                                Integer.MAX_VALUE,
                                "a",
                                "DUPLICATE",
                                body,
                                java.time.LocalDateTime.now()));

        new PresalesGenerationCoordinator(
                        mock(PresalesService.class),
                        mock(PresalesContextProvider.class),
                        mock(PresalesEmployeeRuntime.class),
                        json,
                        repository,
                        hooks())
                .recoverStaleTasks();
        var recovered = repository.findRuntimeRow("w", "p").orElseThrow();
        assertEquals(2147483648L, recovered.version());
        var document = json.readTree(recovered.bodyJson());
        assertEquals(2147483648L, document.path("version").longValue());
        assertEquals("FAILED", document.path("tasks").get(0).path("status").asText());
        assertEquals(
                "INTERRUPTED_BY_RESTART", document.path("tasks").get(0).path("error").asText());
        assertEquals(json.readTree(body).path("opaque"), document.path("opaque"));
        assertEquals(
                2147483648L,
                db.queryForObject(
                        "SELECT listing_project_version FROM mate_presales_project WHERE id='p'",
                        Long.class));
        assertEquals(receipt, repository.findReceipt("w", "a", "op").orElseThrow().responseJson());
        assertEquals(
                body,
                db.queryForObject(
                        "SELECT body_json FROM mate_presales_revision WHERE project_id='p'",
                        String.class));
        assertEquals(
                "AAEC",
                db.queryForObject(
                        "SELECT content_base64 FROM mate_presales_artifact WHERE project_id='p'",
                        String.class));
        repository.insertRevision(
                "p", 2147483648L, "a", "HIGH", recovered.bodyJson(), java.time.LocalDateTime.now());
        assertEquals(
                0,
                repository.updateRuntimeBody(
                        "w",
                        "p",
                        Integer.MAX_VALUE,
                        2147483649L,
                        body,
                        PresalesListingProjectionV1.fromBody(body, json)));
        assertEquals(recovered, repository.findRuntimeRow("w", "p").orElseThrow());

        Path backup = directory.resolve("wide.sql");
        db.execute("SCRIPT TO '" + backup.toString().replace("'", "''") + "'");
        var restored = source();
        var restoredDb = new JdbcTemplate(restored);
        restoredDb.execute("RUNSCRIPT FROM '" + backup.toString().replace("'", "''") + "'");
        assertEquals(snapshot(db), snapshot(restoredDb));
        assertWideColumns(restored);
        flyway(restored, 220).validate();
        assertEquals(0, flyway(restored, 220).migrate().migrationsExecuted);
        assertEquals(
                recovered,
                new PresalesProjectRepository(restoredDb).findRuntimeRow("w", "p").orElseThrow());
        restoredDb.execute("SHUTDOWN");
        db.execute("SHUTDOWN");
    }

    @Test
    void freshInstallValidatesWithAllThreeWideColumns() throws Exception {
        var source = source();
        flyway(source, 220).migrate();
        flyway(source, 220).validate();
        assertWideColumns(source);
        assertEquals("220", flyway(source, 220).info().current().getVersion().getVersion());
        new JdbcTemplate(source).execute("SHUTDOWN");
    }

    private static List<List<String>> snapshot(JdbcTemplate db) {
        var result = new java.util.ArrayList<List<String>>();
        for (String table :
                List.of(
                        "mate_presales_project",
                        "mate_presales_operation",
                        "mate_presales_revision",
                        "mate_presales_artifact")) {
            result.addAll(
                    db.query(
                            "SELECT * FROM " + table + " ORDER BY 1,2",
                            (rs, n) -> {
                                var row = new java.util.ArrayList<String>();
                                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                                    row.add(rs.getString(i));
                                return row;
                            }));
        }
        return result;
    }

    private static void assertWideColumns(DriverManagerDataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            for (String[] column :
                    new String[][] {
                        {"mate_presales_project", "version", "0"},
                        {"mate_presales_project", "listing_project_version", "1"},
                        {"mate_presales_revision", "version", "0"}
                    }) {
                try (var rows =
                        connection.getMetaData().getColumns(null, null, column[0], column[1])) {
                    assertTrue(rows.next());
                    assertEquals(Types.BIGINT, rows.getInt("DATA_TYPE"));
                    assertEquals(Integer.parseInt(column[2]), rows.getInt("NULLABLE"));
                    assertFalse(rows.next());
                }
            }
        }
    }

    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:revision-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private static Flyway flyway(DriverManagerDataSource source, int target) {
        return Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .target(Integer.toString(target))
                .load();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PresalesPresentationHook> hooks() {
        return mock(ObjectProvider.class);
    }
}
