package vip.mate.semantic.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.graph.*;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.*;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.core.ontology.OntologyDocumentPort;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.web.StatementDtos.*;
import vip.mate.semantic.query.SemanticQueryDtos.*;

import java.sql.ResultSet;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true,timeout=5)
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class SemanticQueryService {
    private final SemanticDomainMapper domain; private final OntologyDocumentPort documents;
    private final JdbcTemplate jdbc;private final GraphApplicationService graphs;private final SemanticAccessService access;private final StatementApplicationService statements;private final SupportEvaluator support;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private final vip.mate.agent.repository.AgentMapper agents;
    private final vip.mate.wiki.service.WikiKnowledgeBaseService knowledgeBases;
    public SemanticQueryService(JdbcTemplate jdbc,GraphApplicationService graphs,SemanticAccessService access,StatementApplicationService statements,SupportEvaluator support,
            vip.mate.agent.repository.AgentMapper agents, vip.mate.wiki.service.WikiKnowledgeBaseService knowledgeBases, SemanticDomainMapper domain, OntologyDocumentPort documents){this.domain=domain;this.documents=documents;this.jdbc=jdbc;this.graphs=graphs;this.access=access;this.statements=statements;this.support=support;this.agents=agents;this.knowledgeBases=knowledgeBases;}

    public SearchResult search(String scope,String graphId,SearchRequest request){
        validateSearch(request);
        return searchFacts(scope, graphId, request, statements.trusted(scope, graphId));
    }

    public SearchResult searchAsAgent(String scope,String actorId,Long agentId,String graphId,SearchRequest request){
        requireAgentGraph(scope,actorId,agentId,graphId);
        validateSearch(request);
        return searchFacts(scope, graphId, request, statements.trustedAsActor(scope, actorId, graphId));
    }

    public GraphRow requireAgentGraph(String scope,String actorId,Long agentId,String graphId) {
        access.requireActor(scope, actorId, "viewer");
        if (agentId == null || agentId <= 0) throw new SemanticApiException(401, "UNAUTHENTICATED", "Authenticated agent origin required");
        var agent = agents.selectById(agentId);
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled()) || !Objects.equals(agent.getWorkspaceId(), Long.valueOf(scope)))
            throw new SemanticApiException(403, "FORBIDDEN", "Agent is not active in this workspace");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        if (knowledgeBases.findVisibleById(agentId, graph.getKbId()) == null)
            throw new SemanticApiException(404, "NOT_FOUND", "Knowledge base is not visible to this agent");
        if(!Boolean.TRUE.equals(graph.getEnabled())) throw new SemanticApiException(409,"GRAPH_DISABLED","Graph is disabled");
        return graph;
    }
    public EvidenceResult evidenceAsAgent(String scope,String actorId,Long agentId,String graphId,String evidenceId) {
        return evidenceInGraph(requireAgentGraph(scope,actorId,agentId,graphId),evidenceId);
    }

    /** Missing/withdrawn proof is an ordinary read result; graph authorization still fails closed. */
    public Optional<EvidenceResult> availableEvidenceAsAgent(String scope,String actorId,Long agentId,String graphId,String evidenceId) {
        var graph=requireAgentGraph(scope,actorId,agentId,graphId);
        try{return Optional.of(evidenceInGraph(graph,evidenceId));}
        catch(SemanticApiException exception){if(exception.status()==404)return Optional.empty();throw exception;}
    }

    private record EntityIndex(Map<String,String> labels, Map<String,String> idsByIri,
                               Map<String,Set<String>> types, Map<String,String> iris) {}
    private EntityIndex entities(String graphId) {
        Map<String,String> labels=new LinkedHashMap<>(), ids=new HashMap<>(), iris=new HashMap<>();
        Map<String,Set<String>> types=new HashMap<>();
        jdbc.query("SELECT id,iri,display_name,asserted_types_json FROM mate_semantic_entity WHERE graph_id=?", rs -> {
            String id=rs.getString("id"), iri=rs.getString("iri");
            labels.put(id, rs.getString("display_name")); ids.put(iri,id); iris.put(id,iri);
            try { types.put(id, Set.copyOf(Arrays.asList(json.readValue(rs.getString("asserted_types_json"),String[].class)))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid entity type index",e); }
        },graphId);
        return new EntityIndex(labels,ids,types,iris);
    }
    private static String target(StatementView fact, EntityIndex index) {
        return fact.assertion().objectIri().or(fact.assertion()::relatedIndividualIri)
                .map(index.idsByIri()::get).orElse(null);
    }
    private SearchResult searchFacts(String scope,String graphId,SearchRequest request,List<StatementView> facts){
        int limit=request.limit()==null?20:request.limit();
        String needle=request.query().toLowerCase(Locale.ROOT); List<StatementView> matches=new ArrayList<>();
        var index=entities(graphId);
        var terms=documents.termLabels(domain.ontology(graphs.requireGraph(scope,graphId,false)).document());
        for(var fact:facts){
            if(request.atTime()!=null && ("UNKNOWN".equals(fact.validityKind()) ||
                (fact.validFrom()!=null && request.atTime().isBefore(fact.validFrom())) ||
                (fact.validTo()!=null && !request.atTime().isBefore(fact.validTo())))) continue;
            String target=target(fact,index);
            StringBuilder text=new StringBuilder(fact.assertion().functionalSyntax());
            text.append(' ').append(index.labels().getOrDefault(fact.subjectId(),""));
            text.append(' ').append(index.labels().getOrDefault(target,""));
            Set<String> iris=new HashSet<>(fact.assertion().signatureIris());
            iris.addAll(index.types().getOrDefault(fact.subjectId(),Set.of()));
            iris.addAll(index.types().getOrDefault(target,Set.of()));
            iris.forEach(iri -> text.append(' ').append(String.join(" ",terms.getOrDefault(iri,List.of()))));
            if(text.toString().toLowerCase(Locale.ROOT).contains(needle)) matches.add(fact);
        }
        List<StatementView> selected=List.copyOf(matches.subList(0,Math.min(limit,matches.size())));
        Map<String,String> selectedEntities=new LinkedHashMap<>(), selectedPredicates=new LinkedHashMap<>();
        for(var fact:selected){
            for(String id:Arrays.asList(fact.subjectId(),target(fact,index)))
                if(index.labels().containsKey(id)) selectedEntities.put(id,index.labels().get(id));
            fact.assertion().predicateIri().ifPresent(iri -> selectedPredicates.put(iri,
                    terms.getOrDefault(iri,List.of(iri)).stream().findFirst().orElse(iri)));
        }
        return new SearchResult(selected,UUID.randomUUID().toString(),matches.size()>limit,
                Map.copyOf(selectedEntities),Map.copyOf(selectedPredicates));
    }

    public GraphResult neighbors(String scope,String graphId,String entityId,int depth,int nodeLimit,int edgeLimit){
        access.require(scope,"viewer");graphs.requireGraph(scope,graphId,false);
        if(depth<1||depth>2)throw bad("Depth must be 1 or 2");
        if(nodeLimit<1||nodeLimit>100||edgeLimit<1||edgeLimit>200)throw bad("Graph limits exceed 100 nodes or 200 edges");
        var index=entities(graphId);
        if(!index.labels().containsKey(entityId))throw new SemanticApiException(404,"NOT_FOUND","Entity not found in graph");
        var trusted=statements.trusted(scope,graphId);
        boolean visible=trusted.stream().anyMatch(f->entityId.equals(f.subjectId())||entityId.equals(target(f,index)));
        if(!visible)return new GraphResult(List.of(),List.of(),UUID.randomUUID().toString(),false);
        Set<String> frontier=new LinkedHashSet<>(List.of(entityId)), ids=new LinkedHashSet<>(frontier), edgeIds=new HashSet<>();
        List<Edge> edges=new ArrayList<>(); boolean truncated=false;
        for(int hop=0;hop<depth;hop++){
            Set<String> next=new LinkedHashSet<>();
            for(var fact:trusted){
                // Negative assertions and sameAs remain explicit facts; neither creates a positive relation edge.
                if(fact.assertion().kind()!=AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY)continue;
                String target=target(fact,index);
                if(target==null || !(frontier.contains(fact.subjectId())||frontier.contains(target)) || edgeIds.contains(fact.id()))continue;
                if(edges.size()>=edgeLimit){truncated=true;break;}
                int added=(ids.contains(fact.subjectId())?0:1)+(ids.contains(target)||fact.subjectId().equals(target)?0:1);
                if(ids.size()+added>nodeLimit){truncated=true;continue;}
                edgeIds.add(fact.id());edges.add(new Edge(fact.id(),fact.subjectId(),target,fact.assertion().predicateIri().orElseThrow(),fact.revision()));
                if(ids.add(fact.subjectId()))next.add(fact.subjectId()); if(ids.add(target))next.add(target);
            }
            frontier=next;if(frontier.isEmpty())break;
        }
        List<Node> nodes=ids.stream().map(id->new Node(id,index.iris().get(id),index.types().getOrDefault(id,Set.of()),
            index.labels().get(id),trusted.stream().filter(f->id.equals(f.subjectId()) && !f.assertion().objectAssertion()).toList())).toList();
        return new GraphResult(nodes,List.copyOf(edges),UUID.randomUUID().toString(),truncated);
    }

    public EvidenceResult evidence(String scope,String graphId,String evidenceId){
        access.require(scope,"viewer");GraphRow graph=graphs.requireGraph(scope,graphId,false);
        return evidenceInGraph(graph,evidenceId);
    }
    private EvidenceResult evidenceInGraph(GraphRow graph,String evidenceId) {
        String graphId=graph.getId();
        List<EvidenceRow> rows=jdbc.query("SELECT e.id,e.snapshot_id,e.start_codepoint,e.end_codepoint,e.exact_quote,s.source_kind,s.source_id,s.source_title,s.text_digest FROM mate_semantic_evidence e JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE e.id=? AND e.graph_id=? AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=e.graph_id AND x.snapshot_id=s.id) AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance g WHERE g.graph_id=e.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id AND g.state='WITHDRAWN')",(rs,n)->evidenceRow(rs),evidenceId,graphId);
        if(rows.isEmpty())throw new SemanticApiException(404,"NOT_FOUND","Evidence is unavailable");EvidenceRow row=rows.getFirst();
        if(!sourceExists(graph,row.sourceKind(),row.sourceRef()))throw new SemanticApiException(404,"NOT_FOUND","Evidence source is unavailable");
        return new EvidenceResult(row.id(),row.snapshotId(),row.sourceKind(),row.sourceRef(),row.title(),row.quote(),row.start(),row.end(),row.digest());
    }

    public HistoryResult history(String scope,String graphId,String statementId){
        access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,false);
        List<RawRevision> rows=jdbc.query("SELECT * FROM mate_semantic_statement_revision WHERE graph_id=? AND statement_id=? ORDER BY revision",(rs,n)->new RawRevision(rs.getString("statement_id"),rs.getInt("revision"),rs.getString("ontology_revision_id"),rs.getString("review_status"),rs.getString("content_json"),rs.getString("actor_id"),rs.getTimestamp("created_at").toLocalDateTime()),graphId,statementId);
        if(rows.isEmpty())throw new SemanticApiException(404,"NOT_FOUND","Statement not found in graph");List<StatementView> history=new ArrayList<>();for(RawRevision row:rows){ProposeRequest r=decode(row.content());List<String> ev=jdbc.query("SELECT evidence_id FROM mate_semantic_revision_evidence WHERE statement_id=? AND revision=? ORDER BY evidence_id",(rs,n)->rs.getString(1),row.id(),row.revision());history.add(new StatementView(row.id(),graphId,row.revision(),row.ontology(),r.subjectId(),domain.assertion(r.assertionText()),r.validityKind(),r.validFrom(),r.validTo(),row.status(),"ACCEPTED".equals(row.status())?(support.supported(graph,row.id(),row.revision())?"SUPPORTED":"SUPPORT_LOST"):"UNREVIEWED",ev,row.actor(),row.created().toInstant(ZoneOffset.UTC)));}return new HistoryResult(statementId,List.copyOf(history));
    }

    private ProposeRequest decode(String content){try{return json.readValue(content,ProposeRequest.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void validateSearch(SearchRequest request){
        if(request==null||request.query()==null||request.query().length()>256)throw bad("Query required and must not exceed 256 characters");
        if(request.limit()!=null&&(request.limit()<1||request.limit()>100))throw bad("Limit must be 1..100");
    }
    private boolean sourceExists(GraphRow graph,String kind,String source){if(!"WIKI_RAW".equals(kind))return false;try{Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",Integer.class,Long.valueOf(source),graph.getKbId());return count!=null&&count>0;}catch(NumberFormatException e){return false;}}
    private static EvidenceRow evidenceRow(ResultSet rs)throws java.sql.SQLException{return new EvidenceRow(rs.getString("id"),rs.getString("snapshot_id"),rs.getString("source_kind"),rs.getString("source_id"),rs.getString("source_title"),rs.getString("exact_quote"),rs.getInt("start_codepoint"),rs.getInt("end_codepoint"),rs.getString("text_digest"));}
    private static SemanticApiException bad(String message){return new SemanticApiException(400,"INVALID_REQUEST",message);}
    private record EvidenceRow(String id,String snapshotId,String sourceKind,String sourceRef,String title,String quote,int start,int end,String digest){}
    private record RawRevision(String id,int revision,String ontology,String status,String content,String actor,java.time.LocalDateTime created){}
}
