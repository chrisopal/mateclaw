package vip.mate.semantic.extraction;

import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.application.extraction.ExtractionPorts.AccessPolicyPort;

/** Uses current host identity, membership, graph/KB ownership, deletion and withdrawal state. */
public class MateClawAccessAdapter implements AccessPolicyPort {
    private final SemanticAccessService access;private final GraphApplicationService graphs;private final JdbcTemplate jdbc;
    public MateClawAccessAdapter(SemanticAccessService access,GraphApplicationService graphs,JdbcTemplate jdbc){this.access=access;this.graphs=graphs;this.jdbc=jdbc;}
    public void require(Actor actor,String graphId,String sourceRef,Action action){
        access.requireActor(actor.workspaceId(),actor.userId(),action==Action.READ?"viewer":"member");
        var graph=graphs.requireGraph(actor.workspaceId(),graphId,false);
        if(action!=Action.READ&&!Boolean.TRUE.equals(graph.getEnabled()))throw new SemanticApiException(409,"GRAPH_DISABLED","Graph is disabled");
        if(sourceRef==null)return;
        if(!sourceRef.matches("[1-9][0-9]*"))throw new SemanticApiException(400,"INVALID_SOURCE","WIKI_RAW source identifier required");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",Integer.class,sourceRef,graph.getKbId());
        int withdrawn=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_kind='WIKI_RAW' AND source_id=? AND state<>'ACTIVE'",Integer.class,graphId,sourceRef);
        if(count!=1||withdrawn>0)throw new SemanticApiException(404,"SOURCE_UNAVAILABLE","Source is no longer available");
    }
}
