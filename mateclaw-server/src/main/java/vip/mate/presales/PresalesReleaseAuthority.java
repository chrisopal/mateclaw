package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/** Captures every source used by candidate provenance, then fences and rereads it. */
final class PresalesReleaseAuthority {
    record Reference(String sourceId, String graphId) {}

    private final ProjectAuthorityFence fence;
    private final PresalesSourceAuthorization sources;
    private final PresalesReleaseAuthorization releases;
    private final PresalesAccess access;
    private final ObjectProvider<PresalesEmployeeRuntime> employees;

    PresalesReleaseAuthority(
            ProjectAuthorityFence fence,
            PresalesSourceAuthorization sources,
            PresalesReleaseAuthorization releases,
            PresalesAccess access,
            ObjectProvider<PresalesEmployeeRuntime> employees) {
        this.fence = fence;
        this.sources = sources;
        this.releases = releases;
        this.access = access;
        this.employees = employees;
    }

    ArrayNode lockAndCapture(String scope, String actor, ObjectNode project, ObjectNode solution) {
        var references = references(scope, project, solution);
        ArrayNode before = capture(scope, project, references);
        var bindings = new ArrayList<ProjectAuthorityFence.Source>();
        for (var pin : before)
            bindings.add(
                    new ProjectAuthorityFence.Source(
                            pin.path("kbId").asText(),
                            pin.path("sourceId").asText(),
                            pin.path("graphId").asText()));
        var kbs = new TreeSet<String>();
        var graphs = new TreeSet<String>();
        collectMaterials(project.path("materials"), kbs, graphs);
        for (var release : project.path("releases"))
            collectMaterials(release.path("handoffSnapshot").path("materials"), kbs, graphs);
        String employee = project.path("agentId").asText();
        if (!fence.lockForCommand(scope, List.of(actor), employee, kbs, graphs, bindings))
            throw new PresalesRejected(
                    409, "EXECUTION_AUTHORITY_CHANGED", "Release authority changed");
        access.requireActor(scope, actor, "member");
        if (!employee.isBlank()) {
            if (employees.getIfAvailable() == null)
                throw new PresalesRejected(
                        409, "EMPLOYEE_UNAVAILABLE", "Project employee unavailable");
            employees.getObject().require(scope, employee);
        }
        sources.authorizeMaterials(scope, project);
        sources.authorizeReleaseSources(scope, project);
        ArrayNode after = capture(scope, project, references(scope, project, solution));
        if (!before.equals(after))
            throw new PresalesRejected(
                    409, "SOURCE_CHANGED", "Source changed while acquiring authority");
        return after;
    }

    private ArrayNode capture(String scope, ObjectNode project, List<Reference> references) {
        ArrayNode pins = project.arrayNode();
        var sorted = new TreeMap<String, Reference>();
        for (var ref : references) sorted.put(ref.graphId() + "/" + ref.sourceId(), ref);
        for (var ref : sorted.values()) {
            var source = sources.candidateSource(scope, project, ref.sourceId(), ref.graphId());
            pins.addObject()
                    .put("sourceId", ref.sourceId())
                    .put("graphId", ref.graphId())
                    .put("kbId", source.kbId())
                    .put(
                            "digest",
                            PresalesArtifactRenderer.digest(
                                    source.text().getBytes(StandardCharsets.UTF_8)));
        }
        return pins;
    }

    private List<Reference> references(String scope, ObjectNode project, ObjectNode solution) {
        var refs = new ArrayList<Reference>();
        collect(project.path("baselines"), "", refs);
        for (var task : project.path("tasks"))
            collect(task.path("contextSnapshot").path("sources"), "", refs);
        for (var release : project.path("releases")) {
            var snapshot = release.path("handoffSnapshot");
            collect(snapshot, "", refs);
            for (var material : snapshot.path("materials"))
                for (var ref : snapshot.path("sourceRefs"))
                    if (!(ref.isObject() && ref.path("sources").isArray()))
                        collect(
                                project.arrayNode().add(ref),
                                material.path("graphId").asText(),
                                refs);
        }
        for (var clarification : project.path("clarifications")) {
            if (project.path("materials").isEmpty())
                collect(clarification.path("sourceRefs"), "", refs);
            for (var material : project.path("materials"))
                collect(clarification.path("sourceRefs"), material.path("graphId").asText(), refs);
        }
        refs.addAll(sources.historicalFitSources(scope, project));
        refs.addAll(releases.currentSources(scope, project, solution));
        return refs;
    }

    private static void collectMaterials(
            JsonNode materials, java.util.Set<String> kbs, java.util.Set<String> graphs) {
        for (var material : materials) {
            kbs.add(material.path("kbId").asText());
            String graph = material.path("graphId").asText();
            if (!graph.isBlank()) graphs.add(graph);
        }
    }

    private static void collect(JsonNode node, String graph, List<Reference> refs) {
        if (node.isArray()) {
            for (var child : node) {
                if (child.isTextual()) refs.add(new Reference(child.asText(), graph));
                else collect(child, graph, refs);
            }
        } else if (node.isObject()) {
            String currentGraph = node.path("graphId").asText(graph);
            if (node.path("sourceRef").isTextual())
                refs.add(new Reference(node.path("sourceRef").asText(), currentGraph));
            for (String field : List.of("references", "sources", "sourceRefs", "baseline"))
                collect(node.path(field), currentGraph, refs);
        }
    }
}
