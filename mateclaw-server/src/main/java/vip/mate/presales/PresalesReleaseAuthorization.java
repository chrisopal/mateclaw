package vip.mate.presales;

import static vip.mate.presales.PresalesProjectItems.find;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.statement.StatementApplicationService;

/** Rechecks live semantic facts before release; the caller owns role checks and transactions. */
final class PresalesReleaseAuthorization {
    private final SemanticProperties semantic;
    private final ObjectProvider<GraphApplicationService> graphs;
    private final ObjectProvider<StatementApplicationService> statements;
    private final ObjectProvider<SemanticQueryService> queries;
    private final PresalesSourceAuthorization sources;
    private final PresalesSolutionPolicy solutions;

    PresalesReleaseAuthorization(
            SemanticProperties semantic,
            ObjectProvider<GraphApplicationService> graphs,
            ObjectProvider<StatementApplicationService> statements,
            ObjectProvider<SemanticQueryService> queries,
            PresalesSourceAuthorization sources,
            PresalesSolutionPolicy solutions) {
        this.semantic = semantic;
        this.graphs = graphs;
        this.statements = statements;
        this.queries = queries;
        this.sources = sources;
        this.solutions = solutions;
    }

    void requireEnabled() {
        if (!semantic.isEnabled()
                || graphs.getIfAvailable() == null
                || statements.getIfAvailable() == null)
            throw conflict("SEMANTIC_DISABLED", "Semantic module is required for approval");
    }

    void validateEvidence(String scope, ObjectNode project, ObjectNode value) {
        requireEnabled();
        String graph = value.path("graphId").asText();
        PresalesProjectItems.requireBoundGraph(project, graph);
        for (var id : value.path("evidenceIds"))
            queries.getObject().evidence(scope, graph, id.asText());
    }

    String reviewId(String scope, ObjectNode p, ObjectNode solution) {
        requireEnabled();
        var baseline = solutions.releaseBaseline(p, solution);
        for (var ref : baseline.path("references")) {
            var requirement = find(p, "requirements", ref.path("requirementId").asText());
            Integer requirementVersion =
                    PresalesProjectItems.positiveRevision(requirement.path("version"));
            if (requirementVersion == null
                    || !PresalesProjectItems.matchesRevision(
                            ref.path("requirementVersion"), requirementVersion))
                throw conflict("BASELINE_STALE", "Requirement changed after baseline approval");
            String graph = ref.path("graphId").asText();
            PresalesProjectItems.requireBoundGraph(p, graph);
            var currentGraph = graphs.getObject().requireGraph(scope, graph, true);
            if (!ref.path("ontologyRevisionId")
                    .asText()
                    .equals(currentGraph.getOntologyRevisionId()))
                throw conflict("BASELINE_STALE", "Graph ontology changed after baseline approval");
            boolean trusted =
                    statements.getObject().trusted(scope, graph).stream()
                            .anyMatch(
                                    f ->
                                            f.id().equals(ref.path("statementId").asText())
                                                    && PresalesProjectItems.matchesRevision(
                                                            ref.path("statementRevision"),
                                                            f.revision()));
            if (!trusted)
                throw conflict("BASELINE_STALE", "Semantic fact changed or support withdrawn");
            for (var eid : ref.path("evidenceIds")) {
                var source = queries.getObject().evidence(scope, graph, eid.asText());
                sources.currentSource(scope, source.sourceRef(), source.textDigest(), true);
            }
        }
        for (var fitId : solution.path("fitGapRefs")) {
            var fit = find(p, "fitGaps", fitId.asText());
            if (!"UNKNOWN".equals(fit.path("status").asText())) validateEvidence(scope, p, fit);
        }
        return solutions.releaseReviewId(p, solution);
    }

    private static PresalesRejected conflict(String code, String message) {
        return new PresalesRejected(409, code, message);
    }
}
