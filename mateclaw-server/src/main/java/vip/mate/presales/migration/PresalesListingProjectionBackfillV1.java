package vip.mate.presales.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import vip.mate.presales.PresalesListingProjectionV1;
import vip.mate.presales.PresalesListingProjectionV1.Projection;

/**
 * Frozen V218 algorithm. Future listing contracts need a new migration, never edits here. Flyway
 * owns the connection and transaction; this migration never commits business data.
 */
public abstract class PresalesListingProjectionBackfillV1 extends BaseJavaMigration {
    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final String ROW_COLUMNS =
            "id,workspace_id,version,body_json,listing_contract,listing_project_version";
    private static final String UPDATE =
            "UPDATE mate_presales_project SET listing_contract=?,listing_project_version=?,"
                    + "listing_name_key=?,listing_customer_key=?,listing_status_key=?,"
                    + "listing_owner_key=?,listing_stage_key=?,listing_summary_json=?,"
                    + "listing_decode_failure=?,listing_stage_failure=?,listing_summary_failure=?"
                    + " WHERE id=? AND workspace_id=? AND version=?";

    @Override
    public final Integer getChecksum() {
        CRC32 checksum = new CRC32();
        // Hash actual bytecode, including each algorithm-bearing member of the frozen closure.
        for (Class<?> type :
                List.of(
                        getClass(),
                        PresalesListingProjectionBackfillV1.class,
                        Row.class,
                        PresalesListingProjectionV1.class,
                        Projection.class)) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "Missing V218 checksum class: " + type.getName());
                }
                checksum.update(input.readAllBytes());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot checksum V218 migration", failure);
            }
        }
        return (int) checksum.getValue();
    }

    @Override
    public final void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        ObjectMapper mapper =
                new ObjectMapper().enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        String cursor = null;
        while (true) {
            List<Row> batch = readBatch(connection, cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (Row row : batch) {
                backfill(connection, mapper, row);
            }
            cursor = batch.getLast().id();
        }
        // Missing or stale derived facts must fail the migration, including skipped ready rows.
        try (var statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM mate_presales_project WHERE listing_contract"
                                        + " IS NULL OR listing_contract<>1 OR listing_project_version"
                                        + " IS NULL OR listing_project_version<>version");
                var rows = statement.executeQuery()) {
            rows.next();
            if (rows.getLong(1) != 0) {
                throw new SQLException("V218 listing projection is incomplete");
            }
        }
    }

    private static List<Row> readBatch(Connection connection, String cursor) throws SQLException {
        String sql =
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM mate_presales_project"
                        + (cursor == null ? "" : " WHERE id>?")
                        + " ORDER BY id LIMIT "
                        + BATCH_SIZE;
        List<Row> batch = new ArrayList<>(BATCH_SIZE);
        try (var statement = connection.prepareStatement(sql)) {
            if (cursor != null) {
                statement.setString(1, cursor);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    batch.add(readRow(rows));
                }
            }
        }
        return batch;
    }

    private static void backfill(Connection connection, ObjectMapper mapper, Row initial)
            throws SQLException {
        Row row = initial;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (row.ready()) {
                return;
            }
            Projection projection = PresalesListingProjectionV1.fromBody(row.body(), mapper);
            try (var statement = connection.prepareStatement(UPDATE)) {
                bind(statement, row, projection);
                int updated = statement.executeUpdate();
                if (updated == 1) {
                    Row readback = readExact(connection, row.id(), row.workspace());
                    if (readback.version() != row.version()
                            || !Objects.equals(readback.body(), row.body())
                            || !readback.ready()) {
                        throw new SQLException("V218 listing projection readback failed");
                    }
                    return;
                }
                if (updated != 0) {
                    throw new SQLException("V218 listing projection affected unexpected rows");
                }
            }
            row = readExact(connection, row.id(), row.workspace());
        }
        throw new SQLException("V218 listing projection concurrent update retry exhausted");
    }

    private static Row readExact(Connection connection, String id, String workspace)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT "
                                + ROW_COLUMNS
                                + " FROM mate_presales_project WHERE id=? AND workspace_id=?")) {
            statement.setString(1, id);
            statement.setString(2, workspace);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("V218 listing projection row no longer exists in scope");
                }
                return readRow(rows);
            }
        }
    }

    private static Row readRow(ResultSet rows) throws SQLException {
        return new Row(
                rows.getString("id"),
                rows.getString("workspace_id"),
                rows.getInt("version"),
                rows.getString("body_json"),
                rows.getObject("listing_contract", Integer.class),
                rows.getObject("listing_project_version", Integer.class));
    }

    private static void bind(PreparedStatement statement, Row row, Projection projection)
            throws SQLException {
        statement.setInt(1, projection.contractVersion());
        statement.setInt(2, row.version());
        statement.setString(3, projection.nameKey());
        statement.setString(4, projection.customerKey());
        statement.setString(5, projection.statusKey());
        statement.setString(6, projection.ownerKey());
        statement.setString(7, projection.stageKey());
        statement.setString(8, projection.summaryJson());
        statement.setString(9, projection.decodeFailure());
        statement.setString(10, projection.stageFailure());
        statement.setString(11, projection.summaryFailure());
        statement.setString(12, row.id());
        statement.setString(13, row.workspace());
        statement.setInt(14, row.version());
    }

    private record Row(
            String id,
            String workspace,
            int version,
            String body,
            Integer contract,
            Integer projected) {
        boolean ready() {
            return Objects.equals(contract, 1) && Objects.equals(projected, version);
        }
    }
}
