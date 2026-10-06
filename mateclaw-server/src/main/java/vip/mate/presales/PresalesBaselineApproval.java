package vip.mate.presales;

import static vip.mate.presales.PresalesProjectItems.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.statement.StatementApplicationService;

/**
 * Validates and captures a baseline from an authorized project and current public semantic facts.
 * The application service owns admin authorization, module availability, transactions and
 * persistence.
 */
final class PresalesBaselineApproval {
    private final ObjectMapper json;
    private final ObjectProvider<GraphApplicationService> graphs;
    private final ObjectProvider<StatementApplicationService> statements;
    private final ObjectProvider<SemanticQueryService> queries;
    private final PresalesSourceAuthorization sourceAuthorization;

    PresalesBaselineApproval(
            ObjectMapper json,
            ObjectProvider<GraphApplicationService> graphs,
            ObjectProvider<StatementApplicationService> statements,
            ObjectProvider<SemanticQueryService> queries,
            PresalesSourceAuthorization sourceAuthorization) {
        this.json = json;
        this.graphs = graphs;
        this.statements = statements;
        this.queries = queries;
        this.sourceAuthorization = sourceAuthorization;
    }

    void prepare(String scope, ObjectNode p, ObjectNode v, String actor) {
        text(v.path("reason").asText(), "reason", 10000);
        if (p.withArray("requirements").isEmpty()) throw bad("Requirements are required");
        for (var c : p.withArray("clarifications"))
            if (!"ANSWERED".equals(c.path("status").asText())
                    || c.path("answer").asText().isBlank()
                    || c.path("answerSourceId").asText().isBlank())
                throw conflict(
                        "OPEN_CLARIFICATIONS",
                        "Resolve clarifications with answer sources before baseline approval");
        ArrayNode refs = json.createArrayNode();
        for (var req : p.withArray("requirements")) {
            if ("UNKNOWN".equals(req.path("scope").asText()))
                throw conflict(
                        "SCOPE_UNRESOLVED", "Resolve requirement scope before baseline approval");
            String graph = req.path("graphId").asText();
            boolean bound = false;
            for (var material : p.withArray("materials"))
                if (graph.equals(material.path("graphId").asText())) bound = true;
            if (graph.isBlank() || !bound)
                throw bad("Requirement graph must be bound to this project");
            var graphRow = graphs.getObject().requireGraph(scope, graph, true);
            for (var material : p.withArray("materials"))
                if (graph.equals(material.path("graphId").asText())
                        && !graphRow.getOntologyRevisionId()
                                .equals(material.path("ontologyRevisionId").asText()))
                    throw conflict("BINDING_STALE", "Rebind graph after ontology change");
            var fact =
                    statements.getObject().trusted(scope, graph).stream()
                            .filter(
                                    s ->
                                            s.id().equals(req.path("statementId").asText())
                                                    && PresalesProjectItems.matchesRevision(
                                                            req.path("statementRevision"),
                                                            s.revision()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            conflict(
                                                    "SEMANTIC_REVIEW_REQUIRED",
                                                    "Each requirement must reference a current"
                                                            + " accepted supported statement"));
            Integer requirementVersion = PresalesProjectItems.positiveRevision(req.path("version"));
            if (requirementVersion == null)
                throw conflict("BASELINE_STALE", "Requirement version is invalid");
            ObjectNode ref = json.createObjectNode();
            ref.put("requirementId", req.path("id").asText())
                    .put("requirementVersion", requirementVersion)
                    .put("scope", req.path("scope").asText())
                    .put("graphId", graph)
                    .put("statementId", fact.id())
                    .put("statementRevision", fact.revision())
                    .put("ontologyRevisionId", fact.ontologyRevisionId());
            ref.set("evidenceIds", json.valueToTree(fact.evidenceIds()));
            ref.set("assertion", json.valueToTree(fact.assertion()));
            ArrayNode evidence = ref.putArray("sources");
            for (String eid : fact.evidenceIds()) {
                var source = queries.getObject().evidence(scope, graph, eid);
                sourceAuthorization.currentSource(
                        scope, source.sourceRef(), source.textDigest(), true);
                evidence.add(json.valueToTree(source));
            }
            refs.add(ref);
        }
        v.set("references", refs);
        v.put("approvedBy", actor).put("customerConfirmationStatus", "UNCONFIRMED");
        Long projectVersion = PresalesProjectRevision.positiveRevision(p.path("version"));
        if (projectVersion == null) throw conflict("VERSION_CONFLICT", "Stored version is invalid");
        v.set("projectVersion", PresalesProjectRevision.number(projectVersion));
    }

    private static PresalesRejected bad(String message) {
        return new PresalesRejected(400, "INVALID_REQUEST", message);
    }

    private static PresalesRejected conflict(String code, String message) {
        return new PresalesRejected(409, code, message);
    }
}
