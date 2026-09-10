package vip.mate.semantic.statement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.identity.SemanticIds.StatementId;
import vip.mate.semantic.governance.SemanticGovernanceService;
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
    private final SemanticGovernanceService governance;
    public StatementReviewService(StatementApplicationService statements,SemanticAccessService access,GraphApplicationService graphs,JdbcTemplate jdbc,SemanticDomainMapper domain,OntologyWireMapper wire,SupportEvaluator support,SemanticGovernanceService governance){
        this.statements=statements;this.access=access;this.graphs=graphs;this.jdbc=jdbc;this.domain=domain;this.wire=wire;this.support=support;this.governance=governance;
    }

    @Transactional
    public StatementView review(String scope,String graphId,String statementId,ReviewRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);StatementApplicationService.requireEnabled(graph);
        validate(request);Object commandPayload=List.of(statementId,request);String hash=StatementApplicationService.hash(wire.encode(commandPayload));
        StatementView replay=replay(graphId,statementId,request.operationId(),hash,StatementApplicationService.hash(wire.encode(request)),"REVIEW_STATEMENT",StatementView.class);if(replay!=null)return replay;
        var current=statements.row(graphId,statementId);if(current==null)throw StatementApplicationService.notFound();
        if(current.revision()!=request.expectedRevision())throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Statement revision changed");
        String action=request.action().toUpperCase(Locale.ROOT);
        String next=switch(action){case "ACCEPT"->"ACCEPTED";case "REJECT"->"REJECTED";case "RETRACT"->"RETRACTED";default->throw StatementApplicationService.bad("Unsupported review action");};
        if("ACCEPTED".equals(next))validateAcceptance(graph,current,statementId);
        if("RETRACTED".equals(next)&&!"ACCEPTED".equals(current.status()))throw StatementApplicationService.conflict("INVALID_REVIEW_STATE","Only accepted facts can be retracted");
        if(("ACCEPTED".equals(next)||"REJECTED".equals(next))&&!"PROPOSED".equals(current.status()))throw StatementApplicationService.conflict("INVALID_REVIEW_STATE","Only proposed facts can be reviewed");
        StatementView result=writeDecision(graph,current,next,actor.getId().toString(),request.reason());
        Set<MemberIdentity> affected=new LinkedHashSet<>();affected.add(MemberIdentity.statement(statementId));stalePendingProposals(graphId,statementId,current.revision(),"").forEach(id->affected.add(MemberIdentity.change(id)));
        reconcileOpenConflicts(graph,affected,actor.getId().toString(),request.reason(),null);
        statements.command(graphId,request.operationId(),"REVIEW_STATEMENT",commandPayload,result);
        governance.append(graphId,graph.getWorkspaceId(),"STATEMENT",statementId,result.revision(),"REVIEW_STATEMENT",request.operationId(),actor.getId().toString(),request.reason(),wire.encode(result));return result;
    }

    @Transactional
    public ChangeView reviewChange(String scope,String graphId,String proposalId,ReviewRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);StatementApplicationService.requireEnabled(graph);validate(request);
        Object commandPayload=List.of(proposalId,request);String hash=StatementApplicationService.hash(wire.encode(commandPayload));
        ChangeView replay=replay(graphId,proposalId,request.operationId(),hash,StatementApplicationService.hash(wire.encode(request)),"REVIEW_CHANGE",ChangeView.class);if(replay!=null)return replay;
        ChangeRow proposal=changeRow(graphId,proposalId);if(proposal==null)throw StatementApplicationService.notFound();
        if(!"PENDING".equals(proposal.status()))throw StatementApplicationService.conflict("PROPOSAL_STALE","Change proposal is no longer pending");
        var current=statements.row(graphId,proposal.statementId());if(current==null||current.revision()!=proposal.expectedRevision()||current.revision()!=request.expectedRevision())throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Change proposal base revision is stale");
        String action=request.action()==null?"":request.action().toUpperCase(Locale.ROOT);Integer resultRevision=null;String status;Set<MemberIdentity> affected=new LinkedHashSet<>();
        if("ACCEPT".equals(action)){
            requireActiveEvidence(graph,statements.decode(proposal.payload()));
            requireNoOpenConflict(graph,new ConflictMember("CHANGE_PROPOSAL",proposalId,proposal.expectedRevision()));
            Approval approval=approveChange(graph,proposal,actor.getId().toString(),request.reason(),Set.of());
            resultRevision=approval.revision();affected.addAll(approval.affected());status="APPROVED";
        }else if("REJECT".equals(action)){
            rejectChange(proposalId);status="REJECTED";
        }else throw StatementApplicationService.bad("Unsupported change review action");
        statements.touch(graph);
        affected.add(MemberIdentity.change(proposalId));
        reconcileOpenConflicts(graph,affected,actor.getId().toString(),request.reason(),null);
        ChangeView result=new ChangeView(proposalId,graphId,proposal.statementId(),proposal.expectedRevision(),status,resultRevision,proposal.proposedBy(),proposal.createdAt().toInstant(ZoneOffset.UTC),statements.decode(proposal.payload()));
        statements.command(graphId,request.operationId(),"REVIEW_CHANGE",commandPayload,result);
        governance.append(graphId,graph.getWorkspaceId(),"CHANGE_PROPOSAL",proposalId,resultRevision==null?proposal.expectedRevision():resultRevision,"REVIEW_CHANGE",request.operationId(),actor.getId().toString(),request.reason(),wire.encode(result));return result;
    }

    @Transactional
    public ConflictView resolve(String scope,String graphId,String conflictId,ResolveRequest request){
        var actor=access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,true);StatementApplicationService.requireEnabled(graph);
        StatementApplicationService.requireOperation(request==null?null:request.operationId());
        if(request.winnerStatementId()==null||request.expectedMembers()==null||request.expectedMembers().size()!=2||request.reason()==null||request.reason().isBlank())throw StatementApplicationService.bad("Winner, two exact members, and reason are required");
        if(request.expectedMembers().stream().anyMatch(Objects::isNull))throw StatementApplicationService.bad("Conflict members cannot be null");
        Object commandPayload=List.of(conflictId,request);String hash=StatementApplicationService.hash(wire.encode(commandPayload));
        ConflictView replay=replay(graphId,conflictId,request.operationId(),hash,legacyResolveHash(request),"RESOLVE_CONFLICT",ConflictView.class);if(replay!=null)return replay;
        List<ConflictView> found=jdbc.query("SELECT * FROM mate_semantic_conflict WHERE id=? AND graph_id=? FOR UPDATE",(rs,n)->conflictView(rs),conflictId,graphId);
        if(found.isEmpty())throw StatementApplicationService.notFound();ConflictView conflict=found.getFirst();
        if(!"OPEN".equals(conflict.status()))throw StatementApplicationService.conflict("CONFLICT_STALE","Conflict was already resolved");
        Set<String> expected=new HashSet<>();for(ConflictMember member:request.expectedMembers())expected.add(memberKey(member));
        Set<String> actual=Set.of(memberKey(conflict.left()),memberKey(conflict.right()));
        if(!expected.equals(actual))throw StatementApplicationService.conflict("CONFLICT_MEMBERS_STALE","Conflict members changed");
        MemberState leftState=memberState(graphId,conflict.left(),true),rightState=memberState(graphId,conflict.right(),true);
        if(!leftState.active()||!rightState.active()||!memberKey(leftState.ref()).equals(memberKey(conflict.left()))||!memberKey(rightState.ref()).equals(memberKey(conflict.right())))throw StatementApplicationService.conflict("CONFLICT_MEMBERS_STALE","Conflict member is not current");
        List<MemberState> members=List.of(leftState,rightState);
        List<MemberState> winners=members.stream().filter(member->member.ref().statementId().equals(request.winnerStatementId())).toList();
        if(winners.size()!=1)throw StatementApplicationService.bad("Winner must identify exactly one conflict member");
        MemberState winner=winners.getFirst(),loser=members.get(0)==winner?members.get(1):members.get(0);
        requireActiveEvidence(graph,winner.content());
        Set<String> ignoredAccepted=loser.statement()!=null&&"ACCEPTED".equals(loser.statement().status())?Set.of(loser.statement().statementId()):Set.of();
        String winnerStatementId=winner.statement()!=null?winner.statement().statementId():winner.change().statementId();
        ensureNoAcceptedConflict(graph,winner.content(),winnerStatementId,ignoredAccepted);
        Set<MemberIdentity> affected=new LinkedHashSet<>();
        rejectMember(graph,loser,actor.getId().toString(),request.reason(),affected);
        StatementView winnerResult=acceptMember(graph,winner,actor.getId().toString(),request.reason(),affected);
        String resolution=wire.encode(Map.of("winnerKind",winner.ref().kind(),"winnerStatementId",request.winnerStatementId(),"reason",request.reason()));
        if(jdbc.update("UPDATE mate_semantic_conflict SET status='RESOLVED',resolution_json=?,resolved_by=?,resolved_at=? WHERE id=? AND status='OPEN'",resolution,actor.getId().toString(),StatementApplicationService.now(),conflictId)!=1)throw StatementApplicationService.conflict("CONFLICT_STALE","Conflict was already resolved");
        reconcileOpenConflicts(graph,affected,actor.getId().toString(),request.reason(),conflictId);
        statements.touch(graph);ConflictView result=new ConflictView(conflict.id(),graphId,conflict.kind(),"RESOLVED",conflict.left(),conflict.right(),resolution);
        statements.command(graphId,request.operationId(),"RESOLVE_CONFLICT",commandPayload,result);
        governance.append(graphId,graph.getWorkspaceId(),"CONFLICT",conflictId,winnerResult.revision(),"RESOLVE_CONFLICT",request.operationId(),actor.getId().toString(),request.reason(),wire.encode(result));return result;
    }

    private void validateAcceptance(GraphRow graph,StatementApplicationService.StoredRevision current,String statementId){
        if(current.contentJson()==null)throw StatementApplicationService.notFound();ProposeRequest request=statements.decode(current.contentJson());
        requireActiveEvidence(graph,request);
        requireNoOpenConflict(graph,new ConflictMember("STATEMENT",statementId,current.revision()));
        ensureNoAcceptedConflict(graph,request,statementId);
    }
    private void ensureNoAcceptedConflict(GraphRow graph,ProposeRequest candidate,String own){
        ensureNoAcceptedConflict(graph,candidate,own,Set.of());
    }
    private void ensureNoAcceptedConflict(GraphRow graph,ProposeRequest candidate,String own,Set<String> ignored){
        domain.validate(graph,domain.content(graph,candidate));
        var content=domain.content(graph,candidate);var ontology=domain.ontology(graph);for(var other:statements.current(graph.getId(),List.of("ACCEPTED"))){if(other.statementId().equals(own)||ignored.contains(other.statementId()))continue;domain.compare(ontology,graph,content,domain.content(graph,statements.decode(other.contentJson()))).ifPresent(kind->{throw StatementApplicationService.conflict("FACT_CONFLICT","Accepted fact conflicts with current graph");});}
    }
    private void requireActiveEvidence(GraphRow graph,ProposeRequest request){
        if(request.evidenceIds()==null||request.evidenceIds().isEmpty())throw new SemanticApiException(422,"EVIDENCE_REQUIRED","Accepted facts require evidence");
        if(!support.supported(graph,request.evidenceIds()))throw new SemanticApiException(422,"EVIDENCE_UNSUPPORTED","No active evidence supports this fact");
    }
    private void requireNoOpenConflict(GraphRow graph,ConflictMember member){
        Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_conflict WHERE graph_id=? AND status='OPEN' AND ((left_member_kind=? AND left_statement_id=?) OR (right_member_kind=? AND right_statement_id=?))",Integer.class,graph.getId(),member.kind(),member.statementId(),member.kind(),member.statementId());
        if(open!=null&&open>0)throw StatementApplicationService.conflict("OPEN_CONFLICT","Resolve deterministic conflicts before acceptance");
    }
    private Approval approveChange(GraphRow graph,ChangeRow proposal,String actor,String reason,Set<String> ignoredAccepted){
        var current=statements.row(graph.getId(),proposal.statementId());if(current==null||current.revision()!=proposal.expectedRevision()||!"PENDING".equals(proposal.status()))throw StatementApplicationService.conflict("PROPOSAL_STALE","Change proposal base revision is stale");
        ProposeRequest content=statements.decode(proposal.payload());domain.validate(graph,domain.content(graph,content));requireActiveEvidence(graph,content);ensureNoAcceptedConflict(graph,content,proposal.statementId(),ignoredAccepted);
        List<String> siblings=stalePendingProposals(graph.getId(),proposal.statementId(),proposal.expectedRevision(),proposal.id());
        int next=current.revision()+1;statements.insertRevision(graph,proposal.statementId(),next,content,"ACCEPTED",actor,reason,StatementApplicationService.now());
        if(jdbc.update("UPDATE mate_semantic_statement SET current_revision=? WHERE id=? AND current_revision=?",next,proposal.statementId(),current.revision())!=1)throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Statement changed concurrently");
        if(jdbc.update("UPDATE mate_semantic_change_proposal SET status='APPROVED',result_revision=? WHERE id=? AND status='PENDING'",next,proposal.id())!=1)throw StatementApplicationService.conflict("PROPOSAL_STALE","Change proposal is no longer pending");
        Set<MemberIdentity> affected=new LinkedHashSet<>();affected.add(MemberIdentity.statement(proposal.statementId()));affected.add(MemberIdentity.change(proposal.id()));siblings.forEach(id->affected.add(MemberIdentity.change(id)));return new Approval(next,Set.copyOf(affected));
    }
    private void rejectChange(String proposalId){if(jdbc.update("UPDATE mate_semantic_change_proposal SET status='REJECTED' WHERE id=? AND status='PENDING'",proposalId)!=1)throw StatementApplicationService.conflict("PROPOSAL_STALE","Change proposal is no longer pending");}
    private List<String> pendingProposalIds(String graphId,String statementId,int expectedRevision,String excluded){return jdbc.query("SELECT id FROM mate_semantic_change_proposal WHERE graph_id=? AND target_statement_id=? AND expected_revision=? AND id<>? AND status='PENDING'",(rs,n)->rs.getString(1),graphId,statementId,expectedRevision,excluded==null?"":excluded);}
    private List<String> stalePendingProposals(String graphId,String statementId,int expectedRevision,String excluded){List<String> ids=pendingProposalIds(graphId,statementId,expectedRevision,excluded);if(!ids.isEmpty()){String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));jdbc.update("UPDATE mate_semantic_change_proposal SET status='STALE' WHERE id IN ("+placeholders+") AND status='PENDING'",ids.toArray());}return ids;}
    private void rejectMember(GraphRow graph,MemberState member,String actor,String reason,Set<MemberIdentity> affected){
        affected.add(MemberIdentity.of(member.ref()));
        if(member.statement()!=null){writeDecision(graph,member.statement(),"ACCEPTED".equals(member.statement().status())?"RETRACTED":"REJECTED",actor,reason);stalePendingProposals(graph.getId(),member.statement().statementId(),member.statement().revision(),"").forEach(id->affected.add(MemberIdentity.change(id)));}
        else rejectChange(member.change().id());
    }
    private StatementView acceptMember(GraphRow graph,MemberState member,String actor,String reason,Set<MemberIdentity> affected){
        affected.add(MemberIdentity.of(member.ref()));
        if(member.statement()!=null){if("ACCEPTED".equals(member.statement().status()))return statements.view(graph,member.statement());StatementView result=writeDecision(graph,member.statement(),"ACCEPTED",actor,reason);stalePendingProposals(graph.getId(),member.statement().statementId(),member.statement().revision(),"").forEach(id->affected.add(MemberIdentity.change(id)));return result;}
        Approval approval=approveChange(graph,member.change(),actor,reason,Set.of());affected.addAll(approval.affected());return statements.view(graph,statements.row(graph.getId(),member.change().statementId()));
    }
    private void reconcileOpenConflicts(GraphRow graph,Set<MemberIdentity> memberIds,String actor,String reason,String excludedConflictId){
        if(memberIds.isEmpty())return;
        List<String> clauses=new ArrayList<>();List<Object> args=new ArrayList<>();args.add(graph.getId());for(MemberIdentity member:memberIds){clauses.add("(left_member_kind=? AND left_statement_id=?)");args.add(member.kind());args.add(member.id());clauses.add("(right_member_kind=? AND right_statement_id=?)");args.add(member.kind());args.add(member.id());}
        List<ConflictView> conflicts=jdbc.query("SELECT * FROM mate_semantic_conflict WHERE graph_id=? AND status='OPEN' AND ("+String.join(" OR ",clauses)+") FOR UPDATE",(rs,n)->conflictView(rs),args.toArray());
        var ontology=domain.ontology(graph);
        Map<String,String> retainedPairs=new HashMap<>();
        for(ConflictView conflict:conflicts){
            if(Objects.equals(conflict.id(),excludedConflictId))continue;
            MemberState left=memberState(graph.getId(),conflict.left(),true),right=memberState(graph.getId(),conflict.right(),true);
            var finding=left.active()&&right.active()?domain.compare(ontology,graph,domain.content(graph,left.content()),domain.content(graph,right.content())):Optional.<vip.mate.semantic.core.conflict.ConflictKind>empty();
            if(finding.isPresent()){
                String pairKey=pairKey(left.ref(),right.ref()),retained=retainedPairs.putIfAbsent(pairKey,conflict.id());
                if(retained==null)jdbc.update("UPDATE mate_semantic_conflict SET kind=?,left_member_kind=?,left_statement_id=?,left_revision=?,right_member_kind=?,right_statement_id=?,right_revision=? WHERE id=? AND status='OPEN'",finding.get().name(),left.ref().kind(),left.ref().statementId(),left.ref().revision(),right.ref().kind(),right.ref().statementId(),right.ref().revision(),conflict.id());
                else{String resolution=wire.encode(Map.of("reason","Superseded by equivalent open conflict","conflictId",retained));jdbc.update("UPDATE mate_semantic_conflict SET status='RESOLVED',resolution_json=?,resolved_by=?,resolved_at=? WHERE id=? AND status='OPEN'",resolution,actor,StatementApplicationService.now(),conflict.id());}
            }
            else{String resolution=wire.encode(Map.of("reason","Member state changed: "+reason));jdbc.update("UPDATE mate_semantic_conflict SET status='RESOLVED',resolution_json=?,resolved_by=?,resolved_at=? WHERE id=? AND status='OPEN'",resolution,actor,StatementApplicationService.now(),conflict.id());}
        }
    }
    private MemberState memberState(String graphId,ConflictMember reference,boolean markStale){
        if("STATEMENT".equals(reference.kind())){var row=statements.row(graphId,reference.statementId());return row==null?new MemberState(reference,false,null,null,null):new MemberState(new ConflictMember("STATEMENT",row.statementId(),row.revision()),active(row),statements.decode(row.contentJson()),row,null);}
        if(!"CHANGE_PROPOSAL".equals(reference.kind()))throw StatementApplicationService.conflict("CONFLICT_MEMBER_KIND_INVALID","Unsupported conflict member kind");
        ChangeRow change=changeRow(graphId,reference.statementId());if(change==null)return new MemberState(reference,false,null,null,null);var target=statements.row(graphId,change.statementId());
        if("APPROVED".equals(change.status())&&target!=null)return new MemberState(new ConflictMember("STATEMENT",target.statementId(),target.revision()),active(target),statements.decode(target.contentJson()),target,change);
        boolean current="PENDING".equals(change.status())&&target!=null&&target.revision()==change.expectedRevision();
        if(markStale&&"PENDING".equals(change.status())&&!current){jdbc.update("UPDATE mate_semantic_change_proposal SET status='STALE' WHERE id=? AND status='PENDING'",change.id());change=new ChangeRow(change.id(),change.statementId(),change.expectedRevision(),change.payload(),"STALE",change.resultRevision(),change.proposedBy(),change.createdAt());}
        return new MemberState(new ConflictMember("CHANGE_PROPOSAL",change.id(),change.expectedRevision()),current,statements.decode(change.payload()),null,change);
    }
    private ChangeRow changeRow(String graphId,String proposalId){List<ChangeRow> rows=jdbc.query("SELECT * FROM mate_semantic_change_proposal WHERE id=? AND graph_id=?",(rs,n)->new ChangeRow(rs.getString("id"),rs.getString("target_statement_id"),rs.getInt("expected_revision"),rs.getString("payload_json"),rs.getString("status"),(Integer)rs.getObject("result_revision"),rs.getString("proposed_by"),rs.getTimestamp("created_at").toLocalDateTime()),proposalId,graphId);return rows.isEmpty()?null:rows.getFirst();}
    private static ConflictView conflictView(java.sql.ResultSet rs)throws java.sql.SQLException{return new ConflictView(rs.getString("id"),rs.getString("graph_id"),rs.getString("kind"),rs.getString("status"),new ConflictMember(rs.getString("left_member_kind"),rs.getString("left_statement_id"),rs.getInt("left_revision")),new ConflictMember(rs.getString("right_member_kind"),rs.getString("right_statement_id"),rs.getInt("right_revision")),rs.getString("resolution_json"));}
    private static boolean active(StatementApplicationService.StoredRevision row){return row!=null&&Set.of("PROPOSED","ACCEPTED").contains(row.status());}
    private static String memberKey(ConflictMember member){return member.kind()+":"+member.statementId()+":"+member.revision();}
    private static String pairKey(ConflictMember left,ConflictMember right){String a=left.kind()+":"+left.statementId(),b=right.kind()+":"+right.statementId();return a.compareTo(b)<=0?a+"|"+b:b+"|"+a;}
    private StatementView writeDecision(GraphRow graph,StatementApplicationService.StoredRevision current,String status,String actor,String reason){
        int next=current.revision()+1;ProposeRequest content=statements.decode(current.contentJson());statements.insertRevision(graph,current.statementId(),next,content,status,actor,reason,StatementApplicationService.now());
        if(jdbc.update("UPDATE mate_semantic_statement SET current_revision=? WHERE id=? AND current_revision=?",next,current.statementId(),current.revision())!=1)throw StatementApplicationService.conflict("STATEMENT_VERSION_CONFLICT","Statement changed concurrently");
        statements.touch(graph);return statements.view(graph,statements.row(graph.getId(),current.statementId()));
    }
    private void validate(ReviewRequest request){if(request==null||request.expectedRevision()==null||request.expectedRevision()<1||request.action()==null||request.reason()==null||request.reason().isBlank())throw StatementApplicationService.bad("Review action, expectedRevision, and reason are required");StatementApplicationService.requireOperation(request.operationId());}
    private String legacyResolveHash(ResolveRequest request){
        if(request.expectedMembers().stream().anyMatch(member->!"STATEMENT".equals(member.kind())))return null;
        return StatementApplicationService.hash(wire.encode(new LegacyResolveRequest(request.winnerStatementId(),request.expectedMembers().stream().map(member->new LegacyConflictMember(member.statementId(),member.revision())).toList(),request.reason(),request.operationId())));
    }
    private <T>T replay(String graph,String resourceId,String operation,String hash,String legacyHash,String kind,Class<T> type){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT kind,payload_hash,result_json FROM mate_semantic_mutation_command WHERE graph_id=? AND operation_id=?",graph,operation);
        if(rows.isEmpty())return null;
        Map<String,Object> row=rows.getFirst();String storedHash=(String)row.get("payload_hash");
        if(!kind.equals(row.get("kind"))||(!hash.equals(storedHash)&&!Objects.equals(legacyHash,storedHash)))throw StatementApplicationService.conflict("OPERATION_CONFLICT","Operation id has different review payload");
        T result=wire.decode((String)row.get("result_json"),type);
        if(!resourceId.equals(resourceId(result)))throw StatementApplicationService.conflict("OPERATION_CONFLICT","Operation id belongs to a different resource");
        return result;
    }
    private record LegacyConflictMember(String statementId,Integer revision){}
    private record LegacyResolveRequest(String winnerStatementId,List<LegacyConflictMember> expectedMembers,String reason,String operationId){}
    private static String resourceId(Object result){if(result instanceof StatementView view)return view.id();if(result instanceof ChangeView view)return view.id();if(result instanceof ConflictView view)return view.id();return null;}
    private record Approval(int revision,Set<MemberIdentity> affected){}
    private record MemberState(ConflictMember ref,boolean active,ProposeRequest content,StatementApplicationService.StoredRevision statement,ChangeRow change){}
    private record MemberIdentity(String kind,String id){static MemberIdentity statement(String id){return new MemberIdentity("STATEMENT",id);}static MemberIdentity change(String id){return new MemberIdentity("CHANGE_PROPOSAL",id);}static MemberIdentity of(ConflictMember member){return new MemberIdentity(member.kind(),member.statementId());}}
    private record ChangeRow(String id,String statementId,int expectedRevision,String payload,String status,Integer resultRevision,String proposedBy,java.time.LocalDateTime createdAt){}
}
