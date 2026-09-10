package vip.mate.semantic.statement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.SemanticIds.StatementId;
import vip.mate.semantic.graph.*;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class StatementApplicationService {
    final JdbcTemplate jdbc;
    final GraphApplicationService graphs;
    final SemanticAccessService access;
    final SemanticDomainMapper domain;
    final OntologyWireMapper wire;
    final SupportEvaluator support;

    public StatementApplicationService(JdbcTemplate jdbc,GraphApplicationService graphs,SemanticAccessService access,SemanticDomainMapper domain,OntologyWireMapper wire,SupportEvaluator support){this.jdbc=jdbc;this.graphs=graphs;this.access=access;this.domain=domain;this.wire=wire;this.support=support;}

    @Transactional
    public StatementView propose(String scope,String graphId,ProposeRequest request){
        var actor=access.require(scope,"member");
        GraphRow graph=graphs.requireGraph(scope,graphId,true);
        requireEnabled(graph); requireOperation(request==null?null:request.operationId());
        StatementView replay=replay(graphId,request.operationId(),request);
        if(replay!=null)return replay;
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Long.class,graphId);
        if(count!=null&&count>=10000)throw new SemanticApiException(422,"GRAPH_STATEMENT_LIMIT","Graph supports at most 10000 statements");
        StatementContent content=domain.content(graph,request); domain.validate(graph,content);
        requireEvidence(graphId,content);
        String statementId=id(); LocalDateTime now=now();
        jdbc.update("INSERT INTO mate_semantic_statement(id,graph_id,current_revision,created_by,created_at) VALUES(?,?,?,?,?)",statementId,graphId,1,actor.getId().toString(),now);
        insertRevision(graph,statementId,1,request,"PROPOSED",actor.getId().toString(),null,now);
        var ontology=domain.ontology(graph);
        for(StoredRevision other:current(graphId,List.of("PROPOSED","ACCEPTED"))){
            if(statementId.equals(other.statementId()))continue;
            domain.compare(ontology,graph,content,domain.content(graph,decode(other.contentJson()))).ifPresent(kind->insertConflict(graphId,kind.name(),"STATEMENT",statementId,1,"STATEMENT",other.statementId(),other.revision(),now));
        }
        for(PendingChange other:pendingChanges(graphId))domain.compare(ontology,graph,content,domain.content(graph,decode(other.payload()))).ifPresent(kind->insertConflict(graphId,kind.name(),"STATEMENT",statementId,1,"CHANGE_PROPOSAL",other.id(),other.expectedRevision(),now));
        touch(graph);
        StatementView result=view(graph,row(graphId,statementId));
        command(graphId,request.operationId(),"PROPOSE_STATEMENT",request,result);
        return result;
    }

    @Transactional
    public ChangeView proposeChange(String scope,String graphId,String statementId,ChangeRequest request){
        var actor=access.require(scope,"member"); GraphRow graph=graphs.requireGraph(scope,graphId,true); requireEnabled(graph);
        if(request==null||request.expectedRevision()==null||request.expectedRevision()<1||request.content()==null)throw bad("expectedRevision and content required");
        requireOperation(request.operationId());
        List<ChangeView> replay=jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE graph_id=? AND operation_id=?",(rs,n)->change(rs),graphId,request.operationId());
        if(!replay.isEmpty()){
            String payload=jdbc.queryForObject("SELECT payload_json FROM mate_semantic_change_proposal WHERE id=?",String.class,replay.getFirst().id());
            if(!statementId.equals(replay.getFirst().targetStatementId())
                    || request.expectedRevision()!=replay.getFirst().expectedRevision()
                    || !wire.encode(request.content()).equals(payload))throw conflict("OPERATION_CONFLICT","Operation id has different change target, base revision, or payload");
            return replay.getFirst();
        }
        StoredRevision current=row(graphId,statementId);
        if(current==null)throw notFound();
        if(current.revision()!=request.expectedRevision())throw conflict("STATEMENT_VERSION_CONFLICT","Statement revision changed");
        StatementContent content=domain.content(graph,request.content());domain.validate(graph,content);requireEvidence(graphId,content);
        String proposal=id();LocalDateTime now=now();
        jdbc.update("INSERT INTO mate_semantic_change_proposal(id,graph_id,target_statement_id,expected_revision,operation_id,payload_json,status,proposed_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",proposal,graphId,statementId,request.expectedRevision(),request.operationId(),wire.encode(request.content()),"PENDING",actor.getId().toString(),now);
        var ontology=domain.ontology(graph);
        for(StoredRevision other:current(graphId,List.of("PROPOSED","ACCEPTED"))){
            if(statementId.equals(other.statementId()))continue;
            domain.compare(ontology,graph,content,domain.content(graph,decode(other.contentJson()))).ifPresent(kind->insertConflict(graphId,kind.name(),"CHANGE_PROPOSAL",proposal,request.expectedRevision(),"STATEMENT",other.statementId(),other.revision(),now));
        }
        for(PendingChange other:pendingChanges(graphId)){
            if(proposal.equals(other.id()))continue;
            domain.compare(ontology,graph,content,domain.content(graph,decode(other.payload()))).ifPresent(kind->insertConflict(graphId,kind.name(),"CHANGE_PROPOSAL",proposal,request.expectedRevision(),"CHANGE_PROPOSAL",other.id(),other.expectedRevision(),now));
        }
        touch(graph);return new ChangeView(proposal,graphId,statementId,request.expectedRevision(),"PENDING",null,actor.getId().toString(),now.toInstant(ZoneOffset.UTC),request.content());
    }

    public Page<StatementView> statements(String scope,String graphId,String view,String status,int page,int pageSize){
        var actor=access.require(scope,"viewer");GraphRow graph=graphs.requireGraph(scope,graphId,false);page(page,pageSize);
        String mode=view==null?"trusted":view;
        if(!Set.of("trusted","review","mine").contains(mode))throw bad("Unsupported statement view");
        if("review".equals(mode))access.require(scope,"admin");
        List<StoredRevision> rows=current(graphId,"review".equals(mode)?List.of("PROPOSED","ACCEPTED","REJECTED","RETRACTED"):"mine".equals(mode)?List.of("PROPOSED"):List.of("ACCEPTED"));
        if("mine".equals(mode))rows=rows.stream().filter(r->r.actorId().equals(actor.getId().toString())).toList();
        Set<String> supported=support.supportedCurrentStatements(graph);
        if("trusted".equals(mode))rows=rows.stream().filter(r->supported.contains(r.statementId())).toList();
        if(status!=null&&!status.isBlank())rows=rows.stream().filter(r->status.equalsIgnoreCase(r.status())).toList();
        int from=Math.min((page-1)*pageSize,rows.size()),to=Math.min(from+pageSize,rows.size());
        return new Page<>(rows.subList(from,to).stream().map(r->viewWithSupport(graph,r,"ACCEPTED".equals(r.status())?(supported.contains(r.statementId())?"SUPPORTED":"SUPPORT_LOST"):"UNREVIEWED")).toList(),rows.size(),page,pageSize);
    }

    public List<StatementView> trustedAsActor(String scope, String actorId, String graphId) {
        access.requireActor(scope, actorId, "viewer");
        return trustedRows(graphs.requireGraph(scope, graphId, false));
    }

    public List<StatementView> trusted(String scope, String graphId) {
        access.require(scope, "viewer");
        return trustedRows(graphs.requireGraph(scope, graphId, false));
    }

    private List<StatementView> trustedRows(GraphRow graph) {
        Set<String> supported=support.supportedCurrentStatements(graph);
        return current(graph.getId(), List.of("ACCEPTED")).stream()
                .filter(row -> supported.contains(row.statementId()))
                .map(row -> viewWithSupport(graph, row, "SUPPORTED")).toList();
    }

    public Page<ChangeView> changes(String scope,String graphId,String status,int page,int pageSize){
        access.require(scope,"admin");graphs.requireGraph(scope,graphId,false);page(page,pageSize);
        boolean filtered=status!=null&&!status.isBlank();
        Long total=filtered?jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_change_proposal WHERE graph_id=? AND status=?",Long.class,graphId,status.toUpperCase(Locale.ROOT)):jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_change_proposal WHERE graph_id=?",Long.class,graphId);
        List<ChangeView> rows=filtered?jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE graph_id=? AND status=? ORDER BY created_at DESC LIMIT ? OFFSET ?",(rs,n)->change(rs),graphId,status.toUpperCase(Locale.ROOT),pageSize,(page-1)*pageSize):jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE graph_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",(rs,n)->change(rs),graphId,pageSize,(page-1)*pageSize);
        return new Page<>(rows,total==null?0:total,page,pageSize);
    }

    public ChangeView change(String scope,String graphId,String proposalId){
        access.require(scope,"admin");graphs.requireGraph(scope,graphId,false);
        List<ChangeView> rows=jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE graph_id=? AND id=?",(rs,n)->change(rs),graphId,proposalId);
        if(rows.isEmpty())throw notFound();return rows.getFirst();
    }

    public Page<ConflictView> conflicts(String scope,String graphId,int page,int pageSize){
        access.require(scope,"admin");graphs.requireGraph(scope,graphId,false);page(page,pageSize);
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_conflict WHERE graph_id=?",Long.class,graphId);
        List<ConflictView> rows=jdbc.query("SELECT * FROM mate_semantic_conflict WHERE graph_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",(rs,n)->conflictView(rs),graphId,pageSize,(page-1)*pageSize);
        return new Page<>(rows,total==null?0:total,page,pageSize);
    }

    StoredRevision row(String graphId,String statementId){List<StoredRevision> rows=jdbc.query("SELECT r.* FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.graph_id=? AND s.id=?",(rs,n)->stored(rs),graphId,statementId);return rows.isEmpty()?null:rows.getFirst();}
    List<StoredRevision> current(String graphId,List<String> statuses){
        String placeholders=String.join(",",Collections.nCopies(statuses.size(),"?"));List<Object> args=new ArrayList<>();args.add(graphId);args.addAll(statuses);
        return jdbc.query("SELECT r.* FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.graph_id=? AND r.review_status IN ("+placeholders+") ORDER BY r.created_at,r.statement_id",(rs,n)->stored(rs),args.toArray());
    }
    void insertRevision(GraphRow graph,String statementId,int revision,ProposeRequest request,String status,String actor,String reason,LocalDateTime now){
        var payload=domain.assertion(request.assertionText());
        String kind=payload.predicateIri().isEmpty()?"ASSERTION":payload.literal().isPresent()?"PROPERTY":"RELATION";
        jdbc.update("INSERT INTO mate_semantic_statement_revision(statement_id,revision,graph_id,ontology_revision_id,subject_id,predicate_kind,predicate_iri,assertion_kind,assertion_text,review_status,validity_kind,valid_from,valid_to,value_type,value_text,content_json,actor_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                statementId,revision,graph.getId(),graph.getOntologyRevisionId(),request.subjectId(),kind,payload.predicateIri().orElse(null),payload.kind().name(),payload.functionalSyntax(),status,request.validityKind(),timestamp(request.validFrom()),timestamp(request.validTo()),"OWL_AXIOM",payload.literal().map(v->v.lexicalValue()).orElse(null),wire.encode(request),actor,reason,now);
        if(request.evidenceIds()!=null)for(String evidence:request.evidenceIds())jdbc.update("INSERT INTO mate_semantic_revision_evidence(statement_id,revision,evidence_id) VALUES(?,?,?)",statementId,revision,evidence);
    }
    StatementView view(GraphRow graph,StoredRevision row){
        String supportStatus="ACCEPTED".equals(row.status())?(support.supported(graph,row.statementId(),row.revision())?"SUPPORTED":"SUPPORT_LOST"):"UNREVIEWED";
        return viewWithSupport(graph,row,supportStatus);
    }
    private StatementView viewWithSupport(GraphRow graph,StoredRevision row,String supportStatus){
        ProposeRequest request=decode(row.contentJson());
        List<String> evidence=request.evidenceIds()==null?List.of():request.evidenceIds().stream().sorted().toList();
        return new StatementView(row.statementId(),graph.getId(),row.revision(),row.ontologyRevisionId(),request.subjectId(),domain.assertion(request.assertionText()),request.validityKind(),request.validFrom(),request.validTo(),row.status(),supportStatus,evidence,row.actorId(),row.createdAt().toInstant(ZoneOffset.UTC));
    }
    ProposeRequest decode(String json){return wire.decode(json,ProposeRequest.class);}
    void requireEvidence(String graphId,StatementContent content){
        for(var id:content.evidenceIds()){
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_evidence WHERE id=? AND graph_id=?",Integer.class,id.value(),graphId);
            if(count==null||count==0)throw new SemanticApiException(422,"INVALID_EVIDENCE","Evidence does not belong to graph");
        }
    }
    void touch(GraphRow graph){if(jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1,updated_at=? WHERE id=? AND mutation_version=?",now(),graph.getId(),graph.getMutationVersion())!=1)throw conflict("GRAPH_VERSION_CONFLICT","Graph changed concurrently");graph.setMutationVersion(graph.getMutationVersion()+1);}
    private StatementView replay(String graphId,String operation,Object request){
        List<CommandRow> rows=jdbc.query("SELECT * FROM mate_semantic_mutation_command WHERE graph_id=? AND operation_id=?",(rs,n)->new CommandRow(rs.getString("payload_hash"),rs.getString("result_json")),graphId,operation);
        if(rows.isEmpty())return null;if(!hash(wire.encode(request)).equals(rows.getFirst().hash()))throw conflict("OPERATION_CONFLICT","Operation id has different statement payload");return wire.decode(rows.getFirst().result(),StatementView.class);
    }
    void command(String graph,String operation,String kind,Object payload,Object result){jdbc.update("INSERT INTO mate_semantic_mutation_command(id,graph_id,operation_id,kind,payload_hash,result_json,created_at) VALUES(?,?,?,?,?,?,?)",id(),graph,operation,kind,hash(wire.encode(payload)),wire.encode(result),now());}
    private static StoredRevision stored(ResultSet rs)throws SQLException{return new StoredRevision(rs.getString("statement_id"),rs.getInt("revision"),rs.getString("ontology_revision_id"),rs.getString("review_status"),rs.getString("content_json"),rs.getString("actor_id"),rs.getTimestamp("created_at").toLocalDateTime());}
    private ChangeView change(ResultSet rs)throws SQLException{Integer result=(Integer)rs.getObject("result_revision");return new ChangeView(rs.getString("id"),rs.getString("graph_id"),rs.getString("target_statement_id"),rs.getInt("expected_revision"),rs.getString("status"),result,rs.getString("proposed_by"),rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC),decode(rs.getString("payload_json")));}
    private List<PendingChange> pendingChanges(String graphId){return jdbc.query("SELECT cp.id,cp.target_statement_id,cp.expected_revision,cp.payload_json FROM mate_semantic_change_proposal cp JOIN mate_semantic_statement s ON s.id=cp.target_statement_id AND s.graph_id=cp.graph_id AND s.current_revision=cp.expected_revision WHERE cp.graph_id=? AND cp.status='PENDING'",(rs,n)->new PendingChange(rs.getString("id"),rs.getString("target_statement_id"),rs.getInt("expected_revision"),rs.getString("payload_json")),graphId);}
    private void insertConflict(String graphId,String kind,String leftKind,String leftId,int leftRevision,String rightKind,String rightId,int rightRevision,LocalDateTime created){jdbc.update("INSERT INTO mate_semantic_conflict(id,graph_id,kind,status,left_member_kind,left_statement_id,left_revision,right_member_kind,right_statement_id,right_revision,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id(),graphId,kind,"OPEN",leftKind,leftId,leftRevision,rightKind,rightId,rightRevision,created);}
    private static ConflictView conflictView(ResultSet rs)throws SQLException{return new ConflictView(rs.getString("id"),rs.getString("graph_id"),rs.getString("kind"),rs.getString("status"),new ConflictMember(rs.getString("left_member_kind"),rs.getString("left_statement_id"),rs.getInt("left_revision")),new ConflictMember(rs.getString("right_member_kind"),rs.getString("right_statement_id"),rs.getInt("right_revision")),rs.getString("resolution_json"));}
    static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
    public static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    public static void requireOperation(String value){if(value==null||value.isBlank()||value.length()>128)throw bad("operationId required");}
    static void requireEnabled(GraphRow graph){if(!Boolean.TRUE.equals(graph.getEnabled()))throw conflict("GRAPH_DISABLED","Graph is disabled");}
    static void page(int page,int size){if(page<1||size<1||size>100)throw bad("Invalid pagination");}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static SemanticApiException bad(String message){return new SemanticApiException(400,"INVALID_REQUEST",message);}
    static SemanticApiException conflict(String code,String message){return new SemanticApiException(409,code,message);}
    static SemanticApiException notFound(){return new SemanticApiException(404,"NOT_FOUND","Statement not found in graph");}
    record StoredRevision(String statementId,int revision,String ontologyRevisionId,String status,String contentJson,String actorId,LocalDateTime createdAt){}
    private record PendingChange(String id,String targetStatementId,int expectedRevision,String payload){}
    private record CommandRow(String hash,String result){}
}
