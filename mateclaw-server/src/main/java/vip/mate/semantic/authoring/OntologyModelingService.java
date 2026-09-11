package vip.mate.semantic.authoring;

import static vip.mate.semantic.authoring.OntologyModelingDtos.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.ontology.*;
import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.ontology.source.OntologySourceReviewService;
import vip.mate.semantic.ontology.source.OntologySourceDtos.BindRequest;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.semantic.web.SemanticApiException;

/** Task lock precedes ontology lock, then sorted source locks. No background execution or fact writes. */
@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class OntologyModelingService {
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final SemanticAccessService access;
    private final OntologyApplicationService ontologies;
    private final OntologyMapper mapper;
    private final OntologyWireMapper wire;
    private final OntologySourceReviewService sources;
    public OntologyModelingService(JdbcTemplate jdbc, SemanticAccessService access,
            OntologyApplicationService ontologies, OntologyMapper mapper,
            OntologyWireMapper wire, OntologySourceReviewService sources, org.springframework.transaction.PlatformTransactionManager manager) {
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.jdbc=jdbc; this.access=access; this.ontologies=ontologies;
        this.mapper=mapper; this.wire=wire; this.sources=sources;
    }

    public Task create(String scope, CreateTask input) {
        try { return transactions.execute(status->createReserved(scope,input)); }
        catch(org.springframework.dao.DuplicateKeyException collision) {
            return transactions.execute(status->{
                access.require(scope,"member");
                var ids=jdbc.query("SELECT id,request_digest FROM mate_semantic_modeling_task WHERE workspace_id=? AND operation_id=?",
                        (r,n)->Map.entry(r.getString(1),r.getString(2)),Long.valueOf(scope),input.operationId());
                if(ids.isEmpty())throw collision;
                if(!ids.get(0).getValue().equals(digest(input)))throw conflict("OPERATION_CONFLICT");
                return read(scope,ids.get(0).getKey());
            });
        }
    }
    private Task createReserved(String scope, CreateTask input) {
        access.require(scope,"member");
        if(input==null)throw bad("Task required");
        operation(input.operationId()); text(input.goal(),"Goal",10000);
        if((input.ontologyId()==null)==(input.newOntology()==null))throw bad("Supply an existing ontology or new ontology metadata");
        List<SourceVersion> selected=input.sources()==null?List.of():new ArrayList<>(input.sources());
        if(selected.size()>100)throw bad("At most 100 sources");
        var old=jdbc.query("SELECT id,request_digest FROM mate_semantic_modeling_task WHERE workspace_id=? AND operation_id=?",
                (r,n)->Map.entry(r.getString(1),r.getString(2)),Long.valueOf(scope),input.operationId());
        if(!old.isEmpty()) {
            if(!old.get(0).getValue().equals(digest(input)))throw conflict("OPERATION_CONFLICT");
            return read(scope,old.get(0).getKey());
        }
        String taskId=UUID.randomUUID().toString();
        var now=LocalDateTime.now(ZoneOffset.UTC);
        // Reserve the unique request before creating anything. A concurrent loser retries in a fresh transaction.
        jdbc.update("INSERT INTO mate_semantic_modeling_task(id,workspace_id,operation_id,request_digest,ontology_id,state_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                taskId,Long.valueOf(scope),input.operationId(),digest(input),"RESERVED","{}",now,now);
        String ontology=input.ontologyId()==null?ontologies.create(scope,input.newOntology()).id():input.ontologyId();
        var parent=mapper.lock(ontology,Long.parseLong(scope));
        if(parent==null)throw missing();
        var draft=parent.getDraftId()==null?ontologies.createDraft(scope,ontology,new CreateDraft(input.baseRevisionId())):ontologies.getDraft(scope,ontology);
        for(var source:selected) {
            if(source==null)throw bad("Source required");
            text(source.knowledgeBaseId(),"Knowledge base",64);text(source.sourceRef(),"Source reference",128);text(source.sourceDigest(),"Source digest",64);
        }
        var ordered=new ArrayList<>(selected);
        ordered.sort(Comparator.comparing(SourceVersion::knowledgeBaseId).thenComparing(SourceVersion::sourceRef));
        Set<String> unique=new HashSet<>();
        for(var s:ordered) {
            if(!unique.add(s.knowledgeBaseId()+":"+s.sourceRef()))throw bad("Duplicate source");
            var material=sources.materialViewForModeling(scope,ontology,s.knowledgeBaseId(),s.sourceRef());
            if(material==null||!material.sourceDigest().equals(s.sourceDigest()))throw conflict("SOURCE_CHANGED");
        }
        var task=new Task(taskId,ontology,draft.id(),input.goal(),List.copyOf(ordered),"READY",null,List.of());
        jdbc.update("UPDATE mate_semantic_modeling_task SET ontology_id=?,state_json=? WHERE id=?",ontology,wire.encode(task),task.id());
        return task;
    }

    @Transactional
    public Task read(String scope,String id) {
        access.require(scope,"viewer");
        var task=locked(scope,id);
        task=refresh(scope,task);
        return task;
    }

    @Transactional
    public List<Task> list(String scope,String ontologyId) {
        access.require(scope,"viewer");
        if(ontologyId!=null)ontologies.get(scope,ontologyId);
        return jdbc.query("SELECT state_json FROM mate_semantic_modeling_task WHERE workspace_id=? AND (? IS NULL OR ontology_id=?) ORDER BY created_at DESC,id LIMIT 100",
                (r,n)->wire.decode(r.getString(1),Task.class),Long.valueOf(scope),ontologyId,ontologyId);
    }

    @Transactional
    public Task submit(String scope,String id,SubmitProposal input) {
        access.require(scope,"member");
        if(input==null)throw bad("Proposal required");
        operation(input.operationId());
        Task task=locked(scope,id);
        Task replay=replay(task,input.operationId(),input);
        if(replay!=null){checkSources(scope,task);return replay;}
        active(task);
        task=refresh(scope,task);
        var draft=ontologies.getDraft(scope,task.ontologyId());
        if(!draft.id().equals(task.draftId())||input.expectedDraftVersion()==null||draft.draftVersion()!=input.expectedDraftVersion())throw conflict("DRAFT_CONFLICT");
        if(!checkSources(scope,task))throw conflict("SOURCE_CHANGED");
        if(input.changes()==null||input.changes().isEmpty()||input.changes().size()>200)throw bad("Supply 1 to 200 business changes");
        if(wire.encode(input).length()>1000000)throw bad("Proposal too large");
        Set<String> ids=new HashSet<>();
        for(var change:input.changes()) {
            if(change==null)throw bad("Change required");
            text(change.clientId(),"Client id",128);
            if(!ids.add(change.clientId()))throw bad("Duplicate client id");
        }
        var evidence=input.evidence()==null?List.<Evidence>of():input.evidence();
        for(var e:evidence) {
            if(e==null||!ids.contains(e.clientId()))throw bad("Evidence must reference a proposal item");
            if("USER_STATEMENT".equals(e.origin())) {
                if(e.knowledgeBaseId()!=null||e.sourceRef()!=null||e.sourceDigest()!=null||e.exactQuote()==null||e.exactQuote().isBlank()||!task.goal().contains(e.exactQuote()))throw bad("User statement evidence must quote the task goal without source identifiers");
                continue;
            }
            if(!task.sources().contains(new SourceVersion(e.knowledgeBaseId(),e.sourceRef(),e.sourceDigest())))throw bad("Evidence must use a selected source version");
            if(!Set.of("EXTRACTED","EXPERT","INFERRED").contains(Objects.toString(e.origin(),"")))throw bad("Explicit evidence origin required");
            sources.resolveEvidence(scope,task.ontologyId(),e.knowledgeBaseId(),e.sourceRef(),e.sourceDigest(),e.exactQuote(),e.occurrence());
        }
        var questions=input.questions()==null?List.<String>of():new ArrayList<>(input.questions());
        if(questions.size()>100||new HashSet<>(questions).size()!=questions.size())throw bad("Questions must be unique, at most 100");
        questions.forEach(q->text(q,"Question",2000));
        if(input.samples()!=null&&input.samples().stream().anyMatch(Objects::isNull))throw bad("Samples cannot contain null");
        var normalized=new SubmitProposal(input.operationId(),input.expectedDraftVersion(),List.copyOf(input.changes()),List.copyOf(evidence),questions,input.samples()==null?List.of():List.copyOf(input.samples()));
        if(task.proposals().size()>=100)throw bad("At most 100 proposals per task; create a continuation task");
        var proposals=new ArrayList<>(task.proposals());
        proposals.add(new Proposal(UUID.randomUUID().toString(),normalized,"PENDING",null,Map.of(),null));
        task=replace(task,"AWAITING_CONFIRMATION",null,proposals);
        save(task); record(task,input.operationId(),input,task); return task;
    }

    @Transactional
    public Task decide(String scope,String id,String proposalId,Decision input) {
        access.require(scope,"member");
        if(input==null)throw bad("Decision required");
        operation(input.operationId());
        Task task=locked(scope,id);
        Object payload=List.of(proposalId,input);
        Task replay=replay(task,input.operationId(),payload);
        if(replay!=null){checkSources(scope,task);return replay;}
        active(task);
        task=refresh(scope,task);
        Proposal proposal=task.proposals().stream().filter(p->p.id().equals(proposalId)).findFirst().orElseThrow(OntologyModelingService::missing);
        if(proposal.status().equals("STALE")){record(task,input.operationId(),payload,task);return task;}
        if(!proposal.status().equals("PENDING"))throw conflict("PROPOSAL_ALREADY_DECIDED");
        if(!Set.of("ACCEPT","REJECT").contains(Objects.toString(input.decision(),"")))throw bad("Decision must be ACCEPT or REJECT");
        ModelCommandResult result=null;
        if(input.answers()!=null&&input.answers().entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null))throw bad("Answers cannot contain null");
        var answers=input.answers()==null?Map.<String,String>of():Map.copyOf(input.answers());
        if(answers.size()>100||wire.encode(answers).length()>250000)throw bad("Answers too large");
        if(input.decision().equals("ACCEPT")) {
            for(String question:proposal.input().questions())text(answers.get(question),"Answer to "+question,2000);
            var resolved=new ArrayList<vip.mate.semantic.ontology.source.OntologySourceDtos.ResolvedEvidence>();
            for(var e:proposal.input().evidence())resolved.add("USER_STATEMENT".equals(e.origin())?null:sources.resolveEvidence(scope,task.ontologyId(),e.knowledgeBaseId(),e.sourceRef(),e.sourceDigest(),e.exactQuote(),e.occurrence()));
            String operation="model-task:"+proposal.id();
            result=ontologies.applyModelCommands(scope,task.ontologyId(),new ModelEditRequest(proposal.input().expectedDraftVersion(),operation,proposal.input().changes()));
            long version=result.draft().draftVersion();
            int binding=0;
            for(int i=0;i<proposal.input().evidence().size();i++) {
                var evidence=proposal.input().evidence().get(i);var quote=resolved.get(i);
                if("USER_STATEMENT".equals(evidence.origin()))continue;
                var item=result.items().stream().filter(r->Objects.equals(r.clientId(),evidence.clientId())).findFirst().orElseThrow(()->bad("Missing applied evidence target"));
                if(item.axiomIds().isEmpty())throw bad("Evidence target has no resulting axioms");
                for(String axiom:item.axiomIds()) {
                    var bound=sources.bind(scope,task.ontologyId(),new BindRequest(version,"model-evidence:"+proposal.id()+":"+(binding++),axiom,
                            evidence.knowledgeBaseId(),evidence.sourceRef(),evidence.sourceDigest(),quote.startCodePoint(),quote.endCodePoint(),quote.exactQuote(),evidence.origin()));
                    version=bound.draftVersion();
                }
            }
            result=new ModelCommandResult(ontologies.getDraft(scope,task.ontologyId()),result.items());
        }
        var changed=new Proposal(proposal.id(),proposal.input(),input.decision().equals("ACCEPT")?"ACCEPTED":"REJECTED",null,answers,result);
        var proposals=new ArrayList<Proposal>();
        for(var p:task.proposals())proposals.add(p.id().equals(proposal.id())?changed:
                result!=null&&p.status().equals("PENDING")?new Proposal(p.id(),p.input(),"STALE","DRAFT_CHANGED",p.answers(),p.result()):p);
        task=replace(task,"READY",null,proposals); save(task); record(task,input.operationId(),payload,task); return task;
    }

    @Transactional
    public Task stage(String scope,String id,StageChange change) {
        access.require(scope,"member");
        if(change==null||!Set.of("READY","RUNNING","FAILED","CANCELLED").contains(Objects.toString(change.stage(),"")))throw bad("Unsupported task stage");
        Task task=locked(scope,id);
        if(task.stage().equals("CANCELLED")) {
            if(change.stage().equals("CANCELLED"))return task;
            throw conflict("TASK_CANCELLED");
        }
        if(change.message()!=null&&change.message().length()>2000)throw bad("Message too long");
        var proposals=task.proposals();
        if(change.stage().equals("CANCELLED"))proposals=proposals.stream().map(p->p.status().equals("PENDING")?
                new Proposal(p.id(),p.input(),"CANCELLED","TASK_CANCELLED",p.answers(),p.result()):p).toList();
        task=replace(task,change.stage(),change.message(),proposals);save(task);return task;
    }

    private Task locked(String scope,String id) {
        var values=jdbc.query("SELECT state_json FROM mate_semantic_modeling_task WHERE id=? AND workspace_id=? FOR UPDATE",(r,n)->r.getString(1),id,Long.valueOf(scope));
        if(values.isEmpty())throw missing();
        Task task=wire.decode(values.get(0),Task.class);
        if(mapper.lock(task.ontologyId(),Long.parseLong(scope))==null)throw missing();
        return task;
    }
    private Task refresh(String scope,Task task) {
        boolean sourceCurrent=checkSources(scope,task);
        DraftView draft=null;
        try{draft=ontologies.getDraft(scope,task.ontologyId());}catch(SemanticApiException ex){if(ex.status()!=404)throw ex;}
        var changed=new ArrayList<Proposal>();boolean dirty=false;
        for(var p:task.proposals()) {
            String reason=!sourceCurrent?"SOURCE_CHANGED":draft==null||!task.draftId().equals(draft.id())||draft.draftVersion()!=p.input().expectedDraftVersion()?"DRAFT_CHANGED":null;
            if(p.status().equals("PENDING")&&reason!=null){p=new Proposal(p.id(),p.input(),"STALE",reason,p.answers(),p.result());dirty=true;}
            changed.add(p);
        }
        if(dirty){task=replace(task,task.stage().equals("CANCELLED")?"CANCELLED":"READY",task.message(),changed);save(task);}
        return task;
    }
    private boolean checkSources(String scope,Task task) {
        boolean current=true;
        for(var source:task.sources()) {
            var material=sources.materialViewForModeling(scope,task.ontologyId(),source.knowledgeBaseId(),source.sourceRef());
            current&=material!=null&&material.sourceDigest().equals(source.sourceDigest());
        }
        return current;
    }
    private Task replace(Task t,String stage,String message,List<Proposal> proposals){return new Task(t.id(),t.ontologyId(),t.draftId(),t.goal(),t.sources(),stage,message,List.copyOf(proposals));}
    private void save(Task task){jdbc.update("UPDATE mate_semantic_modeling_task SET state_json=?,updated_at=? WHERE id=?",wire.encode(task),LocalDateTime.now(ZoneOffset.UTC),task.id());}
    private Task replay(Task t,String operation,Object payload) {
        var rows=jdbc.query("SELECT request_digest,result_json FROM mate_semantic_modeling_operation WHERE task_id=? AND operation_id=?",(r,n)->Map.entry(r.getString(1),r.getString(2)),t.id(),operation);
        if(rows.isEmpty())return null;
        if(!rows.get(0).getKey().equals(digest(payload)))throw conflict("OPERATION_CONFLICT");
        return wire.decode(rows.get(0).getValue(),Task.class);
    }
    private void record(Task t,String operation,Object input,Task result){jdbc.update("INSERT INTO mate_semantic_modeling_operation(task_id,operation_id,request_digest,result_json) VALUES(?,?,?,?)",t.id(),operation,digest(input),wire.encode(result));}
    private String digest(Object input){return OntologyDocument.sha256(wire.encode(input));}
    private static void active(Task task){if(task.stage().equals("CANCELLED"))throw conflict("TASK_CANCELLED");}
    private static void operation(String id){text(id,"Operation id",128);}
    private static void text(String s,String label,int limit){if(s==null||s.isBlank()||s.length()>limit)throw bad(label+" required and must be within "+limit+" characters");}
    private static SemanticApiException bad(String message){return new SemanticApiException(400,"INVALID_REQUEST",message);}
    private static SemanticApiException conflict(String code){return new SemanticApiException(409,code,code);}
    private static SemanticApiException missing(){return new SemanticApiException(404,"NOT_FOUND","Modeling resource unavailable");}
}
