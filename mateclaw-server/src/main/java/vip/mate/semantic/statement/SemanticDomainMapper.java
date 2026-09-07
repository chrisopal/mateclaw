package vip.mate.semantic.statement;

import org.springframework.stereotype.Component;

import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.graph.*;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.web.OntologyDtos.Definition;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;

import java.util.*;

@Component
public class SemanticDomainMapper {
    private final GraphMapper graphs;
    private final OntologyWireMapper wire;
    public SemanticDomainMapper(GraphMapper graphs, OntologyWireMapper wire){this.graphs=graphs;this.wire=wire;}

    public GraphScope scope(GraphRow graph){return new GraphScope(new WorkspaceId(graph.getWorkspaceId().toString()),new KnowledgeBaseId(graph.getKbId().toString()),new GraphId(graph.getId()));}

    public OntologyRevision ontology(GraphRow graph){
        GraphOntologyRevisionRow row=graphs.revision(graph.getOntologyRevisionId());
        if(row==null) throw new SemanticApiException(409,"ONTOLOGY_REVISION_MISSING","Pinned ontology revision no longer exists");
        Definition d=wire.decode(row.getDefinitionJson(),Definition.class);
        OntologyDefinition definition=new OntologyDefinition(
                d.types().stream().map(t->new EntityTypeDefinition(t.key(),t.label(),t.description())).toList(),
                d.properties().stream().map(p->new PropertyDefinition(p.key(),p.label(),p.description(),p.ownerTypeKey(),p.valueType(),p.multiplicity(),Optional.ofNullable(p.fixedUnit()))).toList(),
                d.relations().stream().map(r->new RelationDefinition(r.key(),r.label(),r.description(),r.sourceTypeKey(),r.targetTypeKey(),r.multiplicity())).toList());
        return new OntologyRevision(new OntologyRevisionId(row.getId()),new OntologyId(row.getOntologyId()),row.getVersion(),definition);
    }

    public Map<EntityId,Entity> entities(GraphRow graph){
        Map<EntityId,Entity> result=new HashMap<>();
        for(EntityRow row:graphs.entities(graph.getId())){
            EntityId id=new EntityId(row.getId());
            result.put(id,new Entity(id,scope(graph),row.getTypeKey(),row.getDisplayName()));
        }
        return Map.copyOf(result);
    }

    public StatementContent content(GraphRow graph,ProposeRequest request){
        if(request==null) throw bad("Statement content required");
        try{
            if("ENTITY".equals(request.valueType())){
                if(request.value()!=null||request.unit()!=null)throw bad("ENTITY values use targetEntityId only");
            }else if(request.targetEntityId()!=null)throw bad("Scalar values cannot have targetEntityId");
            if(!"DECIMAL".equals(request.valueType())&&request.unit()!=null)throw bad("Only DECIMAL values can specify a unit");
            if(request.evidenceIds()!=null&&new HashSet<>(request.evidenceIds()).size()!=request.evidenceIds().size())throw bad("Evidence references must be unique");
            PredicateRef predicate=switch(require(request.predicateKind(),"predicateKind")){
                case "RELATION" -> PredicateRef.relation(request.predicateKey());
                case "PROPERTY" -> PredicateRef.property(request.predicateKey());
                default -> throw bad("Unsupported predicateKind");
            };
            StatementValue value=switch(require(request.valueType(),"valueType")){
                case "TEXT"->new StatementValue.TextValue(Objects.requireNonNull(request.value(),"value"));
                case "DECIMAL"->new StatementValue.DecimalValue(Objects.requireNonNull(request.value(),"value"),request.unit());
                case "BOOLEAN"->new StatementValue.BooleanValue(booleanValue(request.value()));
                case "DATE"->new StatementValue.DateValue(Objects.requireNonNull(request.value(),"value"));
                case "INSTANT"->new StatementValue.InstantValue(Objects.requireNonNull(request.value(),"value"));
                case "ENTITY"->new StatementValue.EntityValue(new EntityId(require(request.targetEntityId(),"targetEntityId")));
                default->throw bad("Unsupported valueType");
            };
            Validity validity=switch(require(request.validityKind(),"validityKind")){
                case "UNKNOWN" -> {
                    if(request.validFrom()!=null||request.validTo()!=null)throw bad("UNKNOWN validity cannot have interval bounds");
                    yield Validity.unknown();
                }
                case "INTERVAL" -> Validity.interval(request.validFrom(),request.validTo());
                default -> throw bad("Unsupported validityKind");
            };
            Set<EvidenceId> evidence=new LinkedHashSet<>();
            if(request.evidenceIds()!=null) request.evidenceIds().forEach(id->evidence.add(new EvidenceId(id)));
            return new StatementContent(scope(graph),new OntologyRevisionId(graph.getOntologyRevisionId()),new EntityId(require(request.subjectId(),"subjectId")),predicate,value,validity,evidence);
        }catch(SemanticApiException e){throw e;}catch(RuntimeException e){throw new SemanticApiException(422,"INVALID_STATEMENT","Statement value or validity is invalid");}
    }

    public void validate(GraphRow graph,StatementContent content){
        var report=new StatementValidator().validate(scope(graph),ontology(graph),content,entities(graph));
        if(!report.valid()) throw new SemanticApiException(422,report.violations().getFirst().code(),"Statement does not conform to pinned ontology");
    }
    private static String require(String value,String field){if(value==null||value.isBlank())throw bad(field+" required");return value;}
    private static boolean booleanValue(String value){
        if("true".equals(value))return true;
        if("false".equals(value))return false;
        throw bad("BOOLEAN value must be true or false");
    }
    private static SemanticApiException bad(String message){return new SemanticApiException(400,"INVALID_REQUEST",message);}
}
