package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.presales.repository.PresalesProjectRepository;

class PresalesSqlListingTest {
    private final ObjectMapper json = new ObjectMapper();
    private LedgerJdbc jdbc;
    private PresalesService service;
    private PresalesProjectQueryService queryService;
    private PresalesAccess access;
    private boolean external;
    private boolean ownsTables;

    static final class LedgerJdbc extends JdbcTemplate {
        final List<String> reads = new ArrayList<>();
        int rows;

        LedgerJdbc(javax.sql.DataSource source) {
            super(source);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            reads.add(sql);
            return super.query(
                    sql,
                    (RowMapper<T>)
                            (rs, index) -> {
                                rows++;
                                return mapper.mapRow(rs, index);
                            },
                    args);
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
            reads.add(sql);
            return super.query(
                    sql,
                    (ResultSetExtractor<T>)
                            rs ->
                                    extractor.extractData(
                                            (ResultSet)
                                                    Proxy.newProxyInstance(
                                                            ResultSet.class.getClassLoader(),
                                                            new Class<?>[] {ResultSet.class},
                                                            (proxy, method, arguments) -> {
                                                                try {
                                                                    Object result =
                                                                            method.invoke(
                                                                                    rs, arguments);
                                                                    if (method.getName()
                                                                                    .equals("next")
                                                                            && Boolean.TRUE.equals(
                                                                                    result)) rows++;
                                                                    return result;
                                                                } catch (
                                                                        InvocationTargetException
                                                                                failure) {
                                                                    throw failure.getCause();
                                                                }
                                                            })),
                    args);
        }
    }

