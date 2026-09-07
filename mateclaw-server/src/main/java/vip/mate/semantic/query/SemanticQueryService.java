package vip.mate.semantic.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.graph.*;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.*;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.OntologyDtos.Definition;
import vip.mate.semantic.web.StatementDtos.*;
import vip.mate.semantic.query.SemanticQueryDtos.*;

import java.sql.ResultSet;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true,timeout=5)
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class SemanticQueryService {
    private final JdbcTemplate jdbc;private final GraphApplicationService graphs;private final SemanticAccessService access;private final StatementApplicationService statements;private final SupportEvaluator support;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private final vip.mate.agent.repository.AgentMapper agents;
    private final vip.mate.wiki.service.WikiKnowledgeBaseService knowledgeBases;
    public SemanticQueryService(JdbcTemplate jdbc,GraphApplicationService graphs,SemanticAccessService access,StatementApplicationService statements,SupportEvaluator support,
            vip.mate.agent.repository.AgentMapper agents, vip.mate.wiki.service.WikiKnowledgeBaseService knowledgeBases){this.jdbc=jdbc;this.graphs=graphs;this.access=access;this.statements=statements;this.support=support;this.agents=agents;this.knowledgeBases=knowledgeBases;}

    public SearchResult search(String scope,String graphId,SearchRequest request){
        validateSearch(request);
        return searchFacts(graphId, request, statements.trusted(scope, graphId));
    }

    public SearchResult searchAsAgent(String scope,String actorId,Long agentId,String graphId,SearchRequest request){
        access.requireActor(scope, actorId, "viewer");
        if (agentId == null || agentId <= 0) throw new SemanticApiException(401, "UNAUTHENTICATED", "Authenticated agent origin required");
        var agent = agents.selectById(agentId);
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled()) || !Objects.equals(agent.getWorkspaceId(), Long.valueOf(scope)))
            throw new SemanticApiException(403, "FORBIDDEN", "Agent is not active in this workspace");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        if (knowledgeBases.findVisibleById(agentId, graph.getKbId()) == null)
            throw new SemanticApiException(404, "NOT_FOUND", "Knowledge base is not visible to this agent");
        validateSearch(request);
        return searchFacts(graphId, request, statements.trustedAsActor(scope, actorId, graphId));
    }

    private SearchResult searchFacts(String graphId,SearchRequest request,List<StatementView> facts){
        int limit=request.limit()==null?20:request.limit();
        String needle=request.query().toLowerCase(Locale.ROOT);List<StatementView> matches=new ArrayList<>();
        Map<String,String> labels = new HashMap<>();
        jdbc.query("SELECT id,display_name FROM mate_semantic_entity WHERE graph_id=?", rs -> {
            labels.put(rs.getString("id"), rs.getString("display_name"));
        }, graphId);
        Map<String,String> predicates = predicateLabels(graphId);
        for(StatementView fact:facts){
            if(request.atTime()!=null&&("UNKNOWN".equals(fact.validityKind())||(fact.validFrom()!=null&&request.atTime().isBefore(fact.validFrom()))))continue;
            if(request.atTime()!=null&&fact.validTo()!=null&&!request.atTime().isBefore(fact.validTo()))continue;
            String text = String.join(" ", labels.getOrDefault(fact.subjectId(), ""), fact.predicateKey(),
                    predicates.getOrDefault(fact.predicateKind()+":"+fact.predicateKey(), ""),
                    labels.getOrDefault(fact.targetEntityId(), ""), Objects.toString(fact.value(), ""));
            if(text.toLowerCase(Locale.ROOT).contains(needle))matches.add(fact);
        }
        boolean truncated=matches.size()>limit;
        List<StatementView> selected=List.copyOf(matches.subList(0,Math.min(limit,matches.size())));
        Map<String,String> selectedEntities=new LinkedHashMap<>(), selectedPredicates=new LinkedHashMap<>();
        for(StatementView fact:selected){
            if(labels.containsKey(fact.subjectId())) selectedEntities.put(fact.subjectId(),labels.get(fact.subjectId()));
            if(labels.containsKey(fact.targetEntityId())) selectedEntities.put(fact.targetEntityId(),labels.get(fact.targetEntityId()));
            String key=fact.predicateKind()+":"+fact.predicateKey();
            if(predicates.containsKey(key)) selectedPredicates.put(key,predicates.get(key));
        }
        return new SearchResult(selected,UUID.randomUUID().toString(),truncated,Map.copyOf(selectedEntities),Map.copyOf(selectedPredicates));
    }

    public GraphResult neighbors(String scope,String graphId,String entityId,int depth,int nodeLimit,int edgeLimit){
        access.require(scope,"viewer");graphs.requireGraph(scope,graphId,false);
        if(depth<1||depth>2)throw bad("Depth must be 1 or 2");if(nodeLimit<1||nodeLimit>100||edgeLimit<1||edgeLimit>200)throw bad("Graph limits exceed 100 nodes or 200 edges");
        Integer seed=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_entity WHERE id=? AND graph_id=?",Integer.class,entityId,graphId);if(seed==null||seed==0)throw new SemanticApiException(404,"NOT_FOUND","Entity not found in graph");
        List<StatementView> trusted=statements.trusted(scope,graphId);Set<String> frontier=new LinkedHashSet<>(List.of(entityId));Set<String> ids=new LinkedHashSet<>(frontier);List<Edge> edges=new ArrayList<>();Set<String> edgeIds=new HashSet<>();boolean truncated=false;
        boolean visible=trusted.stream().anyMatch(f->entityId.equals(f.subjectId())||entityId.equals(f.targetEntityId()));
        if(!visible)return new GraphResult(List.of(),List.of(),UUID.randomUUID().toString(),false);
        for(int hop=0;hop<depth;hop++){
            Set<String> next=new LinkedHashSet<>();for(StatementView fact:trusted){if(!"RELATION".equals(fact.predicateKind())||fact.targetEntityId()==null)continue;if(frontier.contains(fact.subjectId())||frontier.contains(fact.targetEntityId())){
                if(edgeIds.contains(fact.id()))continue;
                if(edges.size()>=edgeLimit){truncated=true;break;}
                int added=(ids.contains(fact.subjectId())?0:1)+(ids.contains(fact.targetEntityId())||fact.subjectId().equals(fact.targetEntityId())?0:1);
                if(ids.size()+added>nodeLimit){truncated=true;continue;}
                edgeIds.add(fact.id());edges.add(new Edge(fact.id(),fact.subjectId(),fact.targetEntityId(),fact.predicateKey(),fact.revision()));
                if(ids.add(fact.subjectId()))next.add(fact.subjectId());
                if(ids.add(fact.targetEntityId()))next.add(fact.targetEntityId());
            }}
            for(String id:next){if(ids.size()>=nodeLimit&&!ids.contains(id)){truncated=true;continue;}ids.add(id);}frontier=next;if(truncated&&edges.size()>=edgeLimit)break;
        }
        List<Node> nodes=new ArrayList<>();for(String id:ids){List<Map<String,Object>> rows=jdbc.queryForList("SELECT type_key,display_name FROM mate_semantic_entity WHERE id=? AND graph_id=?",id,graphId);if(rows.isEmpty())continue;Map<String,Object> row=rows.getFirst();List<StatementView> properties=trusted.stream().filter(f->id.equals(f.subjectId())&&"PROPERTY".equals(f.predicateKind())).toList();nodes.add(new Node(id,(String)row.get("type_key"),(String)row.get("display_name"),properties));}
        return new GraphResult(List.copyOf(nodes),List.copyOf(edges),UUID.randomUUID().toString(),truncated);
    }

    public EvidenceResult evidence(String scope,String graphId,String evidenceId){
        access.require(scope,"viewer");GraphRow graph=graphs.requireGraph(scope,graphId,false);
        List<EvidenceRow> rows=jdbc.query("SELECT e.id,e.snapshot_id,e.start_codepoint,e.end_codepoint,e.exact_quote,s.source_kind,s.source_id,s.source_title,s.text_digest FROM mate_semantic_evidence e JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE e.id=? AND e.graph_id=? AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=e.graph_id AND x.snapshot_id=s.id) AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance g WHERE g.graph_id=e.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id AND g.state='WITHDRAWN')",(rs,n)->evidenceRow(rs),evidenceId,graphId);
        if(rows.isEmpty())throw new SemanticApiException(404,"NOT_FOUND","Evidence is unavailable");EvidenceRow row=rows.getFirst();
        if(!sourceExists(graph,row.sourceKind(),row.sourceRef()))throw new SemanticApiException(404,"NOT_FOUND","Evidence source is unavailable");
        return new EvidenceResult(row.id(),row.snapshotId(),row.sourceKind(),row.sourceRef(),row.title(),row.quote(),row.start(),row.end(),row.digest());
    }

    public HistoryResult history(String scope,String graphId,String statementId){
        access.require(scope,"admin");GraphRow graph=graphs.requireGraph(scope,graphId,false);
        List<RawRevision> rows=jdbc.query("SELECT * FROM mate_semantic_statement_revision WHERE graph_id=? AND statement_id=? ORDER BY revision",(rs,n)->new RawRevision(rs.getString("statement_id"),rs.getInt("revision"),rs.getString("ontology_revision_id"),rs.getString("review_status"),rs.getString("content_json"),rs.getString("actor_id"),rs.getTimestamp("created_at").toLocalDateTime()),graphId,statementId);
        if(rows.isEmpty())throw new SemanticApiException(404,"NOT_FOUND","Statement not found in graph");List<StatementView> history=new ArrayList<>();for(RawRevision row:rows){ProposeRequest r=decode(row.content());List<String> ev=jdbc.query("SELECT evidence_id FROM mate_semantic_revision_evidence WHERE statement_id=? AND revision=? ORDER BY evidence_id",(rs,n)->rs.getString(1),row.id(),row.revision());history.add(new StatementView(row.id(),graphId,row.revision(),row.ontology(),r.subjectId(),r.predicateKind(),r.predicateKey(),r.valueType(),r.value(),r.unit(),r.targetEntityId(),r.validityKind(),r.validFrom(),r.validTo(),row.status(),"ACCEPTED".equals(row.status())?(support.supported(graph,row.id(),row.revision())?"SUPPORTED":"SUPPORT_LOST"):"UNREVIEWED",ev,row.actor(),row.created().toInstant(ZoneOffset.UTC)));}return new HistoryResult(statementId,List.copyOf(history));
    }

    private Map<String,String> predicateLabels(String graphId) {
        List<String> definitions = jdbc.query("SELECT r.definition_json FROM mate_semantic_graph g JOIN mate_semantic_ontology_revision r ON r.id=g.ontology_revision_id WHERE g.id=?", (rs,n)->rs.getString(1), graphId);
        Map<String,String> labels = new HashMap<>();
        if (definitions.isEmpty()) return labels;
        try {
            Definition definition = json.readValue(definitions.getFirst(), Definition.class);
            definition.properties().forEach(p -> labels.put("PROPERTY:"+p.key(), p.label()));
            definition.relations().forEach(r -> labels.put("RELATION:"+r.key(), r.label()));
            return labels;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot read pinned ontology definition", e);
        }
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
