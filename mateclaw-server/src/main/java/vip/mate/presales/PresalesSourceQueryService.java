package vip.mate.presales;

import static vip.mate.presales.PresalesDtos.*;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.query.SemanticQueryDtos.EvidenceResult;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

/** Source discovery and semantic reads, retaining project authorization before semantic access. */
@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesSourceQueryService {
    private final PresalesService projects;
    private final PresalesAccess access;
    private final WikiKnowledgeBaseService wiki;
    private final ObjectProvider<GraphApplicationService> graphs;
    private final ObjectProvider<StatementApplicationService> statements;
    private final ObjectProvider<SemanticQueryService> queries;
    private final SemanticProperties semantic;

    public PresalesSourceQueryService(
            PresalesService projects,
            PresalesAccess access,
            WikiKnowledgeBaseService wiki,
            ObjectProvider<GraphApplicationService> graphs,
            ObjectProvider<StatementApplicationService> statements,
            ObjectProvider<SemanticQueryService> queries,
            SemanticProperties semantic) {
        this.projects = projects;
        this.access = access;
        this.wiki = wiki;
        this.graphs = graphs;
        this.statements = statements;
        this.queries = queries;
        this.semantic = semantic;
    }

    public List<Source> sources(String scope) {
        access.require(scope, "viewer");
        List<Source> out = new ArrayList<>();
        for (var kb : wiki.listByWorkspace(Long.valueOf(scope)).stream().limit(200).toList()) {
            Source item = new KnowledgeBaseSource(kb.getId().toString(), kb.getName());
            if (semantic.isEnabled() && graphs.getIfAvailable() != null) {
                var binding = graphs.getObject().findBinding(scope, kb.getId().toString());
                if (binding.isPresent() && binding.get().enabled())
                    item =
                            new GraphSource(
                                    item.kbId(),
                                    item.name(),
                                    binding.get().graphId(),
                                    binding.get().ontologyRevisionId());
            }
            out.add(item);
        }
        return out;
    }

    public List<TrustedStatement> trustedStatements(String scope, String projectId) {
        var p = projects.get(scope, projectId);
        requireSemantic();
        List<TrustedStatement> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var material : p.withArray("materials")) {
            String graph = material.path("graphId").asText();
            if (graph.isBlank() || !seen.add(graph)) continue;
            for (var fact : statements.getObject().trusted(scope, graph)) {
                out.add(
                        new TrustedStatement(
                                fact.id(),
                                fact.revision(),
                                graph,
                                fact.ontologyRevisionId(),
                                fact.assertion().functionalSyntax(),
                                fact.evidenceIds()));
                if (out.size() >= 500) return out;
            }
        }
        return out;
    }

    public Capabilities capabilities(String scope) {
        access.require(scope, "viewer");
        return new Capabilities(
                true,
                semantic.isEnabled(),
                access.allowed(scope, "member"),
                access.allowed(scope, "admin"));
    }

    public EvidenceResult evidence(
            String scope, String projectId, String graphId, String evidenceId) {
        var p = projects.get(scope, projectId);
        PresalesProjectItems.requireBoundGraph(p, graphId);
        requireSemantic();
        return queries.getObject().evidence(scope, graphId, evidenceId);
    }

    private void requireSemantic() {
        if (!semantic.isEnabled()
                || graphs.getIfAvailable() == null
                || statements.getIfAvailable() == null)
            throw new PresalesRejected(
                    409, "SEMANTIC_DISABLED", "Semantic module is required for approval");
    }
}
