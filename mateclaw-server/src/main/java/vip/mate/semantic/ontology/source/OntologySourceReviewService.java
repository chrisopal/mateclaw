package vip.mate.semantic.ontology.source;

import static vip.mate.semantic.ontology.source.OntologySourceDtos.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.ontology.*;
import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.CommandRecordRow;
import vip.mate.semantic.statement.repository.CommandRecordMapper;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.OntologyDtos.SaveDraft;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.application.extraction.SuggestionValidator;
import vip.mate.semantic.application.extraction.ExtractionContracts.Quote;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

/** Source change review never rewrites a published axiom or its original snapshot. */
@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class OntologySourceReviewService {
    private final JdbcTemplate jdbc;private final SemanticAccessService access;private final OntologyMapper mapper;
    private final OntologyApplicationService ontologies;private final OntologyWireMapper wire;
    private final CommandRecordMapper commands;private final WikiKnowledgeBaseService knowledgeBases;
    public OntologySourceReviewService(JdbcTemplate jdbc,SemanticAccessService access,OntologyMapper mapper,
            OntologyApplicationService ontologies,OntologyWireMapper wire,CommandRecordMapper commands,WikiKnowledgeBaseService knowledgeBases) {
        this.jdbc=jdbc;this.access=access;this.mapper=mapper;this.ontologies=ontologies;this.wire=wire;this.commands=commands;this.knowledgeBases=knowledgeBases;
    }
    private record Material(String title,String text,String digest) {}
    private record Stored(Binding binding,String text,String title,Instant capturedAt) {}

    @Transactional
    public Bound bind(String scope,String ontologyId,BindRequest request) {
        var actor=access.require(scope,"member");var parent=parent(scope,ontologyId,true);
        if(request==null)throw bad("Binding request required");
        var replay=replay(parent,request.operationId(),"BIND_AXIOM_SOURCE",request,Bound.class);if(replay!=null)return replay;
        if(request.expectedDraftVersion()==null||request.axiomId()==null||!Set.of("EXTRACTED","EXPERT","INFERRED").contains(Objects.toString(request.origin(),"")))throw bad("Draft version, axiom and explicit origin required");
        var draft=ontologies.getDraft(scope,ontologyId);
        if(draft.draftVersion()!=request.expectedDraftVersion())throw conflict("DRAFT_CONFLICT");
        if(draft.document().axioms().stream().noneMatch(a->a.axiomId().equals(request.axiomId())))throw bad("Axiom is not in this draft");
        var material=material(scope,request.knowledgeBaseId(),request.sourceRef(),true);
        if(material==null)throw missing();
        if(!material.digest().equals(request.expectedSourceDigest()))throw conflict("SOURCE_CHANGED");
        if(!SuggestionValidator.matches(material.text(),new Quote(request.startCodePoint(),request.endCodePoint(),request.exactQuote())))throw bad("Exact quote and code point range do not match current source");
        var updated=ontologies.saveDraft(scope,ontologyId,new SaveDraft(request.expectedDraftVersion(),draft.name(),draft.description(),draft.document().source(),"source-save:"+OntologyDocument.sha256(request.operationId())));
        String snapshot=capture(scope,request.knowledgeBaseId(),request.sourceRef(),material,actor.getId().toString());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_semantic_axiom_source(id,revision_id,axiom_id,source_snapshot_id,source_digest,exact_quote,start_code_point,end_code_point,origin,review_state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id,draft.id(),request.axiomId(),snapshot,material.digest(),request.exactQuote(),request.startCodePoint(),request.endCodePoint(),request.origin(),"PENDING",actor.getId().toString(),LocalDateTime.now(ZoneOffset.UTC));
        createReview(id,material.digest(),"CURRENT",snapshot);
        var result=new Bound(updated.draftVersion(),binding(scope,ontologyId,id));
        record(parent,request.operationId(),"BIND_AXIOM_SOURCE",request,result);return result;
    }
    @Transactional(readOnly=true)
    public List<Binding> bindings(String scope,String ontologyId,String revisionId) {
        access.require(scope,"viewer");parent(scope,ontologyId,false);
        if(mapper.revision(revisionId,ontologyId)==null)throw missing();
        return jdbc.query("SELECT id FROM mate_semantic_axiom_source WHERE revision_id=? ORDER BY id",(rs,n)->rs.getString(1),revisionId)
            .stream().map(id->stored(scope,ontologyId,id).binding()).toList();
    }
    /** Lock source rows through the migration commit, including a current read under MySQL RR. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<Binding> bindingsForMigration(String scope,String ontologyId,String revisionId) {
        access.require(scope,"admin");parent(scope,ontologyId,false);
        if(mapper.revision(revisionId,ontologyId)==null)throw missing();
        return jdbc.query("SELECT id FROM mate_semantic_axiom_source WHERE revision_id=? ORDER BY id",(rs,n)->rs.getString(1),revisionId)
            .stream().map(id->stored(scope,ontologyId,id,true).binding()).toList();
    }
    public Binding binding(String scope,String ontologyId,String id) {
        access.require(scope,"viewer");return stored(scope,ontologyId,id).binding();
    }
    public Snapshot snapshotAsAgent(String scope,String actor,Long agent,String ontologyId,String bindingId) {
        access.requireActor(scope,actor,"viewer");var source=stored(scope,ontologyId,bindingId);
        var kb=knowledgeBases.findVisibleById(agent,positive(source.binding().knowledgeBaseId()));
        if(kb==null||!Objects.equals(kb.getWorkspaceId(),Long.valueOf(scope)))throw missing();
        var b=source.binding();return new Snapshot(b.sourceSnapshotId(),b.knowledgeBaseId(),b.sourceRef(),source.title(),source.text(),b.sourceDigest(),source.capturedAt());
    }
    public List<String> visibleBindingIds(String scope,String actor,Long agent,String revision,String axiom) {
        access.requireActor(scope,actor,"viewer");
        return jdbc.query("SELECT b.id,s.kb_id FROM mate_semantic_axiom_source b JOIN mate_semantic_ontology_source_snapshot s ON s.id=b.source_snapshot_id WHERE b.revision_id=? AND b.axiom_id=? AND s.workspace_id=? ORDER BY b.id",
            (rs,n)->Map.entry(rs.getString(1),rs.getLong(2)),revision,axiom,Long.valueOf(scope)).stream()
            .filter(row->{var kb=knowledgeBases.findVisibleById(agent,row.getValue());return kb!=null&&Objects.equals(kb.getWorkspaceId(),Long.valueOf(scope));})
            .map(Map.Entry::getKey).toList();
    }
    public List<Binding> visibleBindings(String scope,String actor,Long agent,String ontology,String revision,String axiom) {
        return visibleBindingIds(scope,actor,agent,revision,axiom).stream().map(id->stored(scope,ontology,id).binding()).toList();
    }
    @Transactional
    public List<Review> scan(String scope,String ontologyId,ScanRequest request) {
        var actor=access.require(scope,"member");var parent=parent(scope,ontologyId,true);
        if(request==null)throw bad("Scan request required");
        var previous=replay(parent,request.operationId(),"SCAN_ONTOLOGY_SOURCES",request,Review[].class);
        if(previous!=null)return List.of(previous);
        var ids=jdbc.query("SELECT b.id FROM mate_semantic_axiom_source b JOIN mate_semantic_ontology_revision r ON r.id=b.revision_id WHERE r.ontology_id=? ORDER BY b.id",(rs,n)->rs.getString(1),ontologyId);
        if(ids.size()>10000)throw bad("Source review limit exceeded");
        for(String id:ids) {
            var old=stored(scope,ontologyId,id).binding();var current=material(scope,old.knowledgeBaseId(),old.sourceRef(),false);
            String digest=current==null?OntologyDocument.sha256("SOURCE_UNAVAILABLE"):current.digest();
            String snapshot=current==null?null:capture(scope,old.knowledgeBaseId(),old.sourceRef(),current,actor.getId().toString());
            createReview(id,digest,current==null?"UNAVAILABLE":digest.equals(old.sourceDigest())?"CURRENT":"CHANGED",snapshot);
        }
        var result=reviews(scope,ontologyId);record(parent,request.operationId(),"SCAN_ONTOLOGY_SOURCES",request,result);return result;
    }
    @Transactional(readOnly=true)
    public List<Review> reviews(String scope,String ontologyId) {
        access.require(scope,"viewer");parent(scope,ontologyId,false);
        return jdbc.query("SELECT v.* FROM mate_semantic_ontology_source_review v JOIN mate_semantic_axiom_source b ON b.id=v.binding_id JOIN mate_semantic_ontology_revision r ON r.id=b.revision_id WHERE r.ontology_id=? ORDER BY v.created_at,v.id",
            (rs,n)->new Review(rs.getString("id"),rs.getString("binding_id"),rs.getString("observed_digest"),rs.getString("source_state"),rs.getString("review_state"),rs.getString("decision"),rs.getString("reason"),rs.getString("observed_snapshot_id")),ontologyId);
    }
    @Transactional
    public Review decide(String scope,String ontologyId,String reviewId,DecideRequest request) {
        var actor=access.require(scope,"admin");var parent=parent(scope,ontologyId,true);
        if(request==null)throw bad("Review request required");
        var previous=replay(parent,request.operationId(),"REVIEW_ONTOLOGY_SOURCE",List.of(reviewId,request),Review.class);if(previous!=null)return previous;
        if(!Set.of("ACKNOWLEDGE","REMODEL","KEEP_HISTORICAL").contains(Objects.toString(request.decision(),""))||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)throw bad("Explicit decision and reason required");
        var item=reviews(scope,ontologyId).stream().filter(r->r.id().equals(reviewId)).findFirst().orElseThrow(OntologySourceReviewService::missing);
        if(!item.reviewState().equals("PENDING")||!item.observedDigest().equals(request.expectedObservedDigest()))throw conflict("SOURCE_REVIEW_STALE");
        var bound=stored(scope,ontologyId,item.bindingId()).binding();var current=material(scope,bound.knowledgeBaseId(),bound.sourceRef(),true);
        String currentDigest=current==null?OntologyDocument.sha256("SOURCE_UNAVAILABLE"):current.digest();
        if(!currentDigest.equals(item.observedDigest()))throw conflict("SOURCE_REVIEW_STALE");
        jdbc.update("UPDATE mate_semantic_ontology_source_review SET review_state='REVIEWED',decision=?,reason=?,reviewed_by=?,reviewed_at=? WHERE id=? AND review_state='PENDING'",
            request.decision(),request.reason(),actor.getId().toString(),LocalDateTime.now(ZoneOffset.UTC),reviewId);
        jdbc.update("UPDATE mate_semantic_axiom_source SET review_state=? WHERE id=?",request.decision(),item.bindingId());
        var result=new Review(item.id(),item.bindingId(),item.observedDigest(),item.sourceState(),"REVIEWED",request.decision(),request.reason(),item.observedSnapshotId());
        record(parent,request.operationId(),"REVIEW_ONTOLOGY_SOURCE",List.of(reviewId,request),result);return result;
    }
    private void createReview(String binding,String digest,String state,String snapshot) {
        jdbc.update("UPDATE mate_semantic_ontology_source_review SET review_state='STALE' WHERE binding_id=? AND observed_digest<>? AND review_state='PENDING'",binding,digest);
        jdbc.update("UPDATE mate_semantic_ontology_source_review SET review_state='PENDING' WHERE binding_id=? AND observed_digest=? AND review_state='STALE'",binding,digest);
        var existing=jdbc.query("SELECT review_state FROM mate_semantic_ontology_source_review WHERE binding_id=? AND observed_digest=?",(rs,n)->rs.getString(1),binding,digest);
        if(existing.isEmpty()) jdbc.update("INSERT INTO mate_semantic_ontology_source_review(id,binding_id,observed_digest,source_state,review_state,created_at,observed_snapshot_id) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),binding,digest,state,"PENDING",LocalDateTime.now(ZoneOffset.UTC),snapshot);
        if(snapshot!=null) jdbc.update("UPDATE mate_semantic_ontology_source_review SET observed_snapshot_id=? WHERE binding_id=? AND observed_digest=? AND observed_snapshot_id IS NULL",snapshot,binding,digest);
        if(existing.isEmpty() || !existing.getFirst().equals("REVIEWED"))
            jdbc.update("UPDATE mate_semantic_axiom_source SET review_state='PENDING' WHERE id=?",binding);
    }

    private String capture(String scope,String kb,String source,Material material,String actor) {
        // Serialize snapshot deduplication across different ontology locks within one workspace.
        jdbc.queryForObject("SELECT id FROM mate_workspace WHERE id=? FOR UPDATE",Long.class,Long.valueOf(scope));
        var existing=jdbc.query("SELECT id FROM mate_semantic_ontology_source_snapshot WHERE workspace_id=? AND kb_id=? AND source_ref=? AND source_digest=? ORDER BY id",
                (rs,n)->rs.getString(1),Long.valueOf(scope),positive(kb),source,material.digest());
        if(!existing.isEmpty())return existing.getFirst();
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_semantic_ontology_source_snapshot(id,workspace_id,kb_id,source_ref,source_title,source_text,source_digest,captured_by,captured_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,Long.valueOf(scope),positive(kb),source,material.title(),material.text(),material.digest(),actor,LocalDateTime.now(ZoneOffset.UTC));
        return id;
    }
    @Transactional(readOnly=true)
    public Map<String,Snapshot> reviewSnapshots(String scope,String ontologyId,String reviewId) {
        access.require(scope,"viewer");parent(scope,ontologyId,false);
        var review=reviews(scope,ontologyId).stream().filter(r->r.id().equals(reviewId)).findFirst().orElseThrow(OntologySourceReviewService::missing);
        var binding=stored(scope,ontologyId,review.bindingId()).binding();
        Map<String,Snapshot> result=new LinkedHashMap<>();result.put("original",snapshot(scope,binding.sourceSnapshotId()));
        if(review.observedSnapshotId()!=null)result.put("observed",snapshot(scope,review.observedSnapshotId()));
        return Map.copyOf(result);
    }
    private Snapshot snapshot(String scope,String id) {
        var rows=jdbc.query("SELECT * FROM mate_semantic_ontology_source_snapshot WHERE id=? AND workspace_id=?",(rs,n)->
            new Snapshot(rs.getString("id"),rs.getString("kb_id"),rs.getString("source_ref"),rs.getString("source_title"),rs.getString("source_text"),rs.getString("source_digest"),rs.getTimestamp("captured_at").toLocalDateTime().toInstant(ZoneOffset.UTC)),id,Long.valueOf(scope));
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }
    private Stored stored(String scope,String ontology,String id) {
        return stored(scope,ontology,id,false);
    }
    private Stored stored(String scope,String ontology,String id,boolean lockSource) {
        var rows=jdbc.query("SELECT b.*,s.kb_id,s.source_ref,s.source_text,s.source_title,s.captured_at FROM mate_semantic_axiom_source b JOIN mate_semantic_ontology_revision r ON r.id=b.revision_id JOIN mate_semantic_ontology o ON o.id=r.ontology_id JOIN mate_semantic_ontology_source_snapshot s ON s.id=b.source_snapshot_id AND s.workspace_id=o.workspace_id WHERE b.id=? AND o.id=? AND o.workspace_id=?",
            (rs,n)->new Stored(new Binding(rs.getString("id"),rs.getString("revision_id"),rs.getString("axiom_id"),rs.getString("source_snapshot_id"),rs.getString("kb_id"),rs.getString("source_ref"),rs.getString("source_digest"),rs.getString("exact_quote"),rs.getInt("start_code_point"),rs.getInt("end_code_point"),rs.getString("origin"),rs.getString("review_state"),"UNCHECKED"),rs.getString("source_text"),rs.getString("source_title"),rs.getTimestamp("captured_at").toLocalDateTime().toInstant(ZoneOffset.UTC)),id,ontology,Long.valueOf(scope));
        if(rows.size()!=1)throw missing();var old=rows.getFirst();var b=old.binding();
        // Historical snapshots remain immutable; agent reads additionally require current KB visibility.
        var current=material(scope,b.knowledgeBaseId(),b.sourceRef(),lockSource);
        String state=current==null?"UNAVAILABLE":current.digest().equals(b.sourceDigest())?"CURRENT":"CHANGED";
        String observed=current==null?OntologyDocument.sha256("SOURCE_UNAVAILABLE"):current.digest();
        var decisions=jdbc.query("SELECT review_state,decision FROM mate_semantic_ontology_source_review WHERE binding_id=? AND observed_digest=?",
                (rs,n)->"REVIEWED".equals(rs.getString(1))?rs.getString(2):rs.getString(1),id,observed);
        String review=decisions.isEmpty()?"PENDING":decisions.getFirst();
        return new Stored(new Binding(b.id(),b.revisionId(),b.axiomId(),b.sourceSnapshotId(),b.knowledgeBaseId(),b.sourceRef(),b.sourceDigest(),b.exactQuote(),b.startCodePoint(),b.endCodePoint(),b.origin(),review,state),old.text(),old.title(),old.capturedAt());
    }
    private Material material(String scope,String kb,String source,boolean lock) {
        var rows=jdbc.query("SELECT m.title,COALESCE(NULLIF(m.extracted_text,''),m.original_content) AS content FROM mate_wiki_raw_material m JOIN mate_wiki_knowledge_base k ON k.id=m.kb_id WHERE m.id=? AND m.kb_id=? AND k.workspace_id=? AND m.deleted=0 AND k.deleted=0"+(lock?" FOR UPDATE":""),
            (rs,n)->{String text=Objects.toString(rs.getString("content"),"");return new Material(Objects.toString(rs.getString("title"),""),text,OntologyDocument.sha256(text));},positive(source),positive(kb),Long.valueOf(scope));
        if(rows.isEmpty())return null;var result=rows.getFirst();if(result.text().isBlank()||result.text().length()>100000)throw bad("Source must contain 1..100000 parsed characters");return result;
    }
    private OntologyRow parent(String scope,String id,boolean lock){var row=lock?mapper.lock(id,Long.parseLong(scope)):mapper.find(id,Long.parseLong(scope));if(row==null)throw missing();return row;}
    private <T>T replay(OntologyRow parent,String operation,String kind,Object payload,Class<T> type) {
        if(operation==null||operation.isBlank()||operation.length()>128)throw bad("Operation ID required, maximum 128 characters");
        var row=commands.find(parent.getWorkspaceId(),operation);if(row==null)return null;
        if(!row.getKind().equals(kind)||!row.getResourceId().equals(parent.getId())||!row.getPayloadHash().equals(OntologyDocument.sha256(wire.encode(payload))))throw conflict("OPERATION_CONFLICT");
        return wire.decode(row.getResultJson(),type);
    }
    private void record(OntologyRow parent,String operation,String kind,Object payload,Object result) {
        var row=new CommandRecordRow();row.setId(UUID.randomUUID().toString());row.setWorkspaceId(parent.getWorkspaceId());row.setOperationId(operation);row.setKind(kind);row.setResourceId(parent.getId());row.setPayloadHash(OntologyDocument.sha256(wire.encode(payload)));row.setResultJson(wire.encode(result));row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));commands.insert(row);
    }
    private static long positive(String value){try{long id=Long.parseLong(value);if(id<=0)throw bad("Positive source identifiers required");return id;}catch(NumberFormatException e){throw bad("Invalid source identifier");}}
    private static SemanticApiException bad(String message){return new SemanticApiException(422,"INVALID_AXIOM_SOURCE",message);}
    private static SemanticApiException missing(){return new SemanticApiException(404,"SOURCE_UNAVAILABLE","Source or binding is unavailable");}
    private static SemanticApiException conflict(String code){return new SemanticApiException(409,code,"Source or draft changed; refresh before retrying");}
}
