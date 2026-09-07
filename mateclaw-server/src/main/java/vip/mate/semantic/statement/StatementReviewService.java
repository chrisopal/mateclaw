package vip.mate.semantic.statement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.core.conflict.ConflictDetector;
import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.identity.SemanticIds.StatementId;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.*;

import java.time.ZoneOffset;
import java.util.*;

@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class StatementReviewService {
    private final StatementApplicationService statements;
    private final SemanticAccessService access;
    private final GraphApplicationService graphs;
    private final JdbcTemplate jdbc;
    private final SemanticDomainMapper domain;
    private final OntologyWireMapper wire;
    private final SupportEvaluator support;
    private final ConflictDetector detector=new ConflictDetector();
    public StatementReviewService(StatementApplicationService statements,SemanticAccessService access,GraphApplicationService graphs,JdbcTemplate jdbc,SemanticDomainMapper domain,OntologyWireMapper wire,SupportEvaluator support){
        this.statements=statements;this.access=access;this.graphs=graphs;this.jdbc=jdbc;this.domain=domain;this.wire=wire;this.support=support;
    }

    @Transactional
    public StatementView review(String scope,String graphId,String statementId,ReviewRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);StatementApplicationService.requireEnabled(graph);
        validate(request);String hash=StatementApplicationService.hash(wire.encode(request));
        StatementView replay=replay(graphId,request.operationId(),hash,"REVIEW_STATEMENT",StatementView.class);if(replay!=null)return replay;
        var current=statements.row(graphId,statementId);if(current==null)throw StatementApplicationService.notFound();
        if(current.revision()!=request.expectedRevision())throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Statement revision changed");
        String action=request.action().toUpperCase(Locale.ROOT);
        String next=switch(action){case "ACCEPT"->"ACCEPTED";case "REJECT"->"REJECTED";case "RETRACT"->"RETRACTED";default->throw StatementApplicationService.bad("Unsupported review action");};
        if("ACCEPTED".equals(next))validateAcceptance(graph,current,statementId);
        if("RETRACTED".equals(next)&&!"ACCEPTED".equals(current.status()))throw StatementApplicationService.conflict("INVALID_REVIEW_STATE","Only accepted facts can be retracted");
        if(("ACCEPTED".equals(next)||"REJECTED".equals(next))&&!"PROPOSED".equals(current.status()))throw StatementApplicationService.conflict("INVALID_REVIEW_STATE","Only proposed facts can be reviewed");
        StatementView result=writeDecision(graph,current,next,actor.getId().toString(),request.reason());
        statements.command(graphId,request.operationId(),"REVIEW_STATEMENT",request,result);return result;
    }

    @Transactional
    public ChangeView reviewChange(String scope,String graphId,String proposalId,ReviewRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);validate(request);
        String hash=StatementApplicationService.hash(wire.encode(request));
        ChangeView replay=replay(graphId,request.operationId(),hash,"REVIEW_CHANGE",ChangeView.class);if(replay!=null)return replay;
        List<ChangeRow> rows=jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE id=? AND graph_id=?",(rs,n)->new ChangeRow(rs.getString("id"),rs.getString("target_statement_id"),rs.getInt("expected_revision"),rs.getString("payload_json"),rs.getString("status"),rs.getString("proposed_by"),rs.getTimestamp("created_at").toLocalDateTime()),proposalId,graphId);
        if(rows.isEmpty())throw StatementApplicationService.notFound();ChangeRow proposal=rows.getFirst();
        if(!"PENDING".equals(proposal.status()))throw StatementApplicationService.conflict("PROPOSAL_STALE","Change proposal is no longer pending");
        var current=statements.row(graphId,proposal.statementId());if(current==null||current.revision()!=proposal.expectedRevision())throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Change proposal base revision is stale");
        String action=request.action()==null?"":request.action().toUpperCase(Locale.ROOT);Integer resultRevision=null;String status;
        if("ACCEPT".equals(action)){
            ProposeRequest content=statements.decode(proposal.payload());domain.validate(graph,domain.content(graph,content));
            if(content.evidenceIds()==null||content.evidenceIds().isEmpty())throw new SemanticApiException(422,"EVIDENCE_REQUIRED","Accepted facts require evidence");
            ensureNoAcceptedConflict(graph,content,proposal.statementId());
            int next=current.revision()+1;statements.insertRevision(graph,proposal.statementId(),next,content,"ACCEPTED",actor.getId().toString(),request.reason(),StatementApplicationService.now());
            jdbc.update("UPDATE mate_semantic_statement SET current_revision=? WHERE id=? AND current_revision=?",next,proposal.statementId(),current.revision());
            resultRevision=next;status="APPROVED";
        }else if("REJECT".equals(action)){status="REJECTED";}else throw StatementApplicationService.bad("Unsupported change review action");
        jdbc.update("UPDATE mate_semantic_change_proposal SET status=?,result_revision=? WHERE id=? AND status='PENDING'",status,resultRevision,proposalId);
        statements.touch(graph);ChangeView result=new ChangeView(proposalId,graphId,proposal.statementId(),proposal.expectedRevision(),status,resultRevision,proposal.proposedBy(),proposal.createdAt().toInstant(ZoneOffset.UTC));
        statements.command(graphId,request.operationId(),"REVIEW_CHANGE",request,result);return result;
    }

    @Transactional
    public ConflictView resolve(String scope,String graphId,String conflictId,ResolveRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);
        StatementApplicationService.requireOperation(request==null?null:request.operationId());
        if(request.winnerStatementId()==null||request.expectedMembers()==null||request.expectedMembers().size()!=2||request.reason()==null||request.reason().isBlank())throw StatementApplicationService.bad("Winner, two exact members, and reason are required");
        String hash=StatementApplicationService.hash(wire.encode(request));
        ConflictView replay=replay(graphId,request.operationId(),hash,"RESOLVE_CONFLICT",ConflictView.class);if(replay!=null)return replay;
        List<ConflictView> found=jdbc.query("SELECT * FROM mate_semantic_conflict WHERE id=? AND graph_id=? FOR UPDATE",(rs,n)->new ConflictView(rs.getString("id"),rs.getString("graph_id"),rs.getString("kind"),rs.getString("status"),new ConflictMember(rs.getString("left_statement_id"),rs.getInt("left_revision")),new ConflictMember(rs.getString("right_statement_id"),rs.getInt("right_revision")),rs.getString("resolution_json")),conflictId,graphId);
        if(found.isEmpty())throw StatementApplicationService.notFound();ConflictView conflict=found.getFirst();
        if(!"OPEN".equals(conflict.status()))throw StatementApplicationService.conflict("CONFLICT_STALE","Conflict was already resolved");
        Set<String> expected=new HashSet<>();for(ConflictMember member:request.expectedMembers())expected.add(member.statementId()+":"+member.revision());
        Set<String> actual=Set.of(conflict.left().statementId()+":"+conflict.left().revision(),conflict.right().statementId()+":"+conflict.right().revision());
        if(!expected.equals(actual))throw StatementApplicationService.conflict("CONFLICT_MEMBERS_STALE","Conflict members changed");
        if(!Set.of(conflict.left().statementId(),conflict.right().statementId()).contains(request.winnerStatementId()))throw StatementApplicationService.bad("Winner must be a conflict member");
        for(ConflictMember member:List.of(conflict.left(),conflict.right())){
            var current=statements.row(graphId,member.statementId());if(current==null||current.revision()!=member.revision())throw StatementApplicationService.conflict("CONFLICT_MEMBERS_STALE","Conflict member is not current");
        }
        StatementView winnerResult=null;
        for(ConflictMember member:List.of(conflict.left(),conflict.right())){
            var current=statements.row(graphId,member.statementId());boolean winner=member.statementId().equals(request.winnerStatementId());
            String nextStatus=winner?"ACCEPTED":("ACCEPTED".equals(current.status())?"RETRACTED":"REJECTED");
            if(winner&&"PROPOSED".equals(current.status())&&statements.decode(current.contentJson()).evidenceIds().isEmpty())throw new SemanticApiException(422,"EVIDENCE_REQUIRED","Accepted facts require evidence");
            if(winner&&"ACCEPTED".equals(current.status()))winnerResult=statements.view(graph,current);else{
                StatementView changed=writeDecision(graph,current,nextStatus,actor.getId().toString(),request.reason());if(winner)winnerResult=changed;
            }
        }
        String resolution=wire.encode(Map.of("winnerStatementId",request.winnerStatementId(),"reason",request.reason()));
        jdbc.update("UPDATE mate_semantic_conflict SET status='RESOLVED',resolution_json=?,resolved_by=?,resolved_at=? WHERE id=? AND status='OPEN'",resolution,actor.getId().toString(),StatementApplicationService.now(),conflictId);
        jdbc.update("UPDATE mate_semantic_conflict SET status='RESOLVED',resolution_json=?,resolved_by=?,resolved_at=? WHERE graph_id=? AND status='OPEN' AND (left_statement_id IN (?,?) OR right_statement_id IN (?,?))",resolution,actor.getId().toString(),StatementApplicationService.now(),graphId,conflict.left().statementId(),conflict.right().statementId(),conflict.left().statementId(),conflict.right().statementId());
        statements.touch(graph);ConflictView result=new ConflictView(conflict.id(),graphId,conflict.kind(),"RESOLVED",conflict.left(),conflict.right(),resolution);
        statements.command(graphId,request.operationId(),"RESOLVE_CONFLICT",request,result);return result;
    }

    private void validateAcceptance(GraphRow graph,StatementApplicationService.StoredRevision current,String statementId){
        if(current.contentJson()==null)throw StatementApplicationService.notFound();ProposeRequest request=statements.decode(current.contentJson());
        if(request.evidenceIds()==null||request.evidenceIds().isEmpty())throw new SemanticApiException(422,"EVIDENCE_REQUIRED","Accepted facts require evidence");
        if(!support.supported(graph,statementId,current.revision()))throw new SemanticApiException(422,"EVIDENCE_UNSUPPORTED","No active evidence supports this fact");
        Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_conflict WHERE graph_id=? AND status='OPEN' AND (left_statement_id=? OR right_statement_id=?)",Integer.class,graph.getId(),statementId,statementId);
        if(open!=null&&open>0)throw StatementApplicationService.conflict("OPEN_CONFLICT","Resolve deterministic conflicts before acceptance");
        ensureNoAcceptedConflict(graph,request,statementId);
    }
    private void ensureNoAcceptedConflict(GraphRow graph,ProposeRequest candidate,String own){
        var content=domain.content(graph,candidate);for(var other:statements.current(graph.getId(),List.of("ACCEPTED"))){if(other.statementId().equals(own))continue;detector.compare(domain.ontology(graph),content,domain.content(graph,statements.decode(other.contentJson()))).ifPresent(kind->{throw StatementApplicationService.conflict("FACT_CONFLICT","Accepted fact conflicts with current graph");});}
    }
    private StatementView writeDecision(GraphRow graph,StatementApplicationService.StoredRevision current,String status,String actor,String reason){
        int next=current.revision()+1;ProposeRequest content=statements.decode(current.contentJson());statements.insertRevision(graph,current.statementId(),next,content,status,actor,reason,StatementApplicationService.now());
        if(jdbc.update("UPDATE mate_semantic_statement SET current_revision=? WHERE id=? AND current_revision=?",next,current.statementId(),current.revision())!=1)throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Statement changed concurrently");
        statements.touch(graph);return statements.view(graph,statements.row(graph.getId(),current.statementId()));
    }
    private void validate(ReviewRequest request){if(request==null||request.expectedRevision()==null||request.expectedRevision()<1||request.action()==null||request.reason()==null||request.reason().isBlank())throw StatementApplicationService.bad("Review action, expectedRevision, and reason are required");StatementApplicationService.requireOperation(request.operationId());}
    private <T>T replay(String graph,String operation,String hash,String kind,Class<T> type){List<Map<String,Object>> rows=jdbc.queryForList("SELECT kind,payload_hash,result_json FROM mate_semantic_mutation_command WHERE graph_id=? AND operation_id=?",graph,operation);if(rows.isEmpty())return null;if(!kind.equals(rows.getFirst().get("kind"))||!hash.equals(rows.getFirst().get("payload_hash")))throw StatementApplicationService.conflict("OPERATION_CONFLICT","Operation id has different review payload");return wire.decode((String)rows.getFirst().get("result_json"),type);}
    private record ChangeRow(String id,String statementId,int expectedRevision,String payload,String status,String proposedBy,java.time.LocalDateTime createdAt){}
}
