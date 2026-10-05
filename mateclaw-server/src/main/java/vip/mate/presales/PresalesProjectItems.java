package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/** Legacy project-item revisions. The caller owns authorization and persistence. */
final class PresalesProjectItems {
    private PresalesProjectItems() {}

    /** Historical integer text is supported; invalid revisions never become a matching default. */
    static Integer positiveRevision(JsonNode value) {
        if (value == null) return null;
        int revision;
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            revision = value.intValue();
        } else if (value.isTextual()) {
            try {
                revision = Integer.parseInt(value.textValue().trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        } else {
            return null;
        }
        return revision > 0 ? revision : null;
    }

    static boolean matchesRevision(JsonNode value, int revision) {
        Integer exact = positiveRevision(value);
        return exact != null && exact == revision;
    }

    static int nextRevision(JsonNode current) {
        Integer revision = positiveRevision(current);
        if (revision == null)
            throw new PresalesRejected(409, "VERSION_CONFLICT", "Stored version is invalid");
        return nextRevision(revision);
    }

    static int nextRevision(int current) {
        if (current < 1)
            throw new PresalesRejected(409, "VERSION_CONFLICT", "Stored version is invalid");
        if (current == Integer.MAX_VALUE)
            throw new PresalesRejected(409, "VERSION_EXHAUSTED", "Version limit reached");
        return current + 1;
    }

    static void requireBoundGraph(ObjectNode p, String graph) {
        boolean found = false;
        for (var m : p.withArray("materials"))
            if (graph.equals(m.path("graphId").asText())) found = true;
        if (graph.isBlank() || !found) throw bad("Evidence graph must be bound to this project");
    }

    static void saveItem(
            ObjectNode p, String collection, ObjectNode v, String actor, boolean immutable) {
        var items = p.withArray(collection);
        String requested = v.path("id").asText();
        int version = 1;
        if (!requested.isBlank()) {
            var old = find(p, collection, requested);
            version = nextRevision(old.path("version"));
            if (immutable) v.put("previousId", requested);
            else
                for (int i = 0; i < items.size(); i++)
                    if (requested.equals(items.get(i).path("id").asText())) {
                        items.remove(i);
                        break;
                    }
        }
        v.put("id", immutable || requested.isBlank() ? UUID.randomUUID().toString() : requested)
                .put("version", version)
                .put("authorId", actor)
                .put("createdAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        items.add(v);
    }

    static ObjectNode find(ObjectNode p, String collection, String id) {
        for (var node : p.withArray(collection))
            if (id.equals(node.path("id").asText())) return (ObjectNode) node;
        throw new PresalesRejected(404, "NOT_FOUND", collection + " item not found");
    }

    static void text(String s, String label, int max) {
        if (s == null || s.isBlank() || s.length() > max)
            throw bad(label + " required, max " + max + " characters");
    }

    static void enumValue(ObjectNode v, String key, Set<String> allowed, String fallback) {
        String value = v.path(key).asText(fallback);
        if (!allowed.contains(value)) throw bad("Invalid " + key);
        v.put(key, value);
    }

    private static PresalesRejected bad(String message) {
        return new PresalesRejected(400, "INVALID_REQUEST", message);
    }
}
