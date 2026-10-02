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
        }
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
