package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real H2/Flyway storage evidence; this does not validate MySQL or Kingbase. */
class PresalesRequestHashMigrationTest {
    private static final String LEGACY_HASH = "0123456789abcdef".repeat(4);
    private static final String V2_HASH = "v2:" + "fedcba9876543210".repeat(4);
    private static final Map<String, String> TABLE_ORDERS =
            Map.of(
                    "mate_presales_project", "id",
                    "mate_presales_operation", "workspace_id,actor_id,operation_id",
                    "mate_presales_revision", "project_id,version",
                    "mate_presales_artifact", "project_id,release_id,filename");

    @TempDir Path directory;

    @Test
    void upgradeFrom218PreservesEveryLegacyFactByte() throws Exception {
        String source = legacyDatabase();
        Map<String, byte[]> before;
        try (var connection = connect(source)) {
            before = snapshot(connection);
            assertHashColumn(connection, 64);
        }
        assertEquals(1, flyway(source, 219).migrate().migrationsExecuted);
        flyway(source, 219).validate();
        try (var connection = connect(source)) {
            assertSnapshot(before, connection);
            assertHashColumn(connection, 67);
            assertEquals("219", flyway(source, 219).info().current().getVersion().getVersion());
        }
    }

    @Test
    void upgradedColumnStoresAndReadsAll67VersionedHashCharacters() throws Exception {
        String source = legacyDatabase();
        flyway(source, 219).migrate();
        assertEquals(67, V2_HASH.length());
        try (var connection = connect(source)) {
            insertOperation(
                    connection,
                    "scope-a",
                    "actor-a",
                    "new-operation",
                    V2_HASH,
                    " {\"v2\":true} \n");
        }
        try (var connection = connect(source);
                var statement =
                        connection.prepareStatement(
                                "SELECT request_hash,response_json FROM mate_presales_operation"
                                        + " WHERE workspace_id=? AND actor_id=? AND operation_id=?")) {
            statement.setString(1, "scope-a");
            statement.setString(2, "actor-a");
            statement.setString(3, "new-operation");
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertArrayEquals(V2_HASH.getBytes(StandardCharsets.US_ASCII), rows.getBytes(1));
                assertEquals(" {\"v2\":true} \n", rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void wideningPreservesNotNullAndTheOriginalScopedPrimaryKey() throws Exception {
        String source = legacyDatabase();
        flyway(source, 219).migrate();
        try (var connection = connect(source)) {
            Map<String, byte[]> before = snapshot(connection);
            SQLException nullFailure =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    insertOperation(
                                            connection,
                                            "scope-a",
                                            "actor-a",
                                            "null-hash",
                                            null,
                                            "{}"));
            assertEquals("23502", nullFailure.getSQLState());
            SQLException duplicate =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    insertOperation(
                                            connection,
                                            "scope-a",
                                            "actor-a",
                                            "legacy-op",
                                            V2_HASH,
                                            "{}"));
            assertEquals("23505", duplicate.getSQLState());
            assertSnapshot(before, connection);
            insertOperation(connection, "scope-b", "actor-a", "legacy-op", V2_HASH, "{}");
            insertOperation(connection, "scope-a", "actor-b", "legacy-op", V2_HASH, "{}");
            assertEquals(5, operationCount(connection));
            assertHashColumn(connection, 67);
        }
    }

    @Test
    void aVersionedReceiptInsertRollsBackWithoutChangingLegacyFacts() throws Exception {
        String source = legacyDatabase();
        flyway(source, 219).migrate();
        try (var connection = connect(source)) {
            Map<String, byte[]> before = snapshot(connection);
            connection.setAutoCommit(false);
            insertOperation(connection, "scope-a", "actor-a", "rollback-op", V2_HASH, "{}");
            assertEquals(4, operationCount(connection));
            try (var observer = connect(source)) {
                assertSnapshot(before, observer);
            }
            connection.rollback();
            assertSnapshot(before, connection);
        }
        try (var observer = connect(source)) {
            assertEquals(3, operationCount(observer));
        }
    }

    @Test
    void repeatedMigrationAndValidationDoNotRewriteReceiptsOrReapply219() throws Exception {
        String source = legacyDatabase();
        flyway(source, 219).migrate();
        try (var connection = connect(source)) {
            insertOperation(connection, "scope-a", "actor-a", "v2-op", V2_HASH, "{}");
            Map<String, byte[]> before = snapshot(connection);
            assertEquals(0, flyway(source, 219).migrate().migrationsExecuted);
            flyway(source, 219).validate();
            assertSnapshot(before, connection);
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT COUNT(*),MIN(checksum) FROM flyway_schema_history"
                                            + " WHERE version='219' AND success=TRUE")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertNotNull(rows.getObject(2));
            }
        }
    }

    @Test
    void freshV211SchemaMigratesDirectlyThrough219AndAcceptsVersionedReceipts() throws Exception {
        String source = minimalDatabase();
        assertEquals(3, flyway(source, 219).migrate().migrationsExecuted);
        flyway(source, 219).validate();
        try (var connection = connect(source)) {
            assertHashColumn(connection, 67);
            insertOperation(connection, "fresh-scope", "fresh-actor", "fresh-op", V2_HASH, "{}");
            assertEquals(1, operationCount(connection));
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT request_hash FROM mate_presales_operation")) {
                assertTrue(rows.next());
                assertEquals(V2_HASH, rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void nativeBackupRestoresIntoAnIsolatedDatabaseAndUpgradesWithoutFactChanges()
            throws Exception {
        String source = legacyDatabase();
        String restored = url();
        Path backup = directory.resolve("synthetic-v218.sql");
        Map<String, byte[]> before;
        try (var connection = connect(source);
                var statement = connection.createStatement()) {
            before = snapshot(connection);
            statement.execute("SCRIPT TO '" + sqlPath(backup) + "'");
        }
        assertNotEquals(source, restored);
        try (var connection = connect(restored);
                var statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + sqlPath(backup) + "'");
            assertSnapshot(before, connection);
            assertHashColumn(connection, 64);
        }
        assertEquals(1, flyway(restored, 219).migrate().migrationsExecuted);
        flyway(restored, 219).validate();
        try (var connection = connect(restored)) {
            assertSnapshot(before, connection);
            assertHashColumn(connection, 67);
        }
        try (var connection = connect(source)) {
            assertSnapshot(before, connection);
            assertHashColumn(connection, 64);
        }
    }

    private static String legacyDatabase() throws Exception {
        String source = minimalDatabase();
        try (var connection = connect(source)) {
            seedLegacyFacts(connection);
        }
        flyway(source, 218).migrate();
        flyway(source, 218).validate();
        return source;
    }

    private static String minimalDatabase() throws Exception {
        String source = url();
        try (var connection = connect(source)) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/h2/V211__presales_projects.sql"));
        }
        return source;
    }

    private static void seedLegacyFacts(Connection connection) throws SQLException {
        for (int i = 1; i <= 2; i++) {
            String body =
                    " { \"id\":\"body-"
                            + i
                            + "\",\"name\":\"合成 Ω 😀\","
                            + "\"status\":\"ACTIVE\",\"unknown\":{\"decimal\":1.2500},"
                            + "\"ownerId\":\"9007199254740993\",\"solutions\":[],\"releases\":[],"
                            + "\"clarifications\":[] }\n";
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO"
                                    + " mate_presales_project(id,workspace_id,version,name,status,body_json)"
                                    + " VALUES(?,?,?,'synthetic name','ACTIVE',?)")) {
                statement.setString(1, "project-" + i);
                statement.setString(2, "scope-" + i);
                statement.setInt(3, i);
                statement.setString(4, body);
                assertEquals(1, statement.executeUpdate());
            }
        }
        insertOperation(
                connection,
                "scope-a",
                "actor-a",
                "legacy-op",
                LEGACY_HASH,
                " {\"receipt\":true,\"literal\":\"\\\\uD800\",\"text\":\"Ω 😀\"} \n");
        insertOperation(connection, "scope-a", "actor-a", "other-op", "a".repeat(64), "null\n");
        insertOperation(connection, "scope-c", "actor-c", "legacy-op", "0".repeat(64), "[1,2] ");
        try (var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "INSERT INTO"
                                    + " mate_presales_revision(project_id,version,actor_id,action,body_json,created_at)"
                                    + " VALUES('project-1',1,'actor-a','create',' {\"original\":1.2500}"
                                    + " ',TIMESTAMP '2026-01-02 03:04:05.123456')"));
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "INSERT INTO"
                                    + " mate_presales_artifact(project_id,release_id,filename,digest,content_base64)"
                                    + " VALUES('project-1','release-1','synthetic.bin','"
                                    + LEGACY_HASH
                                    + "','AAEC/w==')"));
        }
    }

    private static void insertOperation(
            Connection connection,
            String scope,
            String actor,
            String operation,
            String hash,
            String response)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO"
                                + " mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json)"
                                + " VALUES(?,?,?,?,?)")) {
            statement.setString(1, scope);
            statement.setString(2, actor);
            statement.setString(3, operation);
            statement.setString(4, hash);
            statement.setString(5, response);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Map<String, byte[]> snapshot(Connection connection) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String table :
                List.of(
                        "mate_presales_project",
                        "mate_presales_operation",
                        "mate_presales_revision",
                        "mate_presales_artifact")) {
            var bytes = new ByteArrayOutputStream();
            try (var encoded = new DataOutputStream(bytes);
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT * FROM "
                                            + table
                                            + " ORDER BY "
                                            + TABLE_ORDERS.get(table))) {
                int columns = rows.getMetaData().getColumnCount();
                encoded.writeInt(columns);
                for (int i = 1; i <= columns; i++)
                    encoded.writeUTF(rows.getMetaData().getColumnLabel(i));
                while (rows.next()) {
                    encoded.writeBoolean(true);
                    for (int i = 1; i <= columns; i++) {
                        String value = rows.getString(i);
                        encoded.writeBoolean(value != null);
                        if (value == null) continue;
                        // Preserve exact JDBC code units, including isolated surrogates.
                        encoded.writeInt(value.length());
                        for (int unit = 0; unit < value.length(); unit++) {
                            encoded.writeChar(value.charAt(unit));
                        }
                    }
                }
                encoded.writeBoolean(false);
            }
            result.put(table, bytes.toByteArray());
        }
        return result;
    }

    private static void assertSnapshot(Map<String, byte[]> expected, Connection connection)
            throws Exception {
        Map<String, byte[]> actual = snapshot(connection);
        assertEquals(expected.keySet(), actual.keySet());
        for (String table : expected.keySet())
            assertArrayEquals(expected.get(table), actual.get(table), table);
    }

    private static void assertHashColumn(Connection connection, int length) throws SQLException {
        try (var columns =
                connection
                        .getMetaData()
                        .getColumns(null, null, "mate_presales_operation", "request_hash")) {
            assertTrue(columns.next());
            assertEquals(length, columns.getInt("COLUMN_SIZE"));
            assertEquals(java.sql.DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"));
            assertFalse(columns.next());
        }
    }

    private static int operationCount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM mate_presales_operation")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static Flyway flyway(String source, int target) {
        return Flyway.configure()
                .dataSource(source, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .baselineOnMigrate(true)
                .baselineVersion("216")
                .target(Integer.toString(target))
                .load();
    }

    private static Connection connect(String source) throws SQLException {
        return DriverManager.getConnection(source, "sa", "");
    }

    private static String url() {
        return "jdbc:h2:mem:request-hash-"
                + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static String sqlPath(Path path) {
        return path.toString().replace("'", "''");
    }
}
