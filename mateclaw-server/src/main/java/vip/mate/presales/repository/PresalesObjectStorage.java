package vip.mate.presales.repository;

import static vip.mate.presales.repository.PresalesObjectCodec.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Independent current pointers and append-only revisions, participating in the caller transaction.
 */
final class PresalesObjectStorage {
    private final JdbcTemplate jdbc;

    PresalesObjectStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Key(String kind, String parent, String id) {}

    private record Current(long revision, ObjectNode body) {}

    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("V2 object writes require the caller transaction");
    }

    /** Lock before touching objects: a losing aggregate CAS must not write object revisions. */
    boolean lockVersion(String scope, String project, long expected) {
        requireTransaction();
        return !jdbc.queryForList(
                        "SELECT id FROM mate_presales_project WHERE workspace_id=? AND id=? AND version=? FOR UPDATE",
                        String.class,
                        scope,
                        project,
                        expected)
                .isEmpty();
    }

    String persist(String scope, String project, String body) {
        requireTransaction();
        ObjectNode source = object(body);
        if (!scope.equals(source.path("workspaceId").asText()) || !project.equals(id(source)))
            throw new IllegalStateException("V2 storage scope mismatch");
        if (source.has(OBJECT_REFS)) throw new IllegalStateException("V2 input must be expanded");
        Map<Key, Current> current = current(scope, project);
        var retained = new HashSet<Key>();
        ObjectNode metadata = source.deepCopy();
        ObjectNode refs = metadata.putObject(OBJECT_REFS);
        for (String kind : COLLECTIONS) {
            JsonNode collection = metadata.remove(kind);
            if (collection == null) continue;
            if (!collection.isArray())
                throw new IllegalStateException("V2 collection must be an array");
            var members = refs.putArray(kind);
            for (var item : collection) {
                if (!(item instanceof ObjectNode value))
                    throw new IllegalStateException("V2 object required");
                String itemId = id(item);
                ObjectNode stored = value.deepCopy();
                if (stored.has(SECTION_REFS))
                    throw new IllegalStateException("V2 input must be expanded");
                if ("solutions".equals(kind) && stored.has("sections")) {
                    JsonNode sections = stored.remove("sections");
                    if (!sections.isArray())
                        throw new IllegalStateException("V2 sections must be an array");
                    var sectionRefs = stored.putArray(SECTION_REFS);
                    int index = 0;
                    for (var section : sections) {
                        if (!(section instanceof ObjectNode sectionBody))
                            throw new IllegalStateException("V2 section object required");
                        // Existing public sections have no mandatory id. Their position is the
                        // stable
                        // storage identity until the public contract supplies an explicit id.
                        String sectionId =
                                section.has("id") ? "id:" + id(section) : "position:" + index;
                        index++;
                        Key key = new Key("solutionSections", itemId, sectionId);
                        sectionRefs.add(
                                reference(
                                        sectionId,
                                        save(scope, project, key, sectionBody, current, retained)));
                    }
                }
                Key key = new Key(kind, "", itemId);
                members.add(
                        reference(itemId, save(scope, project, key, stored, current, retained)));
            }
        }
        for (Key key : current.keySet())
            if (!retained.contains(key))
                jdbc.update(
                        "DELETE FROM mate_presales_object WHERE workspace_id=? AND project_id=? AND object_kind=? AND parent_id=? AND object_id=?",
                        scope,
                        project,
                        key.kind(),
                        key.parent(),
                        key.id());
        return encode(metadata);
    }

    private Map<Key, Current> current(String scope, String project) {
        var current = new LinkedHashMap<Key, Current>();
        jdbc.query(
                "SELECT c.object_kind,c.parent_id,c.object_id,c.storage_revision,r.body_json"
                        + " FROM mate_presales_object c LEFT JOIN mate_presales_object_revision r"
                        + " ON r.workspace_id=c.workspace_id AND r.project_id=c.project_id"
                        + " AND r.object_kind=c.object_kind AND r.parent_id=c.parent_id"
                        + " AND r.object_id=c.object_id AND r.storage_revision=c.storage_revision"
                        + " WHERE c.workspace_id=? AND c.project_id=? FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        row -> {
                            String body = row.getString(5);
                            if (body == null)
                                throw new IllegalStateException(
                                        "Missing V2 current object revision");
                            current.put(
                                    new Key(row.getString(1), row.getString(2), row.getString(3)),
                                    new Current(row.getLong(4), object(body)));
                        },
                scope,
                project);
        return current;
    }

    private long save(
            String scope,
            String project,
            Key key,
            ObjectNode body,
            Map<Key, Current> current,
            HashSet<Key> retained) {
        if (!retained.add(key)) throw new IllegalStateException("Duplicate V2 object identity");
        Current old = current.get(key);
        if (old != null && old.body().equals(object(encode(body)))) return old.revision();
        long max =
                old == null
                        ? jdbc
                                .queryForList(
                                        "SELECT storage_revision FROM mate_presales_object_revision"
                                                + " WHERE workspace_id=? AND project_id=? AND object_kind=?"
                                                + " AND parent_id=? AND object_id=?"
                                                + " ORDER BY storage_revision DESC LIMIT 1 FOR UPDATE",
                                        Long.class,
                                        scope,
                                        project,
                                        key.kind(),
                                        key.parent(),
                                        key.id())
                                .stream()
                                .findFirst()
                                .orElse(0L)
                        : old.revision();
        long revision = Math.addExact(max, 1);
        jdbc.update(
                "INSERT INTO mate_presales_object_revision(workspace_id,project_id,object_kind,parent_id,object_id,storage_revision,body_json) VALUES(?,?,?,?,?,?,?)",
                scope,
                project,
                key.kind(),
                key.parent(),
                key.id(),
                revision,
                encode(body));
        if (old == null)
            jdbc.update(
                    "INSERT INTO mate_presales_object(workspace_id,project_id,object_kind,parent_id,object_id,storage_revision) VALUES(?,?,?,?,?,?)",
                    scope,
                    project,
                    key.kind(),
                    key.parent(),
                    key.id(),
                    revision);
        else if (jdbc.update(
                        "UPDATE mate_presales_object SET storage_revision=? WHERE workspace_id=? AND project_id=? AND object_kind=? AND parent_id=? AND object_id=? AND storage_revision=?",
                        revision,
                        scope,
                        project,
                        key.kind(),
                        key.parent(),
                        key.id(),
                        old.revision())
                != 1) throw new IllegalStateException("V2 object revision conflict");
        return revision;
    }

    String expand(String scope, String project, String encoded) {
        return expand(scope, project, encoded, false);
    }

    /** Locked manifests must resolve their revisions with the same current-read semantics. */
    String expand(String scope, String project, String encoded, boolean currentRead) {
        if (!isV2(encoded)) return encoded;
        ObjectNode metadata = object(encoded);
        if (!scope.equals(metadata.path("workspaceId").asText()) || !project.equals(id(metadata)))
            throw new IllegalStateException("V2 stored scope mismatch");
        JsonNode refs = metadata.remove(OBJECT_REFS);
        if (!(refs instanceof ObjectNode))
            throw new IllegalStateException("Missing V2 object references");
        for (String kind : COLLECTIONS) {
            if (metadata.has(kind))
                throw new IllegalStateException("V2 aggregate mirror forbidden");
            if (!refs.has(kind)) continue;
            if (!refs.path(kind).isArray())
                throw new IllegalStateException("Invalid V2 references");
            var values = metadata.putArray(kind);
            for (var ref : refs.path(kind)) {
                String itemId = id(ref);
                ObjectNode value =
                        read(scope, project, new Key(kind, "", itemId), revision(ref), currentRead);
                if (!itemId.equals(id(value)))
                    throw new IllegalStateException("V2 object identity mismatch");
                if ("solutions".equals(kind) && value.has(SECTION_REFS)) {
                    var sections = value.remove(SECTION_REFS);
                    if (!sections.isArray() || value.has("sections"))
                        throw new IllegalStateException("Invalid V2 solution references");
                    var expanded = value.putArray("sections");
                    for (var section : sections)
                        expanded.add(
                                read(
                                        scope,
                                        project,
                                        new Key("solutionSections", itemId, id(section)),
                                        revision(section),
                                        currentRead));
                }
                values.add(value);
            }
        }
        return encode(metadata);
    }

    private ObjectNode read(
            String scope, String project, Key key, long revision, boolean currentRead) {
        return jdbc
                .query(
                        "SELECT body_json FROM mate_presales_object_revision WHERE workspace_id=? AND project_id=? AND object_kind=? AND parent_id=? AND object_id=? AND storage_revision=?"
                                + (currentRead ? " FOR UPDATE" : ""),
                        (row, n) -> object(row.getString(1)),
                        scope,
                        project,
                        key.kind(),
                        key.parent(),
                        key.id(),
                        revision)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing pinned V2 object revision"));
    }

    /**
     * Capture the exact already-persisted project envelope, never the caller's growing aggregate.
     */
    String snapshot(String scope, String project, String body) {
        if (!isV2(body)) return body;
        requireTransaction();
        String stored =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE workspace_id=? AND id=? FOR UPDATE",
                        String.class,
                        scope,
                        project);
        if (!isV2(stored) || !object(expand(scope, project, stored, true)).equals(object(body)))
            throw new IllegalStateException("V2 snapshot does not match persisted objects");
        return stored;
    }

    String expandSnapshot(String scope, String body) {
        if (!isV2(body)) return body;
        ObjectNode snapshot = object(body);
        return expand(scope, id(snapshot), body, true);
    }
}
