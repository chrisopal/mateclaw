package vip.mate.semantic.extraction;

import java.util.Map;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.OntologyRevision;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.application.extraction.ExtractionPorts.ContextPort;

public class MateClawContextAdapter implements ContextPort {
    private final GraphApplicationService graphs;private final SemanticDomainMapper domain;private final MateClawModelAdapter models;private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public MateClawContextAdapter(GraphApplicationService graphs,SemanticDomainMapper domain,MateClawModelAdapter models,org.springframework.jdbc.core.JdbcTemplate jdbc){this.graphs=graphs;this.domain=domain;this.models=models;this.jdbc=jdbc;}
    public OntologyRevision ontology(Actor a,String graph){return domain.ontology(graphs.requireGraph(a.workspaceId(),graph,false));}
    public GraphScope scope(Actor a,String graph){return domain.scope(graphs.requireGraph(a.workspaceId(),graph,false));}
    public Map<EntityId,Entity> entities(Actor a,String graph){return domain.entities(graphs.requireGraph(a.workspaceId(),graph,false));}
    public void requireSnapshot(Actor a,String graph,SourceSnapshotInput source){
        graphs.requireGraph(a.workspaceId(),graph,false);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot s WHERE s.graph_id=? AND s.id=? AND s.source_id=? AND s.text_digest=? AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=s.graph_id AND x.snapshot_id=s.id)",Integer.class,graph,source.snapshotId(),source.sourceRef(),source.contentHash());
        if(count!=1)throw new vip.mate.semantic.application.extraction.ExtractionException(404,"SOURCE_UNAVAILABLE");
    }
    public ModelConfiguration configuration(Actor a,String graph,String id){graphs.requireGraph(a.workspaceId(),graph,false);return models.configuration(id);}
}