    @BeforeEach
    void database() throws Exception {
        String url = System.getenv("MATECLAW_ACCEPTANCE_JDBC_URL");
        external = url != null && !url.isBlank();
        if (external)
            assertTrue(
                    url.matches(
                            "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mateclaw_aq_acceptance_[a-z0-9]+(?:\\?.*)?"),
                    "Only disposable loopback acceptance schemas are allowed");
        else url = "jdbc:h2:mem:sql_listing_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url,
                        external ? System.getenv("MATECLAW_ACCEPTANCE_JDBC_USER") : "sa",
                        external ? System.getenv("MATECLAW_ACCEPTANCE_JDBC_PASSWORD") : "");
        try (var connection = source.getConnection();
                var tables =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        null,
                                        "%",
                                        new String[] {"TABLE"})) {
            while (tables.next())
                if (!"INFORMATION_SCHEMA".equalsIgnoreCase(tables.getString("TABLE_SCHEM")))
                    fail("Acceptance schema must be empty before fixture creation");
        }
        ownsTables = true;
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/"
                                        + (external ? "mysql" : "h2")
                                        + "/V211__presales_projects.sql"))
                .execute(source);
        jdbc = new LedgerJdbc(source);
        access = mock(PresalesAccess.class);
        when(access.require("scope", "viewer")).thenReturn("viewer");
        service =
                new PresalesService(
                        null,
                        new PresalesProjectRepository(jdbc),
                        json,
                        access,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        PresalesCommandTestSupport.fence(),
                        mock(PresalesSourceAuthorization.class),
                        mock(vip.mate.presales.repository.PresalesRenderTaskRepository.class),
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()));
        queryService =
                new PresalesProjectQueryService(new PresalesProjectRepository(jdbc), json, access);
        for (int i = 0; i < 120; i++) {
            String id = String.format(java.util.Locale.ROOT, "%04d", i);
            var body =
                    json.createObjectNode()
                            .put("id", id)
                            .put("name", "Project " + id)
                            .put("customer", "Customer")
                            .put("status", "ACTIVE")
                            .put("ownerId", "owner")
                            .put("version", 1);
            for (String key :
                    List.of("releases", "solutions", "baselines", "requirements", "clarifications"))
                body.putArray(key);
            body.withArray("requirements").addObject().put("original", "正文 Ω ".repeat(512));
            jdbc.update(
                    "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                    id,
                    "scope",
                    1,
                    "Project " + id,
                    "ACTIVE",
                    json.writeValueAsString(body));
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/" + (external ? "mysql" : "h2"))
                .baselineOnMigrate(true)
                .baselineVersion("216")
                .target("219")
                .load()
                .migrate();
        jdbc.reads.clear();
        jdbc.rows = 0;
    }

    @AfterEach
    void close() throws java.sql.SQLException {
        if (!ownsTables) return;
        if (external)
            for (String table :
                    List.of(
                            "mate_presales_artifact",
                            "mate_presales_revision",
                            "mate_presales_operation",
                            "mate_presales_project",
                            "flyway_schema_history")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        else {
            // JdbcTemplate probes warnings in DEBUG after SHUTDOWN has closed H2.
            try (var connection = jdbc.getDataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    @Test
    void deniedViewerPrecedesPaginationAndDoesNotReadSql() {
        var denied = new vip.mate.semantic.web.SemanticApiException(403, "FORBIDDEN", "denied");
        when(access.require("scope", "viewer")).thenThrow(denied);
        for (int[] paging : List.of(new int[] {1, 20}, new int[] {0, 0})) {
            assertSame(
                    denied,
                    assertThrows(
                            vip.mate.semantic.web.SemanticApiException.class,
                            () ->
                                    queryService.list(
                                            "scope", null, null, null, null, paging[0],
                                            paging[1])));
            assertTrue(jdbc.reads.isEmpty(), jdbc.reads.toString());
        }
    }

    @Test
    void invalidPaginationDoesNotReadSqlAfterViewerCheck() {
        for (int[] paging : List.of(new int[] {0, 20}, new int[] {1, 0}, new int[] {1, 101})) {
            var error =
                    assertThrows(
                            PresalesRejected.class,
                            () ->
                                    queryService.list(
                                            "scope", null, null, null, null, paging[0], paging[1]));
            assertEquals(400, error.status());
            assertEquals("INVALID_REQUEST", error.code());
            assertEquals("Invalid pagination", error.getMessage());
            assertTrue(jdbc.reads.isEmpty(), jdbc.reads.toString());
        }
        verify(access, times(3)).require("scope", "viewer");
    }

    @Test
    void listReadsOnlyTheRequestedSummaryRowsWithoutBodyHistory() {
        var result = queryService.list("scope", null, null, null, null, 3, 7);
        assertEquals(120, result.total());
        assertEquals(7, result.items().size());
        assertEquals("0014", result.items().getFirst().path("id").asText());
        assertFalse(result.items().getFirst().has("requirements"));
        assertAll(
                () ->
                        assertFalse(
                                jdbc.reads.stream().anyMatch(sql -> sql.contains("body_json")),
                                jdbc.reads.toString()),
                () -> assertTrue(jdbc.rows <= 7, "JDBC rows actually materialized: " + jdbc.rows));
        assertEquals(
                1,
                jdbc.reads.size(),
                "One statement must bind total, faults and page to the same read");
    }

    @Test
    void emptyLargePageReturnsTotalWithoutMaterializingAllProjects() {
        var result = queryService.list("scope", null, null, null, null, Integer.MAX_VALUE, 100);
        assertEquals(120, result.total());
        assertTrue(result.items().isEmpty());
        assertTrue(jdbc.rows <= 1, "JDBC rows actually materialized: " + jdbc.rows);
        assertFalse(
                jdbc.reads.stream().anyMatch(sql -> sql.contains("body_json")),
                jdbc.reads.toString());
    }

    private void assertWireEquals(PresalesDtos.Page expected, PresalesDtos.Page actual)
            throws Exception {
        // JSON trees may choose IntNode versus LongNode for the same integer wire value.
        assertEquals(json.writeValueAsString(expected), json.writeValueAsString(actual));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode project(String id, String name) {
        var body =
                json.createObjectNode()
                        .put("id", id)
                        .put("name", name)
                        .put("customer", "")
                        .put("status", "ACTIVE")
                        .put("ownerId", "special")
                        .put("version", 1);
        for (String key : PresalesListingProjectionV1.SOURCE_COLLECTIONS) body.putArray(key);
        return body;
    }

    private void insert(String id, String scope, String orderName, String body) {
        new PresalesProjectRepository(jdbc)
                .insert(
                        new PresalesProjectRepository.ProjectRow(
                                id,
                                scope,
                                1,
                                orderName,
                                "DB_ONLY",
                                body,
                                PresalesListingProjectionV1.fromBody(body, json)));
    }

    private PresalesDtos.Page shadow(
            String scope, String q, String status, String owner, String stage, int page, int size) {
        var bodies =
                jdbc.query(
                        "SELECT body_json FROM mate_presales_project WHERE workspace_id=? ORDER BY name,id",
                        (rs, n) -> {
                            try {
                                return (com.fasterxml.jackson.databind.node.ObjectNode)
                                        json.readTree(rs.getString(1));
                            } catch (Exception e) {
                                throw new IllegalStateException("legacy decode", e);
                            }
                        },
                        scope);
        return PresalesProjectListing.page(
                bodies.stream(),
                new PresalesProjectListing.Criteria(q, status, owner, stage, page, size),
                PresalesListingProjectionV1.SOURCE_COLLECTIONS);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource(
            "vip.mate.presales.PresalesProjectListingTest#substringCases")
    void sqlMatchesDurableLegacyUtf16Search(String name, String query, boolean matches)
            throws Exception {
        // Escaped fixtures preserve isolated UTF-16 units through every JDBC driver.
        var escaped = json.copy();
        escaped.getFactory()
                .configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, true);
        String body = escaped.writeValueAsString(project("edge", name));
        insert("edge", "scope", "Order", body);
        var expected = shadow("scope", query, null, "special", null, 1, 20);
        var actual = queryService.list("scope", query, null, "special", null, 1, 20);
        assertEquals(matches ? 1 : 0, expected.total());
        assertWireEquals(expected, actual);
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id='edge'",
                        String.class));
    }

    @Test
    void exactFiltersNullExtensionsAndDatabaseOrderMatchLegacy() throws Exception {
        var a = project("edge-a", "body Z").put("ownerId", "Owner ").putNull("customer");
        a.putObject("extension").put("opaque", "原文 Ω");
        a.withArray("solutions").addObject().put("version", 9);
        a.withArray("solutions").addObject().put("version", 2);
        a.withArray("clarifications").addObject().put("status", "answered");
        a.withArray("clarifications").addObject().put("status", "ANSWERED");
        var b = a.deepCopy().put("id", "edge-b").put("name", "body A");
        insert("edge-b", "scope", "same-order", json.writeValueAsString(b));
        insert("edge-a", "scope", "same-order", json.writeValueAsString(a));
        insert("foreign", "other", "first", json.writeValueAsString(a.put("id", "foreign")));
        for (String[] filters :
                List.of(
                        new String[] {null, "ACTIVE", "Owner ", "SOLUTION"},
                        new String[] {"null", null, "Owner ", null},
                        new String[] {null, "active", "Owner ", null},
                        new String[] {null, null, "Owner", null},
                        new String[] {null, null, "owner ", null},
                        new String[] {null, null, "Owner ", "solution"},
                        new String[] {null, null, "Owner ", "SOLUTION "},
                        new String[] {null, " \t", "Owner ", " \t"})) {
            assertWireEquals(
                    shadow("scope", filters[0], filters[1], filters[2], filters[3], 1, 1),
                    queryService.list(
                            "scope", filters[0], filters[1], filters[2], filters[3], 1, 1));
        }
        var result = queryService.list("scope", null, "ACTIVE", "Owner ", "SOLUTION", 1, 20);
        assertEquals(
                List.of("edge-a", "edge-b"),
                result.items().stream().map(x -> x.path("id").asText()).toList());
        assertEquals(2, result.items().getFirst().path("latestSolutionVersion").asInt());
        assertEquals(1, result.items().getFirst().path("openClarificationCount").asInt());
        assertTrue(result.items().getFirst().path("customer").isNull());
        assertEquals("原文 Ω", result.items().getFirst().path("extension").path("opaque").asText());
    }

    @Test
    void decodeFaultIsGlobalToScopeEvenOutsideFiltersAndPage() throws Exception {
        insert("bad", "scope", "zz-last", "{");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                queryService.list(
                                        "scope",
                                        "no matches",
                                        null,
                                        null,
                                        null,
                                        Integer.MAX_VALUE,
                                        100));
        assertTrue(failure.getMessage().startsWith("Invalid project body"));
        when(access.require("other", "viewer")).thenReturn("viewer");
        assertEquals(0, queryService.list("other", "no matches", null, null, null, 1, 20).total());
    }

    @Test
    void firstFaultFollowsDatabaseOrderBeforeLaterDecodeAndBeforePage() throws Exception {
        var bad = project("bad-stage", "fault").put("releases", "wrong");
        insert("bad-stage", "scope", "A-first", json.writeValueAsString(bad));
        insert("bad-decode", "scope", "Z-last", "{");
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                queryService.list(
                                        "scope",
                                        null,
                                        null,
                                        null,
                                        "DISCOVERY",
                                        Integer.MAX_VALUE,
                                        100));
        assertTrue(failure.getMessage().contains("releases"));
        jdbc.update("UPDATE mate_presales_project SET name='0-first' WHERE id='bad-decode'");
        assertThrows(
                IllegalStateException.class,
                () -> queryService.list("scope", null, null, null, "DISCOVERY", 1, 20));
    }

    @Test
    void summaryFaultOnlyAppliesAfterBaseAndStageFilters() throws Exception {
        var bad =
                project("bad-summary", "fault")
                        .put("status", "ARCHIVED")
                        .put("clarifications", "wrong");
        insert("bad-summary", "scope", "A-first", json.writeValueAsString(bad));
        assertEquals(
                0, queryService.list("scope", null, null, "special", "DISCOVERY", 1, 20).total());
        assertEquals(0, queryService.list("scope", "absent", null, "special", null, 1, 20).total());
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                queryService.list(
                                        "scope",
                                        null,
                                        null,
                                        "special",
                                        "ARCHIVED",
                                        Integer.MAX_VALUE,
                                        100));
        assertTrue(failure.getMessage().contains("clarifications"));
    }

    @Test
    void malformedStageExcludedByBaseFiltersDoesNotFail() throws Exception {
        insert(
                "bad-stage",
                "scope",
                "A",
                json.writeValueAsString(project("bad-stage", "fault").put("releases", "wrong")));
        assertEquals(
                0, queryService.list("scope", "absent", null, null, "DISCOVERY", 1, 20).total());
        assertEquals(0, queryService.list("scope", null, "OTHER", null, null, 1, 20).total());
        assertThrows(
                IllegalArgumentException.class,
                () -> queryService.list("scope", null, null, null, null, 1, 20));
    }

    @Test
    void missingAndStaleFactsFailWithoutBodyFallbackAndOtherScopeCannotPoison() throws Exception {
        insert("foreign", "other", "first", "{");
        assertEquals(120, queryService.list("scope", null, null, null, null, 1, 20).total());
        for (String expression :
                List.of(
                        "listing_contract=NULL",
                        "listing_project_version=0",
                        "listing_summary_json=NULL")) {
            jdbc.update("UPDATE mate_presales_project SET " + expression + " WHERE id='0000'");
            jdbc.reads.clear();
            assertThrows(
                    IllegalStateException.class,
                    () -> queryService.list("scope", "absent", null, null, null, 1, 20));
            assertFalse(jdbc.reads.stream().anyMatch(x -> x.contains("body_json")));
            jdbc.update("UPDATE mate_presales_project SET listing_contract=NULL WHERE id='0000'");
            try (var connection = jdbc.getDataSource().getConnection()) {
                new db.migration.h2.V218__backfill_presales_listing_projection()
                        .migrate(
                                new org.flywaydb.core.api.migration.Context() {
                                    public java.sql.Connection getConnection() {
                                        return connection;
                                    }

                                    public org.flywaydb.core.api.configuration.Configuration
                                            getConfiguration() {
                                        return null;
                                    }
                                });
            }
        }
    }

    @Test
    void largeUnknownMetadataAndEncodedKeysRemainUntruncated() throws Exception {
        String owner = " Ω ".repeat(18000), opaque = "未知扩展 Ω ".repeat(20000);
        var p = project("large", "Large").put("ownerId", owner).put("customer", owner);
        p.putObject("extension").put("opaque", opaque);
        String body = json.writeValueAsString(p);
        insert("large", "scope", "Large", body);
        var result = queryService.list("scope", "Ω", null, owner, null, 1, 20);
        assertEquals(1, result.total());
        assertEquals(opaque, result.items().getFirst().path("extension").path("opaque").asText());
        assertEquals(owner, result.items().getFirst().path("ownerId").asText());
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id='large'",
                        String.class));
    }

    @Test
    void actualApplicationWritesListTheDurableBodyIncludingUtf16AndReplay() throws Exception {
        when(access.require("scope", "member")).thenReturn("actor");
        when(access.owner(eq("scope"), any(), eq("actor"))).thenReturn("writer");
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()));
        var request =
                new PresalesDtos.Create(
                        "x\ud800y", "Customer", null, null, null, null, 0L, "create-writer");
        var created = tx.execute(status -> service.create("scope", request));
        assertEquals(created, tx.execute(status -> service.create("scope", request)));
        for (String q : List.of("?", "\ud800", "x"))
            assertWireEquals(
                    shadow("scope", q, null, "writer", null, 1, 20),
                    queryService.list("scope", q, null, "writer", null, 1, 20));
        var command =
                new PresalesDtos.Command(
                        1L,
                        "manual-writer",
                        "UPDATE_PROJECT",
                        json.createObjectNode().put("name", "Renamed Ω"));
        var updated =
                tx.execute(
                        status -> service.command("scope", created.path("id").asText(), command));
        assertEquals(2, updated.path("version").asInt());
        assertWireEquals(
                shadow("scope", "renamed", null, "writer", null, 1, 20),
                queryService.list("scope", "renamed", null, "writer", null, 1, 20));
        String body =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        created.path("id").asText());
        var replay =
                tx.execute(
                        status -> service.command("scope", created.path("id").asText(), command));
        assertEquals(updated, replay);
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        created.path("id").asText()));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                        Integer.class,
                        created.path("id").asText()));
        // A failure after the body/projection CAS must roll both back with the caller transaction.
        jdbc.update(
                "INSERT INTO mate_presales_revision VALUES(?,3,'actor','conflict','{}',CURRENT_TIMESTAMP)",
                created.path("id").asText());
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () ->
                        tx.execute(
                                status ->
                                        service.command(
                                                "scope",
                                                created.path("id").asText(),
                                                new PresalesDtos.Command(
                                                        2L,
                                                        "rollback-writer",
                                                        "UPDATE_PROJECT",
                                                        json.createObjectNode()
                                                                .put("name", "must roll back")))));
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        created.path("id").asText()));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT listing_project_version FROM mate_presales_project WHERE id=?",
                        Integer.class,
                        created.path("id").asText()));
        assertWireEquals(
                shadow("scope", "renamed", null, "writer", null, 1, 20),
                queryService.list("scope", "renamed", null, "writer", null, 1, 20));
    }

    @Test
    void historicalRequestHashSurrogateCollisionIsExplicitlyCharacterized() throws Exception {
        when(access.require("scope", "member")).thenReturn("actor");
        when(access.owner(eq("scope"), any(), eq("actor"))).thenReturn("writer");
        var first =
                new PresalesDtos.Create(
                        "x\ud800y", "Customer", null, null, null, null, 0L, "legacy-hash");
        var different =
                new PresalesDtos.Create(
                        "x?y", "Customer", null, null, null, null, 0L, "legacy-hash");
        assertNotEquals(first, different);
        assertEquals(
                vip.mate.semantic.statement.StatementApplicationService.hash(
                        json.writeValueAsString(first)),
                vip.mate.semantic.statement.StatementApplicationService.hash(
                        json.writeValueAsString(different)));
        var created = service.create("scope", first);
        var conflict =
                assertThrows(
                        vip.mate.semantic.web.SemanticApiException.class,
                        () -> service.create("scope", different));
        assertEquals(409, conflict.status());
        assertEquals("OPERATION_CONFLICT", conflict.code());
        assertEquals(created, service.create("scope", first));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_operation WHERE operation_id='legacy-hash'",
                        Integer.class));
    }
}
