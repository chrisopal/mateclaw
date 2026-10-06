package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import db.migration.h2.V218__backfill_presales_listing_projection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import vip.mate.config.JacksonConfig;

class PresalesListingProjectionBackfillTest {
    @TempDir Path directory;

    @Test
    void realUpgradeAndIsolatedRestorePreserveLegacyBytesAndMapperSemantics() throws Exception {
        String source = database(216);
        Map<String, String> bodies = new HashMap<>();
        try (var connection = connect(source)) {
            for (int i = 0; i < 205; i++) {
                String body = goldenBody(i);
                String id = "p%03d".formatted(i);
                bodies.put(id, body);
                insert(connection, id, i % 2 == 0 ? "w-a" : "w-b", i + 1, body);
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO mate_presales_operation VALUES('w-a','actor','op','hash','"
                                + " {\"receipt\":true} ')");
                statement.executeUpdate(
                        "INSERT INTO mate_presales_revision VALUES('p000',1,'actor','create','"
                                + " {\"original\":true} ',CURRENT_TIMESTAMP)");
                statement.executeUpdate(
                        "INSERT INTO mate_presales_artifact"
                                + " VALUES('p000','release','file','digest','AAEC/w==')");
                Path backup = directory.resolve("legacy.sql");
                statement.execute("SCRIPT TO '" + backup.toString().replace("'", "''") + "'");
            }
        }
        String restored = url();
        try (var connection = connect(restored);
                var statement = connection.createStatement()) {
            statement.execute(
                    "RUNSCRIPT FROM '"
                            + directory.resolve("legacy.sql").toString().replace("'", "''")
                            + "'");
        }
        for (String candidate : List.of(source, restored)) {
            migrate(candidate, 218);
            flyway(candidate, 218).validate();
            try (var connection = connect(candidate)) {
                assertLegacyAndProjection(connection, bodies);
                try (var statement = connection.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT checksum FROM flyway_schema_history WHERE"
                                                + " version='218' AND success=TRUE")) {
                    assertTrue(rows.next(), "V218 must be discovered at the existing H2 location");
                    assertEquals(
                            new V218__backfill_presales_listing_projection().getChecksum(),
                            rows.getObject(1, Integer.class));
                }
            }
        }
    }

    @Test
    void keysetBatchesAreBoundedAndRepeatedBackfillOnlyRepairsStaleRows() throws Exception {
        String source = database(217);
        Map<String, String> bodies = new HashMap<>();
        try (var connection = connect(source)) {
            for (int i = 0; i < 205; i++) {
                String id = "p%03d".formatted(i), body = goldenBody(i);
                bodies.put(id, body);
                insert(connection, id, "w", 4, body);
            }
            AtomicInteger batches = new AtomicInteger(), writes = new AtomicInteger();
            Connection observed = observe(connection, batches, writes, null);
            connection.setAutoCommit(false);
            run(observed);
            assertEquals(
                    4, batches.get(), "three bounded nonempty batches and the terminating read");
            assertEquals(205, writes.get());
            connection.commit();
            batches.set(0);
            writes.set(0);
            run(observed);
            assertEquals(4, batches.get());
            assertEquals(0, writes.get(), "a second run must leave ready projections unchanged");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE mate_presales_project SET listing_project_version=3 WHERE"
                                + " id='p100'");
            }
            run(observed);
            assertEquals(1, writes.get(), "stale derived facts must be rebuilt");
            connection.commit();
            assertBodies(connection, bodies);
        }
    }

    @Test
    void casMissRereadsExactWorkspaceAndVersionBeforeRetrying() throws Exception {
        String source = database(217);
        try (var connection = connect(source)) {
            insert(connection, "a", "other", 2, goldenBody(1));
            insert(connection, "b", "wanted", 2, goldenBody(2));
            List<List<Object>> identities = new ArrayList<>();
            AtomicInteger collisions = new AtomicInteger();
            Connection raced =
                    observe(
                            connection,
                            new AtomicInteger(),
                            new AtomicInteger(),
                            parameters -> {
                                if (!"b".equals(parameters.get(12))) return false;
                                identities.add(
                                        List.of(
                                                parameters.get(12),
                                                parameters.get(13),
                                                parameters.get(14)));
                                if (collisions.getAndIncrement() != 0) return false;
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE mate_presales_project SET"
                                                        + " version=9,body_json=? WHERE id='b' AND"
                                                        + " workspace_id='wanted'")) {
                                    update.setString(1, goldenBody(12));
                                    assertEquals(1, update.executeUpdate());
                                }
                                return true;
                            });
            connection.setAutoCommit(false);
            run(raced);
            assertEquals(List.of(List.of("b", "wanted", 2), List.of("b", "wanted", 9)), identities);
            connection.commit();
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT workspace_id,version,body_json,listing_project_version"
                                            + " FROM mate_presales_project WHERE id='b'")) {
                assertTrue(rows.next());
                assertEquals("wanted", rows.getString(1));
                assertEquals(9, rows.getInt(2));
                assertEquals(goldenBody(12), rows.getString(3));
                assertEquals(9, rows.getInt(4));
            }
            assertBodies(connection, Map.of("a", goldenBody(1), "b", goldenBody(12)));
        }
    }

    @Test
    void exhaustedCasFailsAndDoesNotCommitEarlierRows() throws Exception {
        String source = database(217);
        try (var connection = connect(source)) {
            insert(connection, "a", "w", 2, goldenBody(1));
            insert(connection, "b", "w", 2, goldenBody(2));
            AtomicInteger attempts = new AtomicInteger();
            Connection raced =
                    observe(
                            connection,
                            new AtomicInteger(),
                            new AtomicInteger(),
                            parameters -> {
                                if (!"b".equals(parameters.get(12))) return false;
                                attempts.incrementAndGet();
                                return true;
                            });
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class, () -> run(raced));
            assertEquals(
                    "V218 listing projection concurrent update retry exhausted",
                    failure.getMessage());
            assertEquals(3, attempts.get(), "a retry must be bounded");
            try (var independent = connect(source)) {
                assertEquals(
                        2, missing(independent), "the migration must not commit the first row");
            }
            connection.rollback();
            assertEquals(2, missing(connection));
            assertBodies(connection, Map.of("a", goldenBody(1), "b", goldenBody(2)));
        }
        assertTrue(new V218__backfill_presales_listing_projection().canExecuteInTransaction());
    }

    @Test
    void flywayRollsBackTheWholeJavaBackfillWhenALaterRowCannotBeWritten() throws Exception {
        String source = database(217);
        try (var connection = connect(source)) {
            insert(connection, "a", "w", 2, goldenBody(1));
            insert(connection, "b", "w", 2, goldenBody(2));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE mate_presales_project ADD CONSTRAINT backfill_failure CHECK"
                                + " (id<>'b' OR listing_contract IS NULL)");
            }
        }
        assertThrows(FlywayException.class, () -> migrate(source, 218));
        try (var connection = connect(source)) {
            assertEquals(
                    2, missing(connection), "Flyway must roll back the previously projected row");
            assertBodies(connection, Map.of("a", goldenBody(1), "b", goldenBody(2)));
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='218'"
                                            + " AND success=TRUE")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void workspaceMovedDuringCasFailsClosedRatherThanUpdatingAnotherScope() throws Exception {
        String source = database(217);
        try (var connection = connect(source)) {
            insert(connection, "a", "original", 2, goldenBody(1));
            Connection raced =
                    observe(
                            connection,
                            new AtomicInteger(),
                            new AtomicInteger(),
                            parameters -> {
                                try (var statement = connection.createStatement()) {
                                    statement.executeUpdate(
                                            "UPDATE mate_presales_project SET workspace_id='moved'"
                                                    + " WHERE id='a'");
                                }
                                return true;
                            });
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class, () -> run(raced));
            assertEquals(
                    "V218 listing projection row no longer exists in scope", failure.getMessage());
            connection.rollback();
            assertEquals(1, missing(connection));
            assertBodies(connection, Map.of("a", goldenBody(1)));
        }
    }

    @Test
    void allDialectWrappersExposeNonNullContentChecksums() {
        var h2 = new V218__backfill_presales_listing_projection();
        var mysql = new db.migration.mysql.V218__backfill_presales_listing_projection();
        var kingbase = new db.migration.kingbase.V218__backfill_presales_listing_projection();
        assertNotNull(h2.getChecksum());
        assertNotNull(mysql.getChecksum());
        assertNotNull(kingbase.getChecksum());
        assertEquals(
                h2.getChecksum(), new V218__backfill_presales_listing_projection().getChecksum());
        assertNotEquals(
                h2.getChecksum(), mysql.getChecksum(), "the concrete wrapper bytes are hashed");
        assertNotEquals(mysql.getChecksum(), kingbase.getChecksum());
    }

    private static void assertLegacyAndProjection(Connection connection, Map<String, String> bodies)
            throws Exception {
        assertBodies(connection, bodies);
        ObjectMapper runtime = runtimeMapper();
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery("SELECT * FROM mate_presales_project ORDER BY id")) {
            int count = 0;
            while (rows.next()) {
                count++;
                int originalIndex = Integer.parseInt(rows.getString("id").substring(1));
                assertEquals(originalIndex + 1, rows.getInt("version"));
                assertEquals(
                        originalIndex % 2 == 0 ? "w-a" : "w-b", rows.getString("workspace_id"));
                var expected =
                        PresalesListingProjectionV1.fromBody(
                                bodies.get(rows.getString("id")), runtime);
                assertEquals(1, rows.getInt("listing_contract"));
                assertEquals(rows.getInt("version"), rows.getInt("listing_project_version"));
                assertEquals(expected.nameKey(), rows.getString("listing_name_key"));
                assertEquals(expected.customerKey(), rows.getString("listing_customer_key"));
                assertEquals(expected.statusKey(), rows.getString("listing_status_key"));
                assertEquals(expected.ownerKey(), rows.getString("listing_owner_key"));
                assertEquals(expected.stageKey(), rows.getString("listing_stage_key"));
                assertEquals(expected.summaryJson(), rows.getString("listing_summary_json"));
                assertEquals(expected.decodeFailure(), rows.getString("listing_decode_failure"));
                assertEquals(expected.stageFailure(), rows.getString("listing_stage_failure"));
                assertEquals(expected.summaryFailure(), rows.getString("listing_summary_failure"));
                for (String field :
                        List.of(
                                "listing_decode_failure",
                                "listing_stage_failure",
                                "listing_summary_failure")) {
                    String fault = rows.getString(field);
                    if (fault != null) {
                        assertTrue(fault.length() <= 64);
                        assertFalse(
                                fault.contains("secret"),
                                "faults must never disclose raw source text");
                    }
                }
            }
            assertEquals(205, count);
        }
        try (var statement = connection.createStatement()) {
            try (var rows =
                    statement.executeQuery("SELECT response_json FROM mate_presales_operation")) {
                assertTrue(rows.next());
                assertEquals(" {\"receipt\":true} ", rows.getString(1));
                assertFalse(rows.next());
            }
            try (var rows =
                    statement.executeQuery("SELECT body_json FROM mate_presales_revision")) {
                assertTrue(rows.next());
                assertEquals(" {\"original\":true} ", rows.getString(1));
                assertFalse(rows.next());
            }
            try (var rows =
                    statement.executeQuery(
                            "SELECT digest,content_base64 FROM mate_presales_artifact")) {
                assertTrue(rows.next());
                assertEquals("digest", rows.getString(1));
                assertEquals("AAEC/w==", rows.getString(2));
                assertFalse(rows.next());
            }
        }
        assertNotNull(PresalesListingProjectionV1.fromBody(goldenBody(0), runtime).decodeFailure());
        assertNotNull(PresalesListingProjectionV1.fromBody(goldenBody(3), runtime).stageFailure());
        assertNotNull(
                PresalesListingProjectionV1.fromBody(goldenBody(4), runtime).summaryFailure());
    }

    private static void assertBodies(Connection connection, Map<String, String> bodies)
            throws SQLException {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT id,name,status,body_json FROM mate_presales_project")) {
            int count = 0;
            while (rows.next()) {
                count++;
                assertEquals("database name", rows.getString("name"));
                assertEquals("ACTIVE", rows.getString("status"));
                assertArrayEquals(
                        bodies.get(rows.getString("id")).getBytes(StandardCharsets.UTF_8),
                        rows.getString("body_json").getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(bodies.size(), count);
        }
    }

    private static ObjectMapper runtimeMapper() {
        var builder = Jackson2ObjectMapperBuilder.json();
        var config = new JacksonConfig();
        config.enumTolerantCustomizer().customize(builder);
        config.longToStringCustomizer().customize(builder);
        return builder.build();
    }

    private static String goldenBody(int i) {
        return switch (i) {
            case 0 -> "{secret unparseable";
            case 1 -> "null";
            case 2 -> "[\"secret array\"]";
            case 3 -> "{\"releases\":{\"secret\":true}}";
            case 4 -> "{\"clarifications\":{\"secret\":true}}";
            default ->
                    " { \"id\":\"body-"
                            + i
                            + "\",\"name\":\"É SQL%_\\\\😀 İ"
                            + " \",\"customer\":null,\"ownerId\":\"9007199254740993\","
                            + "\"status\":\"ACTIVE\",\"unknown\":{\"long\":9223372036854775807,\"decimal\":1.2500},"
                            + "\"solutions\":[{\"version\":9},{\"version\":2}],"
                            + "\"clarifications\":[{\"status\":\"ANSWERED\"},{\"status\":null}],\"materials\":[{\"secret\":\"source"
                            + " bytes\"}],\"tasks\":[{\"history\":true}] }\n";
        };
    }

    private static void insert(
            Connection connection, String id, String workspace, int version, String body)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO"
                                + " mate_presales_project(id,workspace_id,version,name,status,body_json)"
                                + " VALUES(?,?,?,'database name','ACTIVE',?)")) {
            statement.setString(1, id);
            statement.setString(2, workspace);
            statement.setInt(3, version);
            statement.setString(4, body);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static int missing(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM mate_presales_project WHERE listing_contract"
                                        + " IS NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void run(Connection connection) throws Exception {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        new V218__backfill_presales_listing_projection().migrate(context);
    }

    private static String database(int target) {
        String url = url();
        migrate(url, target);
        return url;
    }

    private static String url() {
        return "jdbc:h2:mem:listing-backfill-"
                + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private static void migrate(String url, int target) {
        flyway(url, target).migrate();
    }

    private static Flyway flyway(String url, int target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion(Integer.toString(target)))
                .load();
    }

    @FunctionalInterface
    private interface Collision {
        boolean miss(Map<Integer, Object> parameters) throws SQLException;
    }

    /** Executes real H2 statements; only the selected CAS return is intercepted. */
    private static Connection observe(
            Connection connection,
            AtomicInteger batches,
            AtomicInteger writes,
            Collision collision) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            try {
                                if (!method.getName().equals("prepareStatement"))
                                    return method.invoke(connection, args);
                                String sql = (String) args[0];
                                if (sql.contains("ORDER BY id LIMIT")) {
                                    assertTrue(sql.endsWith("LIMIT 100"));
                                    assertFalse(sql.contains("OFFSET"));
                                    batches.incrementAndGet();
                                }
                                PreparedStatement statement =
                                        (PreparedStatement) method.invoke(connection, args);
                                if (!sql.startsWith(
                                        "UPDATE mate_presales_project SET listing_contract="))
                                    return statement;
                                assertTrue(
                                        sql.endsWith(
                                                "WHERE id=? AND workspace_id=? AND version=?"));
                                Map<Integer, Object> parameters = new HashMap<>();
                                return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class},
                                        (ignored, operation, values) -> {
                                            try {
                                                if (operation.getName().startsWith("set")
                                                        && values != null
                                                        && values[0] instanceof Integer index) {
                                                    parameters.put(index, values[1]);
                                                }
                                                if (operation.getName().equals("executeUpdate")) {
                                                    writes.incrementAndGet();
                                                    if (collision != null
                                                            && collision.miss(parameters)) return 0;
                                                }
                                                return operation.invoke(statement, values);
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        });
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }
}
