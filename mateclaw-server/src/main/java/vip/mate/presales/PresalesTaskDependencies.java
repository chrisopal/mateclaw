package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/** Exact inputs for new-data tasks; project CAS versions are not business dependencies. */
record PresalesTaskDependencies(
        int schemaVersion,
        List<Selection> selections,
        String semanticDigest,
        String sourcesDigest) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COLLECTIONS =
            List.of(
                    "requirements",
                    "clarifications",
                    "baselines",
                    "fitGaps",
                    "cases",
                    "solutions",
                    "materials");
    private static final List<String> SEMANTIC_FIELDS =
            List.of("name", "customer", "industry", "goal");

    record ObjectRevision(String id, long revision, String digest) {}

    record Selection(String collection, boolean allMembers, List<ObjectRevision> objects) {
        Selection {
            objects = List.copyOf(objects);
        }
    }

    PresalesTaskDependencies {
        selections = List.copyOf(selections);
    }

    static boolean newProject(ObjectNode project) {
        return IntNode.valueOf(2).equals(project.path("storageVersion"));
    }

    static boolean hasManifest(ObjectNode snapshot) {
        return snapshot.has("taskDependencies")
                || snapshot.has("dependencyDigest")
                || IntNode.valueOf(2).equals(snapshot.path("schemaVersion"));
    }

    static void capture(ObjectNode project, ObjectNode snapshot) {
        var selections = new ArrayList<Selection>();
        for (String collection : COLLECTIONS) {
            boolean selected = collection.equals("solutions") && snapshot.has("targetSolutionId");
            JsonNode input =
                    collection.equals("materials")
                            ? project.path(collection)
                            : snapshot.path(collection);
            selections.add(
                    new Selection(collection, !selected, revisions(input, "TASK_SCOPE_CHANGED")));
        }
        var dependencies =
                new PresalesTaskDependencies(
                        2,
                        selections,
                        digest(snapshot.path("operational_record")),
                        digest(snapshot.path("sources")));
        snapshot.put("schemaVersion", 2);
        snapshot.set("taskDependencies", JSON.valueToTree(dependencies));
        snapshot.put("dependencyDigest", digest(snapshot.path("taskDependencies")));
    }

    /** Called before execution and again while the accepting transaction holds the project lock. */
    static void requireCurrent(ObjectNode project, ObjectNode snapshot) {
        if ("ARCHIVED".equals(project.path("status").asText()))
            throw PresalesModelAdapter.error(409, "PROJECT_ARCHIVED");
        var dependencies = read(snapshot);
        if (!dependencies.semanticDigest().equals(digest(semanticInputs(project)))) changed();
        for (var selection : dependencies.selections()) {
            JsonNode current = project.path(selection.collection());
            if (!selection.allMembers()) {
                var selected = JSON.createArrayNode();
                for (var expected : selection.objects()) {
                    JsonNode found = null;
                    for (var item : current) {
                        if (expected.id().equals(item.path("id").asText())) {
                            if (found != null) changed();
                            found = item;
                        }
                    }
                    if (found == null) changed();
                    selected.add(found);
                }
                current = selected;
            }
            if (!selection.objects().equals(revisions(current, "TASK_INPUT_CHANGED"))) changed();
        }
    }

    /** The scope digest binds the entire manifest to the immutable persisted task identity. */
    static String snapshotDigest(ObjectNode snapshot) {
        if (!hasManifest(snapshot)) return "";
        read(snapshot);
        return snapshot.path("dependencyDigest").asText();
    }

    private static PresalesTaskDependencies read(ObjectNode snapshot) {
        try {
            if (!IntNode.valueOf(2).equals(snapshot.path("schemaVersion"))
                    || !snapshot.path("taskDependencies").isObject()
                    || !IntNode.valueOf(2)
                            .equals(snapshot.path("taskDependencies").path("schemaVersion"))
                    || !digest(snapshot.path("taskDependencies"))
                            .equals(snapshot.path("dependencyDigest").asText()))
                throw new IllegalArgumentException();
            var value =
                    JSON.treeToValue(
                            snapshot.path("taskDependencies"), PresalesTaskDependencies.class);
            if (!canonical(JSON.valueToTree(value))
                            .toString()
                            .equals(canonical(snapshot.path("taskDependencies")).toString())
                    || value.schemaVersion() != 2
                    || value.selections().size() != COLLECTIONS.size()
                    || !digest(snapshot.path("operational_record")).equals(value.semanticDigest())
                    || !digest(snapshot.path("sources")).equals(value.sourcesDigest()))
                throw new IllegalArgumentException();
            for (int index = 0; index < COLLECTIONS.size(); index++) {
                var selection = value.selections().get(index);
                String collection = COLLECTIONS.get(index);
                boolean selected =
                        collection.equals("solutions") && snapshot.has("targetSolutionId");
                if (!collection.equals(selection.collection())
                        || selection.allMembers() == selected) throw new IllegalArgumentException();
                if (!collection.equals("materials")
                        && !selection
                                .objects()
                                .equals(revisions(snapshot.path(collection), "TASK_SCOPE_CHANGED")))
                    throw new IllegalArgumentException();
                var ids = new HashSet<String>();
                for (var object : selection.objects()) {
                    if (object.id() == null
                            || object.id().isBlank()
                            || !ids.add(object.id())
                            || object.revision() < 1
                            || object.revision() > PresalesProjectRevision.MAX_VALUE
                            || object.digest() == null
                            || !object.digest().matches("v2:[0-9a-f]{64}"))
                        throw new IllegalArgumentException();
                }
                if (selected
                        && (selection.objects().size() != 1
                                || !selection
                                        .objects()
                                        .getFirst()
                                        .id()
                                        .equals(snapshot.path("targetSolutionId").asText())))
                    throw new IllegalArgumentException();
            }
            return value;
        } catch (Exception invalid) {
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        }
    }

    private static List<ObjectRevision> revisions(JsonNode items, String error) {
        if (items.isMissingNode() || items.isNull()) return List.of();
        if (!items.isArray()) throw PresalesModelAdapter.error(409, error);
        var values = new ArrayList<ObjectRevision>();
        var ids = new HashSet<String>();
        for (var item : items) {
            String id = item.path("id").asText();
            Long revision = PresalesProjectRevision.positiveRevision(item.path("version"));
            if (!item.isObject()
                    || !item.path("id").isTextual()
                    || id.isBlank()
                    || !ids.add(id)
                    || revision == null) throw PresalesModelAdapter.error(409, error);
            values.add(new ObjectRevision(id, revision, digest(item)));
        }
        return List.copyOf(values);
    }

    private static ObjectNode semanticInputs(ObjectNode project) {
        var value = JSON.createObjectNode();
        for (String field : SEMANTIC_FIELDS) value.set(field, project.path(field));
        return value;
    }

    private static String digest(JsonNode value) {
        return PresalesRequestHashV2.digest(canonical(value).toString());
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var sorted = JSON.createObjectNode();
            var fields = new TreeSet<String>();
            value.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) sorted.set(field, canonical(value.path(field)));
            return sorted;
        }
        if (value.isArray()) {
            var ordered = JSON.createArrayNode();
            for (var item : value) ordered.add(canonical(item));
            return ordered;
        }
        return value;
    }

    private static void changed() {
        throw PresalesModelAdapter.error(409, "TASK_INPUT_CHANGED");
    }
}
