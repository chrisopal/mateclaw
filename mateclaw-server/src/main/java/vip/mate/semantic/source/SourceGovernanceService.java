package vip.mate.semantic.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.graph.*;
import vip.mate.semantic.governance.SemanticGovernanceService;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.SourceDtos.GovernanceRequest;
import vip.mate.semantic.web.SourceDtos.GovernanceResult;

import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class SourceGovernanceService {
    private final JdbcTemplate jdbc;private final GraphApplicationService graphs;private final SemanticAccessService access;private final OntologyWireMapper wire;private final SemanticGovernanceService governance;
    public SourceGovernanceService(JdbcTemplate jdbc,GraphApplicationService graphs,SemanticAccessService access,OntologyWireMapper wire,SemanticGovernanceService governance){this.jdbc=jdbc;this.graphs=graphs;this.access=access;this.wire=wire;this.governance=governance;}
    @Transactional public GovernanceResult withdraw(String scope,String graphId,GovernanceRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);validate(request);
        GovernanceResult replay=replay(graphId,request,"SOURCE_WITHDRAW");if(replay!=null)return replay;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind=? AND source_id=?",Integer.class,graphId,request.sourceKind(),request.sourceRef());if(count==null||count==0)throw new SemanticApiException(404,"NOT_FOUND","Source not found in graph");
        Integer sourceVersion=sourceVersion(graphId,request.sourceKind(),request.sourceRef());
        jdbc.update("DELETE FROM mate_semantic_source_governance WHERE graph_id=? AND source_kind=? AND source_id=?",graphId,request.sourceKind(),request.sourceRef());
        jdbc.update("INSERT INTO mate_semantic_source_governance(graph_id,source_kind,source_id,state,actor_id,reason,created_at) VALUES(?,?,?,?,?,?,?)",graphId,request.sourceKind(),request.sourceRef(),"WITHDRAWN",actor.getId().toString(),request.reason(),StatementApplicationService.now());touch(graph);
        GovernanceResult result=new GovernanceResult(request.sourceKind(),request.sourceRef(),"WITHDRAWN");command(graphId,request,"SOURCE_WITHDRAW",result);
        governance.append(graphId,graph.getWorkspaceId(),"SOURCE",request.sourceRef(),sourceVersion,"SOURCE_WITHDRAW",request.operationId(),actor.getId().toString(),request.reason(),wire.encode(result));return result;
    }
    @Transactional public GovernanceResult exclude(String scope,String graphId,String snapshotId,GovernanceRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);if(request==null||request.reason()==null||request.reason().isBlank())throw new SemanticApiException(400,"INVALID_REQUEST","Reason required");StatementApplicationService.requireOperation(request.operationId());
        GovernanceResult replay=replay(graphId,request,"SNAPSHOT_EXCLUDE");if(replay!=null)return replay;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE graph_id=? AND id=?",Integer.class,graphId,snapshotId);if(count==null||count==0)throw new SemanticApiException(404,"NOT_FOUND","Snapshot not found in graph");
        Integer snapshotVersion=snapshotVersion(graphId,snapshotId);
        jdbc.update("DELETE FROM mate_semantic_snapshot_exclusion WHERE graph_id=? AND snapshot_id=?",graphId,snapshotId);
        jdbc.update("INSERT INTO mate_semantic_snapshot_exclusion(graph_id,snapshot_id,actor_id,reason,created_at) VALUES(?,?,?,?,?)",graphId,snapshotId,actor.getId().toString(),request.reason(),StatementApplicationService.now());touch(graph);
        GovernanceResult result=new GovernanceResult("SNAPSHOT",snapshotId,"EXCLUDED");command(graphId,request,"SNAPSHOT_EXCLUDE",result);
        governance.append(graphId,graph.getWorkspaceId(),"SNAPSHOT",snapshotId,snapshotVersion,"SNAPSHOT_EXCLUDE",request.operationId(),actor.getId().toString(),request.reason(),wire.encode(result));return result;
    }
    private void touch(GraphRow graph){if(jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1,updated_at=? WHERE id=? AND mutation_version=?",StatementApplicationService.now(),graph.getId(),graph.getMutationVersion())!=1)throw new SemanticApiException(409,"GRAPH_VERSION_CONFLICT","Graph changed concurrently");}
    private GovernanceResult replay(String graphId,GovernanceRequest request,String kind){List<Map<String,Object>> rows=jdbc.queryForList("SELECT kind,payload_hash,result_json FROM mate_semantic_mutation_command WHERE graph_id=? AND operation_id=?",graphId,request.operationId());if(rows.isEmpty())return null;Map<String,Object> row=rows.getFirst();if(!kind.equals(row.get("kind"))||!StatementApplicationService.hash(wire.encode(request)).equals(row.get("payload_hash")))throw new SemanticApiException(409,"OPERATION_CONFLICT","Operation id has different governance payload");return wire.decode((String)row.get("result_json"),GovernanceResult.class);}
    private void command(String graphId,GovernanceRequest request,String kind,GovernanceResult result){jdbc.update("INSERT INTO mate_semantic_mutation_command(id,graph_id,operation_id,kind,payload_hash,result_json,created_at) VALUES(?,?,?,?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(),graphId,request.operationId(),kind,StatementApplicationService.hash(wire.encode(request)),wire.encode(result),StatementApplicationService.now());}
    private Integer sourceVersion(String graphId,String sourceKind,String sourceRef){Long version=jdbc.queryForObject("SELECT MAX(capture_version) FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind=? AND source_id=?",Long.class,graphId,sourceKind,sourceRef);return version==null?null:Math.toIntExact(version);}
    private Integer snapshotVersion(String graphId,String snapshotId){Long version=jdbc.queryForObject("SELECT capture_version FROM mate_semantic_source_snapshot WHERE graph_id=? AND id=?",Long.class,graphId,snapshotId);return version==null?null:Math.toIntExact(version);}
    private static void validate(GovernanceRequest request){if(request==null||request.sourceKind()==null||request.sourceRef()==null||request.reason()==null||request.reason().isBlank())throw new SemanticApiException(400,"INVALID_REQUEST","Source identity and reason required");StatementApplicationService.requireOperation(request.operationId());}
}
