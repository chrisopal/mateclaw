package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/** Legacy project-item revisions. The caller owns authorization and persistence. */
final class PresalesProjectItems {
    private PresalesProjectItems() {}

    static void saveItem(
            ObjectNode p, String collection, ObjectNode v, String actor, boolean immutable) {
        var items = p.withArray(collection);
        String requested = v.path("id").asText();
        int version = 1;
        if (!requested.isBlank()) {
            var old = find(p, collection, requested);
            version = old.path("version").asInt() + 1;
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
        throw new Rejected(404, "NOT_FOUND", collection + " item not found");
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

    private static Rejected bad(String message) {
        return new Rejected(400, "INVALID_REQUEST", message);
    }

    static final class Rejected extends RuntimeException {
        private final int status;
        private final String code;

        Rejected(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }
}
