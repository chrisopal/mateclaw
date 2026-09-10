package vip.mate.semantic.query;

import static vip.mate.semantic.query.SemanticContextDtos.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.StatementView;

/** Version-pinned, permission-checked runtime context; it never publishes or accepts facts. */
@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class SemanticContextService {
    private final SemanticQueryService queries;
    private final vip.mate.semantic.reasoning.SemanticReasoningService reasoning;
    private final org.springframework.transaction.support.TransactionTemplate reads;
    // Bounded, expiring continuations. Eviction requires restart, never silent recomputation.
    private final Map<String,ReasoningRun> reasoningRuns=new LinkedHashMap<>();
    private record ReasoningRun(String id,Instant expiresAt,
            vip.mate.semantic.reasoning.SemanticReasoningService.PinnedResult pinned,List<Conclusion> conclusions) {}
    private record Read(Context context,String snapshotDigest) {}
    private final SemanticDomainMapper domain;
    private final StatementApplicationService statements;
    private final OntologyDocumentPort documents;
    private final vip.mate.semantic.ontology.source.OntologySourceReviewService sources;
    private final JdbcTemplate jdbc;
    private final org.mybatis.spring.SqlSessionTemplate session;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final byte[] cursorKey=new byte[32];
    public SemanticContextService(SemanticQueryService queries,SemanticDomainMapper domain,
            StatementApplicationService statements,OntologyDocumentPort documents,JdbcTemplate jdbc,
            org.mybatis.spring.SqlSessionTemplate session,vip.mate.semantic.ontology.source.OntologySourceReviewService sources,
            vip.mate.semantic.reasoning.SemanticReasoningService reasoning,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.reasoning=reasoning;
        this.reads=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        reads.setReadOnly(true);reads.setTimeout(15);
        reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.sources=sources;
        this.queries=queries;this.domain=domain;this.statements=statements;this.documents=documents;this.jdbc=jdbc;this.session=session;
        new SecureRandom().nextBytes(cursorKey);
    }
    private Map<String,List<FactSourceReview>> factSourceReviews(String graphId) {
        // Called only after requireAgentGraph; never exposes raw changed source text.
        Map<String,List<FactSourceReview>> result=new TreeMap<>();
        var rows=jdbc.query("SELECT i.* FROM mate_semantic_source_change_item i JOIN mate_semantic_statement s ON s.id=i.item_id AND s.current_revision=i.item_revision WHERE i.graph_id=? AND i.item_kind='FACT' AND i.review_state IN ('PENDING','REVIEWED') ORDER BY i.id LIMIT 10001",
            (rs,n)->Map.entry(rs.getString("item_id")+":"+rs.getLong("item_revision"),
                new FactSourceReview(rs.getString("id"),rs.getString("old_snapshot_id"),rs.getString("new_digest"),rs.getString("source_state"),rs.getString("review_state"),rs.getString("decision"))),graphId);
        if(rows.size()>10000)throw new SemanticApiException(422,"CONTEXT_REVIEW_LIMIT","Too many pending source reviews to freeze context");
        rows.forEach(row->result.computeIfAbsent(row.getKey(),ignored->new ArrayList<>()).add(row.getValue()));
        return result;
    }
    private record Cursor(String principal,String graph,String query,long graphVersion,String revision,
                          int offset,int budget,Instant asOf,Instant capturedAt,Instant expiresAt,String snapshotDigest,String reasoningId) {}
    private record Unit(Axiom axiom,StatementView fact,Conclusion conclusion,OntologyAnnotation annotation) {
        Unit(Axiom axiom,StatementView fact,Conclusion conclusion) { this(axiom,fact,conclusion,null); }
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Context context(String scope,String actor,Long agent,String graphId,Request request) {
        if(request==null||request.reasoning()==null)
            return reads.execute(status->readContext(scope,actor,agent,graphId,request,null,null)).context();
        ReasoningRun run;
        Read before=null;
        if(request.cursor()!=null) {
            // Authorization precedes cache access; readContext also validates the signed principal/query.
            reads.executeWithoutResult(status->queries.requireAgentGraph(scope,actor,agent,graphId));
            run=cachedRun(decodeCursor(request.cursor()).reasoningId());
        } else {
            before=reads.execute(status->readContext(scope,actor,agent,graphId,request,null,null));
            var options=request.reasoning();
            var input=new vip.mate.semantic.reasoning.SemanticReasoningDtos.Request(
                options.scope()==null?vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope.ACCEPTED_FACTS:options.scope(),options.task(),options.individualIri(),options.axiomFunctionalSyntax(),before.context().snapshot().asOf());
            var pinned=reasoning.reasonPinned(scope,actor,agent,graphId,input);
            run=new ReasoningRun(UUID.randomUUID().toString(),before.context().snapshot().capturedAt().plusSeconds(600),pinned,conclusions(pinned));
            if(estimate(run)>87381)throw new SemanticApiException(422,"CONTEXT_REASONING_LIMIT","Reasoning continuation exceeds 256 KiB; narrow the task");
        }
        Read checkpoint=before;
        var result=reads.execute(status->readContext(scope,actor,agent,graphId,request,checkpoint,run));
        // Full reasoning inputs can exceed the question's retrieved slice; validate all of them.
        reasoning.revalidate(scope,actor,agent,graphId,run.pinned());
        if(before!=null&&result.context().coverage().nextCursor()!=null)rememberRun(run);
        return result.context();
    }

    private synchronized ReasoningRun cachedRun(String id) {
        reasoningRuns.values().removeIf(run->!run.expiresAt().isAfter(Instant.now()));
        var run=reasoningRuns.get(id);
        if(run==null)throw stale();
        return run;
    }
    private synchronized void rememberRun(ReasoningRun run) {
        reasoningRuns.values().removeIf(value->!value.expiresAt().isAfter(Instant.now()));
        while(reasoningRuns.size()>=64)reasoningRuns.remove(reasoningRuns.keySet().iterator().next());
        reasoningRuns.put(run.id(),run);
    }

    private Read readContext(String scope,String actor,Long agent,String graphId,Request request,Read checkpoint,ReasoningRun run) {
        if(request==null||request.question()==null||request.question().length()>2000)
            throw bad("Question required, maximum 2000 characters");
        int budget=request.budget()==null?6000:request.budget();
        if(budget<512||budget>12000)throw bad("Budget must be 512..12000 estimated tokens");
        Set<String> requested=request.entityIris()==null?Set.of():Set.copyOf(request.entityIris());
        if(requested.size()>100)throw bad("Maximum 100 entity IRIs");
        try { for(String iri:requested)if(!java.net.URI.create(iri).isAbsolute())throw bad("Absolute entity IRI required"); }
        catch(IllegalArgumentException exception){throw bad("Invalid entity IRI");}
        var graph=queries.requireAgentGraph(scope,actor,agent,graphId);
        var ontology=domain.ontology(graph);var parsed=ontology.document();
        String principal=scope+":"+actor+":"+agent;
        String queryHash=OntologyDocument.sha256(request.question()+"\n"+new TreeSet<>(requested)+"\n"+request.asOf()+"\n"+request.reasoning());
        Instant captured=Instant.now(), asOf=request.asOf()==null?captured:request.asOf(),expires=captured.plusSeconds(600);
        int offset=0;String expectedSnapshot=null;
        if(checkpoint!=null) {
            var pinned=checkpoint.context();
            if(graph.getMutationVersion()!=pinned.graphMutationVersion()||!graph.getOntologyRevisionId().equals(pinned.ontology().revisionId()))throw stale();
            captured=pinned.snapshot().capturedAt();asOf=pinned.snapshot().asOf();expires=captured.plusSeconds(600);
            expectedSnapshot=checkpoint.snapshotDigest();
        }
        if(run!=null) {
            var pinned=run.pinned().result();
            if(!graphId.equals(pinned.graphId())||graph.getMutationVersion()!=pinned.graphMutationVersion()
                ||!ontology.revisionId().value().equals(pinned.ontologyRevisionId())
                ||!parsed.document().documentDigest().equals(pinned.ontologyDocumentDigest())
                ||!parsed.document().importLockDigest().equals(pinned.importLockDigest()))throw stale();
        }
        if(request.cursor()!=null) {
            var cursor=decodeCursor(request.cursor());
            if(!Objects.equals(cursor.reasoningId(),run==null?null:run.id()))throw stale();
            if(!cursor.principal().equals(principal)||!cursor.graph().equals(graphId)||!cursor.query().equals(queryHash)||cursor.budget()!=budget)
                throw bad("Cursor does not belong to this request");
            if(cursor.expiresAt().isBefore(captured)||cursor.graphVersion()!=graph.getMutationVersion()
                    ||!cursor.revision().equals(graph.getOntologyRevisionId()))throw stale();
            expectedSnapshot=cursor.snapshotDigest();
            offset=cursor.offset();captured=cursor.capturedAt();asOf=cursor.asOf();expires=cursor.expiresAt();
        }
        if(run!=null&&!asOf.equals(run.pinned().request().asOf()))throw stale();
        Map<String,List<String>> labels=documents.termLabels(parsed),kinds=documents.termKinds(parsed);
        List<Axiom> allAxioms=new ArrayList<>();
        List<OntologyAnnotation> allAnnotations=new ArrayList<>();
        parsed.ontologyAnnotations().forEach(a->allAnnotations.add(new OntologyAnnotation(ontology.revisionId().value(),null,a)));
        addAxioms(allAxioms,parsed,ontology.revisionId().value(),null);
        // Each artifact retains its own identity and does not become an accepted business fact.
        Set<String> imported=new HashSet<>();
        for(var artifact:parsed.lockedImports())if(imported.add(artifact.contentDigest())) {
            var importedDocument=documents.parse(ontology.ontologyId().value(),
                ontology.revisionId().value()+":"+artifact.artifactId(),artifact.documentText(),artifact.syntax(),parsed.lockedImports());
            addAxioms(allAxioms,importedDocument,ontology.revisionId().value(),artifact.artifactId());
            importedDocument.ontologyAnnotations().forEach(a->allAnnotations.add(new OntologyAnnotation(ontology.revisionId().value(),artifact.artifactId(),a)));
        }
        allAxioms=allAxioms.stream().map(a -> {
            var bindings=a.artifactId()==null?sources.visibleBindings(scope,actor,agent,ontology.ontologyId().value(),a.originRevisionId(),a.id()):List.<vip.mate.semantic.ontology.source.OntologySourceDtos.Binding>of();
            return new Axiom(a.id(),a.originRevisionId(),a.artifactId(),a.kind(),a.functionalSyntax(),a.signature(),a.origin(),
                bindings.stream().map(v->v.id()).toList(),bindings.stream().map(v->new SourceState(v.id(),v.sourceSnapshotId(),v.sourceDigest(),v.currentSourceState(),v.reviewState())).toList());
        }).toList();
        String question=request.question().toLowerCase(Locale.ROOT);
        Set<String> seeds=new HashSet<>(requested);
        kinds.keySet().stream().filter(iri->question.contains(iri.toLowerCase(Locale.ROOT))).forEach(seeds::add);
        labels.forEach((iri,names)-> { if(names.stream().anyMatch(name->!name.isBlank()&&question.contains(name.toLowerCase(Locale.ROOT))))seeds.add(iri); });
        var entities=domain.entities(graph);
        entities.values().stream().filter(e->requested.contains(e.iri())||(!e.displayName().isBlank()&&question.contains(e.displayName().toLowerCase(Locale.ROOT))))
            .forEach(e->{seeds.add(e.iri());seeds.addAll(e.assertedTypes());});
        Set<String> selected=new HashSet<>(seeds); boolean fallback=selected.isEmpty();
        if(fallback)selected.addAll(kinds.keySet());
        // Exclude ubiquitous builtins from expansion, otherwise rdfs:label connects every term.
        boolean changed;
        do {
            changed=false;
            for(var axiom:allAxioms)if(intersects(axiom.signature(),selected))
                for(String iri:axiom.signature())if(!builtin(iri)&&selected.add(iri))changed=true;
        } while(changed);
        List<Unit> units=new ArrayList<>();
        allAnnotations.forEach(a->units.add(new Unit(null,null,null,a)));
        allAxioms.stream().filter(a->intersects(a.signature(),selected)).sorted(Comparator.comparing(Axiom::id))
            .forEach(a->units.add(new Unit(a,null,null)));
        Instant effectiveAsOf=asOf;
        var accepted=statements.trustedAsActor(scope,actor,graphId);
        var facts=accepted.stream().filter(f->validAt(f,effectiveAsOf))
            .filter(f->fallback||intersects(f.assertion().signatureIris(),selected))
            .sorted(Comparator.comparing(StatementView::id)).toList();
        facts.forEach(f->units.add(new Unit(null,f,null)));
        var factReviews=factSourceReviews(graphId);
        String snapshotDigest;
        try { snapshotDigest=OntologyDocument.sha256(json.writeValueAsString(List.of(units,factReviews))); }
        catch(Exception exception){throw new IllegalStateException("Cannot fingerprint context",exception);}
        if(expectedSnapshot!=null&&!expectedSnapshot.equals(snapshotDigest))throw stale();
        if(run!=null)run.conclusions().forEach(c->units.add(new Unit(null,null,c)));
        if(offset<0||offset>units.size())throw stale();
        List<Conclusion> pageConclusions=new ArrayList<>();
        List<OntologyAnnotation> pageAnnotations=new ArrayList<>();
        List<Axiom> pageAxioms=new ArrayList<>();List<Fact> pageFacts=new ArrayList<>();
        Map<String,Evidence> evidence=new LinkedHashMap<>();List<Term> terms=new ArrayList<>();
        List<String> warnings=new ArrayList<>(List.of("Runtime context is not permanent model knowledge. Definitions and relations alone do not prove physical causes.",
            "Ontology assertions, accepted evidence-backed facts and inferred conclusions are different origins.",
            "Unknown-time facts are excluded from this business-time snapshot. Entity registration types are retrieval hints, not reviewed type assertions."));
        if(!factReviews.isEmpty())warnings.add("Some accepted facts have changed sources. Inspect sourceReviews and their decisions; recording a review does not rewrite historical facts or establish current evidence.");
        if(fallback)warnings.add("No exact term match; returning a bounded ontology overview.");
        warnings.add("When truncated, dependency closure is incomplete; follow the version-bound cursor.");
        var ontologyInfo=new Ontology(ontology.ontologyId().value(),ontology.revisionId().value(),ontology.version(),parsed.document().documentDigest(),parsed.document().importLockDigest(),ontology.policy().version(),parsed.parsedOntologyIri(),parsed.parsedVersionIri().orElse(null));
        var snapshotInfo=new Snapshot(asOf,"HALF_OPEN_AT_TIME_EXCLUDE_UNKNOWN",captured);
        var reasoningInfo=reasoningInfo(run,List.of());
        String maximumCursor=encodeCursor(new Cursor(principal,graphId,queryHash,graph.getMutationVersion(),graph.getOntologyRevisionId(),units.size(),budget,asOf,captured,expires,snapshotDigest,run==null?null:run.id()));
        var envelope=new Context("semantic-context-v1",UUID.randomUUID().toString(),graphId,graph.getMutationVersion(),ontologyInfo,snapshotInfo,
            List.of(),List.of(),List.of(),List.of(),reasoningInfo,
            new Coverage(true,units.size(),units.size(),"TOKEN_BUDGET",12000,true,false,maximumCursor),List.copyOf(warnings));
        Set<String> pageTerms=new HashSet<>();int used=estimate(envelope),end=offset;
        for(;end<units.size();end++) {
            var unit=units.get(end);
            if(unit.annotation()!=null) {
                int cost=estimate(unit.annotation())+1;
                if(used+cost>budget)break;
                used+=cost;pageAnnotations.add(unit.annotation());continue;
            }
            if(unit.conclusion()!=null) {
                int cost=estimate(unit.conclusion())+1;
                if(used+cost>budget)break;
                used+=cost;pageConclusions.add(unit.conclusion());continue;
            }
            List<Evidence> attached=new ArrayList<>();
            Fact fact=null; Set<String> signature;
            if(unit.fact()!=null) {
                var source=unit.fact();signature=source.assertion().signatureIris();
                List<String> activeEvidenceIds=new ArrayList<>();
                for(String id:source.evidenceIds()) {
                    if(evidence.containsKey(id)){activeEvidenceIds.add(id);continue;}
                    var available=queries.availableEvidenceAsAgent(scope,actor,agent,graphId,id);
                    if(available.isPresent()) {
                        var quote=available.get();
                        attached.add(new Evidence(id,quote.snapshotId(),quote.textDigest(),quote.exactQuote(),quote.startCodePoint(),quote.endCodePoint()));
                        activeEvidenceIds.add(id);
                    }
                }
                if(activeEvidenceIds.isEmpty())throw stale();
                fact=new Fact(source.id(),source.revision(),source.assertion().functionalSyntax(),"ACCEPTED",List.copyOf(activeEvidenceIds),
                    new Validity(source.validityKind(),source.validFrom(),source.validTo()),
                    factReviews.getOrDefault(source.id()+":"+source.revision(),List.of()));
            } else signature=unit.axiom().signature();
            List<Term> extra=new ArrayList<>();
            for(String iri:new TreeSet<>(signature))if(!pageTerms.contains(iri)&&!builtin(iri)) {
                var refs=unit.axiom()==null?List.<String>of():List.of(unit.axiom().id());
                extra.add(new Term(iri,kinds.getOrDefault(iri,List.of()),labels.getOrDefault(iri,List.of()),null,refs));
            }
            int cost=estimate(unit.axiom()==null?fact:unit.axiom())+estimate(attached)+estimate(extra);
            if(used+cost>budget)break;
            used+=cost;terms.addAll(extra);extra.forEach(t->pageTerms.add(t.iri()));
            if(fact!=null)pageFacts.add(fact);else pageAxioms.add(unit.axiom());
            attached.forEach(e->evidence.put(e.id(),e));
        }
        if(end==offset&&end<units.size())throw new SemanticApiException(422,"CONTEXT_ITEM_EXCEEDS_BUDGET","Raise the context budget or retrieve the full ontology document; one item and its evidence cannot fit");
        session.clearCache();
        var current=queries.requireAgentGraph(scope,actor,agent,graphId);
        if(!Objects.equals(current.getMutationVersion(),graph.getMutationVersion())||!Objects.equals(current.getOntologyRevisionId(),graph.getOntologyRevisionId()))throw stale();
        for(var proof:evidence.values()) {
            var currentProof=queries.evidenceAsAgent(scope,actor,agent,graphId,proof.id());
            if(!proof.digest().equals(currentProof.textDigest())||!proof.exactQuote().equals(currentProof.exactQuote()))throw stale();
        }
        if(!factReviews.equals(factSourceReviews(graphId)))throw stale();
        boolean truncated=end<units.size();
        int omittedAxioms=(int)units.subList(end,units.size()).stream().filter(u->u.axiom()!=null).count();
        int omittedFacts=(int)units.subList(end,units.size()).stream().filter(u->u.fact()!=null).count();
        int omittedConclusions=(int)units.subList(end,units.size()).stream().filter(u->u.conclusion()!=null).count();
        int omittedAnnotations=(int)units.subList(end,units.size()).stream().filter(u->u.annotation()!=null).count();
        String next=truncated?encodeCursor(new Cursor(principal,graphId,queryHash,graph.getMutationVersion(),graph.getOntologyRevisionId(),end,budget,asOf,captured,expires,snapshotDigest,run==null?null:run.id())):null;
        var result=new Context("semantic-context-v1",UUID.randomUUID().toString(),graphId,graph.getMutationVersion(),
            ontologyInfo,snapshotInfo,List.copyOf(terms),List.copyOf(pageAxioms),List.copyOf(pageFacts),List.copyOf(evidence.values()),
            reasoningInfo(run,List.copyOf(pageConclusions)),
            new Coverage(truncated,omittedAxioms,omittedFacts,truncated?"TOKEN_BUDGET":null,used,true,!truncated&&offset==0,next,omittedConclusions,omittedAnnotations),List.copyOf(warnings),List.copyOf(pageAnnotations));
        int actual=estimate(result);
        if(actual>budget)throw new SemanticApiException(422,"CONTEXT_ITEM_EXCEEDS_BUDGET","Context envelope exceeds requested budget");
        return new Read(new Context(result.schema(),result.traceId(),result.graphId(),result.graphMutationVersion(),result.ontology(),result.snapshot(),
            result.terms(),result.axioms(),result.facts(),result.evidence(),result.reasoning(),
            new Coverage(truncated,omittedAxioms,omittedFacts,truncated?"TOKEN_BUDGET":null,actual,true,!truncated&&offset==0,next,omittedConclusions,omittedAnnotations),result.warnings(),result.ontologyAnnotations()),snapshotDigest);
    }
    private static List<Conclusion> conclusions(vip.mate.semantic.reasoning.SemanticReasoningService.PinnedResult pinned) {
        var result=pinned.result();
        Set<String> assertions=new TreeSet<>();
        if(result.status()==vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus.CONSISTENT) {
            result.classRelations().forEach(relation->relation.superClassIris().forEach(parent->
                assertions.add("SubClassOf(<"+relation.classIri()+"> <"+parent+">)")));
            result.individualTypes().forEach(individual->individual.classIris().forEach(type->
                assertions.add("ClassAssertion(<"+type+"> <"+individual.individualIri()+">)")));
        } else if(result.status()==vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus.ENTAILED
                &&pinned.request().axiomFunctionalSyntax()!=null)assertions.add(pinned.request().axiomFunctionalSyntax());
        return assertions.stream().map(a->new Conclusion(a,"INFERRED",List.of(),List.of(),"UNAVAILABLE")).toList();
    }
    private static Reasoning reasoningInfo(ReasoningRun run,List<Conclusion> conclusions) {
        if(run==null)return new Reasoning("BUSINESS_PROJECTION",null,null,"NOT_RUN","NONE",List.of(),List.of());
        var result=run.pinned().result();
        String status=switch(result.status()) {
            case CONSISTENT,ENTAILED,NOT_ENTAILED,UNSATISFIABLE -> "COMPLETE_FOR_REQUEST";
            case RESOURCE_EXHAUSTED -> "RESOURCE_LIMIT";
            default -> result.status().name();
        };
        return new Reasoning(result.scope().name(),result.engineName(),result.engineVersion(),status,result.task().name(),
            List.of(),conclusions,result.status().name(),result.inputDigest(),"UNAVAILABLE");
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public vip.mate.semantic.ontology.source.OntologySourceDtos.Snapshot source(String scope,String actor,Long agent,String graphId,String bindingId) {
        var graph=queries.requireAgentGraph(scope,actor,agent,graphId);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_axiom_source WHERE id=? AND revision_id=?",Integer.class,bindingId,graph.getOntologyRevisionId());
        if(count!=1)throw new SemanticApiException(404,"SOURCE_UNAVAILABLE","Source binding is not in the pinned revision");
        var revision=domain.ontology(graph);
        var result=sources.snapshotAsAgent(scope,actor,agent,revision.ontologyId().value(),bindingId);
        session.clearCache();var current=queries.requireAgentGraph(scope,actor,agent,graphId);
        if(!Objects.equals(current.getOntologyRevisionId(),graph.getOntologyRevisionId()))throw stale();
        return result;
    }
    private void addAxioms(List<Axiom> out,ParsedOntologyDocument document,String revision,String artifact) {
        for(var a:document.axioms())out.add(new Axiom(a.axiomId(),revision,artifact,a.axiomType(),a.rendering(),a.signatureIris(),"ONTOLOGY_ASSERTION",List.of(),List.of()));
    }
    private static boolean intersects(Set<String> left,Set<String> right){return left.stream().anyMatch(right::contains);}
    private static boolean builtin(String iri){return iri.startsWith("http://www.w3.org/");}
    private static boolean validAt(StatementView f,Instant time){return !"UNKNOWN".equals(f.validityKind())&&(f.validFrom()==null||!time.isBefore(f.validFrom()))&&(f.validTo()==null||time.isBefore(f.validTo()));}
    private int estimate(Object value){try{return (json.writeValueAsBytes(value).length+2)/3;}catch(Exception e){throw new IllegalStateException(e);}}
    private String encodeCursor(Cursor value){try{String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(value));return payload+"."+signature(payload);}catch(Exception e){throw new IllegalStateException(e);}}
    private Cursor decodeCursor(String token){try{if(token.length()>4096)throw bad("Invalid cursor");var parts=token.split("\\.",-1);if(parts.length!=2||!MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII)))throw bad("Invalid cursor");return json.readValue(Base64.getUrlDecoder().decode(parts[0]),Cursor.class);}catch(SemanticApiException e){throw e;}catch(Exception e){throw bad("Invalid cursor");}}
    private String signature(String payload){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    private static SemanticApiException bad(String message){return new SemanticApiException(400,"INVALID_CONTEXT_REQUEST",message);}
    private static SemanticApiException stale(){return new SemanticApiException(409,"CONTEXT_STALE","Graph, ontology or cursor changed; restart context retrieval");}
}
