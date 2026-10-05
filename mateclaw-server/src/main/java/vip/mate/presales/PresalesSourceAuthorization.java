package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import vip.mate.semantic.source.SourceGovernanceReadService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiSourceReadService;
import vip.mate.workspace.core.service.ProjectSourceAccess;

/** Source policy for current project materials and immutable historical provenance. */
@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesSourceAuthorization {
    private final ObjectMapper json;
    private final WikiKnowledgeBaseService wiki;
    private final ProjectSourceAccess sourceAccess;
    private final WikiSourceReadService sources;
    private final SourceGovernanceReadService governance;

    public PresalesSourceAuthorization(
            ObjectMapper json,
            WikiKnowledgeBaseService wiki,
            ProjectSourceAccess sourceAccess,
            WikiSourceReadService sources,
            SourceGovernanceReadService governance) {
        this.json = json;
        this.wiki = wiki;
        this.sourceAccess = sourceAccess;
        this.sources = sources;
        this.governance = governance;
    }

    void authorizeMaterials(String scope, ObjectNode p) {
        for (var m : p.withArray("materials")) {
            var kb = wiki.getById(parseId(m.path("kbId").asText()));
            if (kb == null
                    || kb.getWorkspaceId() == null
                    || !scope.equals(kb.getWorkspaceId().toString())
                    || (kb.getDeleted() != null && kb.getDeleted() != 0))
                throw new Denied(
                        403, "MATERIAL_UNAVAILABLE", "Project material access was revoked");
            authorizeEmployeeKb(scope, p, m.path("kbId").asText());
        }
        for (var baseline : p.withArray("baselines"))
            for (var ref : baseline.path("references"))
                for (var source : ref.path("sources")) {
                    authorizeHistoricalSource(scope, p, source.path("sourceRef").asText());
                    if (governance.isWithdrawn(
                            ref.path("graphId").asText(), source.path("sourceRef").asText()))
                        throw new Denied(
                                403, "SOURCE_UNAVAILABLE", "Historical evidence source withdrawn");
                }
        for (var task : p.withArray("tasks"))
            for (var source : task.path("contextSnapshot").path("sources")) {
                authorizeHistoricalSource(scope, p, source.path("sourceRef").asText());
                String graph = source.path("graphId").asText();
                if (!graph.isBlank()
                        && governance.isWithdrawn(graph, source.path("sourceRef").asText()))
                    throw new Denied(403, "SOURCE_UNAVAILABLE", "Task source withdrawn");
            }
    }

    void authorizeReleaseSources(String scope, ObjectNode project) {
        Set<String> boundKbs = new HashSet<>();
        for (var material : project.path("materials")) boundKbs.add(material.path("kbId").asText());
        for (var release : project.path("releases")) {
            JsonNode snapshot = release.path("handoffSnapshot");
            // Legacy releases without frozen provenance retain existing read gates.
            if (!snapshot.isObject()) continue;
            if (!project.path("agentId").asText().isBlank())
                for (var material : snapshot.path("materials"))
                    if (!boundKbs.contains(material.path("kbId").asText()))
                        throw new Denied(
                                403,
                                "SOURCE_UNAVAILABLE",
                                "Release source is no longer bound to the project");
            // Project a read-only source view; never modify the frozen snapshot or rerender
            // artifacts.
            ObjectNode historical = json.createObjectNode();
            historical.put("agentId", project.path("agentId").asText());
            historical.set("materials", snapshot.path("materials").deepCopy());
            var baselines = historical.putArray("baselines");
            if (snapshot.path("baseline").isObject())
                baselines.add(snapshot.path("baseline").deepCopy());
            var references = baselines.addObject().putArray("references");
            for (var ref : snapshot.path("sourceRefs")) {
                if (ref.isObject() && ref.path("sources").isArray()) {
                    references.add(ref.deepCopy());
                    continue;
                }
                String sourceId = ref.isTextual() ? ref.asText() : ref.path("sourceRef").asText();
                if (sourceId.isBlank())
                    throw new Denied(
                            403, "SOURCE_UNAVAILABLE", "Release source provenance is unavailable");
                authorizeHistoricalSource(scope, project, sourceId);
                // Scalar clarification refs inherit the frozen material graph scopes.
                for (var material : snapshot.path("materials")) {
                    var reference =
                            references
                                    .addObject()
                                    .put("graphId", material.path("graphId").asText());
                    reference.putArray("sources").addObject().put("sourceRef", sourceId);
                }
            }
            authorizeMaterials(scope, historical);
            authorizeFrozenFitEvidence(scope, project, snapshot);
        }
    }

    private void authorizeFrozenFitEvidence(String scope, ObjectNode project, JsonNode snapshot) {
        // Missing legacy fit collections are not reconstructed. Artifact reads retain live gates.
        if (snapshot.path("fitGaps").isMissingNode()) return;
        if (!snapshot.path("fitGaps").isArray())
            throw new Denied(403, "SOURCE_UNAVAILABLE", "Frozen fit provenance is unavailable");
        for (var fit : snapshot.path("fitGaps")) {
            if ("UNKNOWN".equals(fit.path("status").asText())) continue;
            if (!supportedFitEvidence(fit))
                throw new Denied(403, "SOURCE_UNAVAILABLE", "Frozen fit provenance is unavailable");
            String graph = fit.path("graphId").asText();
            String kb = "";
            for (var material : snapshot.path("materials")) {
                if (!graph.equals(material.path("graphId").asText())) continue;
                String candidate = material.path("kbId").asText();
                if (!kb.isBlank() && !kb.equals(candidate))
                    throw new Denied(
                            403, "SOURCE_UNAVAILABLE", "Frozen fit graph binding is ambiguous");
                kb = candidate;
            }
            if (kb.isBlank())
                throw new Denied(403, "SOURCE_UNAVAILABLE", "Frozen fit graph is not bound");
            for (var evidenceId : fit.path("evidenceIds")) {
                String source =
                        governance
                                .availableEvidenceSource(scope, graph, kb, evidenceId.asText())
                                .orElseThrow(
                                        () ->
                                                new Denied(
                                                        404,
                                                        "NOT_FOUND",
                                                        "Frozen evidence is unavailable"));
                authorizeHistoricalSource(scope, project, source);
            }
        }
    }

    private static boolean supportedFitEvidence(JsonNode fit) {
        if (!fit.isObject()
                || !nonblankText(fit.path("status"))
                || !Set.of("FIT", "CONFIG", "EXTEND", "PARTNER", "GAP", "UNKNOWN")
                        .contains(fit.path("status").asText())) return false;
        if ("UNKNOWN".equals(fit.path("status").asText())) return true;
        if (!nonblankText(fit.path("graphId"))
                || !fit.path("evidenceIds").isArray()
                || fit.path("evidenceIds").isEmpty()) return false;
        for (var id : fit.path("evidenceIds")) if (!nonblankText(id)) return false;
        return true;
    }

    /** Structural recognition only; the caller must still reauthorize and verify stored bytes. */
    static boolean hasFrozenSourceSnapshot(String scope, ObjectNode project, ObjectNode release) {
        String projectId = project.path("id").asText();
        JsonNode snapshot = release.path("handoffSnapshot");
        JsonNode frozenRelease = snapshot.path("release");
        JsonNode baseline = snapshot.path("baseline");
        if (!"PUBLISHED".equals(release.path("status").asText())
                || !snapshot.isObject()
                || !snapshot.path("schemaVersion").isIntegralNumber()
                || !snapshot.path("schemaVersion").canConvertToInt()
                || snapshot.path("schemaVersion").asInt() != 1
                || !sameText(snapshot.path("workspaceId"), scope)
                || !sameText(snapshot.path("engagementId"), projectId)
                || !sameText(snapshot.path("caseRef"), projectId)
                || !sameText(frozenRelease.path("id"), release.path("id").asText())
                || !sameText(
                        snapshot.path("solution").path("id"), release.path("solutionId").asText())
                || !sameText(baseline.path("id"), release.path("baselineId").asText())
                || !sameText(
                        snapshot.path("solution").path("baselineId"), baseline.path("id").asText())
                || !release.path("files").isArray()
                || !release.path("files").equals(frozenRelease.path("files"))
                || !snapshot.path("materials").isArray()
                || !snapshot.path("sourceRefs").isArray()
                || !baseline.path("references").isArray()) return false;
        for (var material : snapshot.path("materials"))
            if (!material.isObject()
                    || !nonblankText(material.path("kbId"))
                    || !material.path("graphId").isTextual()) return false;
        for (var ref : baseline.path("references"))
            if (!currentlyBoundGraph(project, ref.path("graphId").asText())
                    || !ref.isObject()
                    || !nonblankText(ref.path("graphId"))
                    || !sourceObjects(ref.path("sources"))) return false;
        for (var ref : snapshot.path("sourceRefs")) {
            if (ref.isTextual()) {
                if (!nonblankText(ref)) return false;
            } else if (ref.isObject() && ref.has("sources")) {
                if (!nonblankText(ref.path("graphId")) || !sourceObjects(ref.path("sources")))
                    return false;
            } else if (!ref.isObject() || !nonblankText(ref.path("sourceRef"))) return false;
        }
        if (!snapshot.path("fitGaps").isArray()
                || !snapshot.path("solution").path("fitGapRefs").isArray()) return false;
        Set<String> fitIds = new HashSet<>();
        for (var id : snapshot.path("solution").path("fitGapRefs"))
            if (!nonblankText(id) || !fitIds.add(id.asText())) return false;
        for (var fit : snapshot.path("fitGaps"))
            if (!nonblankText(fit.path("id"))
                    || !fitIds.remove(fit.path("id").asText())
                    || !supportedFitEvidence(fit)
                    || (!"UNKNOWN".equals(fit.path("status").asText())
                            && !currentlyBoundGraph(project, fit.path("graphId").asText())))
                return false;
        if (!fitIds.isEmpty()) return false;
        return true;
    }

    private static boolean currentlyBoundGraph(ObjectNode project, String graph) {
        if (graph.isBlank()) return false;
        for (var material : project.path("materials"))
            if (graph.equals(material.path("graphId").asText())) return true;
        return false;
    }

    private static boolean sourceObjects(JsonNode sources) {
        if (!sources.isArray()) return false;
        for (var source : sources)
            if (!source.isObject() || !nonblankText(source.path("sourceRef"))) return false;
        return true;
    }

    private static boolean nonblankText(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank();
    }

    private static boolean sameText(JsonNode value, String expected) {
        return !expected.isBlank() && value.isTextual() && expected.equals(value.asText());
    }

    private void authorizeHistoricalSource(String scope, ObjectNode project, String sourceId) {
        currentSource(scope, sourceId, "", false);
        String employeeId = project.path("agentId").asText();
        if (!employeeId.isBlank()
                && !sourceAccess.canEmployeeReadSource(scope, employeeId, sourceId))
            throw new Denied(
                    403, "SOURCE_UNAVAILABLE", "Project employee source access was revoked");
    }

    void authorizeEmployeeKb(String scope, ObjectNode project, String kbId) {
        String employeeId = project.path("agentId").asText();
        // Legacy human-only projects retain their existing Workspace/source authorization.
        if (!employeeId.isBlank() && !sourceAccess.canEmployeeReadKb(scope, employeeId, kbId))
            throw new Denied(
                    403, "SOURCE_UNAVAILABLE", "Project employee source access was revoked");
    }

    void currentSource(String scope, String sourceId, String digest, boolean checkDigest) {
        var source =
                sources.readInWorkspace(scope, sourceId)
                        .orElseThrow(
                                () ->
                                        new Denied(
                                                403,
                                                "SOURCE_UNAVAILABLE",
                                                "Referenced source access revoked"));
        if (checkDigest
                && !digest.equals(
                        PresalesArtifactRenderer.digest(
                                source.text().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            throw new Denied(
                    409,
                    "SOURCE_CHANGED",
                    "Source changed since semantic snapshot; review fresh evidence");
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            throw new Denied(400, "INVALID_REQUEST", "Invalid ID");
        }
    }

    /** Domain rejection; the application boundary preserves its legacy HTTP exception contract. */
    static final class Denied extends RuntimeException {
        private final int status;
        private final String code;

        Denied(int status, String code, String message) {
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
