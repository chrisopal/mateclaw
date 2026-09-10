package vip.mate.semantic.statement;

import java.util.*;
import org.springframework.stereotype.Component;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.graph.*;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.owl.OwlAssertionAdapter;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;

@Component
public class SemanticDomainMapper {
    private final GraphMapper graphs;
    private final OntologyWireMapper wire;
    private final OwlAssertionAdapter assertions;
    public SemanticDomainMapper(GraphMapper graphs, OntologyWireMapper wire, OwlAssertionAdapter assertions) {
        this.graphs=graphs; this.wire=wire; this.assertions=assertions;
    }
    public GraphScope scope(GraphRow graph) {
        return new GraphScope(new WorkspaceId(graph.getWorkspaceId().toString()),new KnowledgeBaseId(graph.getKbId().toString()),new GraphId(graph.getId()));
    }
    public OntologyRevision ontology(GraphRow graph) {
        var row=graphs.revision(graph.getOntologyRevisionId());
        if(row==null) throw new SemanticApiException(409,"ONTOLOGY_REVISION_MISSING","Pinned ontology revision no longer exists");
        return new OntologyRevision(new OntologyRevisionId(row.getId()),new OntologyId(row.getOntologyId()),row.getVersion(),wire.parsed(row),wire.policy(row));
    }
    public Map<EntityId,Entity> entities(GraphRow graph) {
        Map<EntityId,Entity> result=new HashMap<>();
        for (var row:graphs.entities(graph.getId())) {
            EntityId id=new EntityId(row.getId());
            result.put(id,new Entity(id,scope(graph),row.getIri(),Set.copyOf(Arrays.asList(wire.decode(row.getAssertedTypesJson(),String[].class))),row.getDisplayName()));
        }
        return Map.copyOf(result);
    }
    public AssertionPayload assertion(String text) {
        if(text==null || text.isBlank() || text.length()>100_000) throw bad("A standard assertionText is required (maximum 100000 characters)");
        try { return assertions.parse(text); }
        catch(IllegalArgumentException exception) { throw bad(exception.getMessage()); }
    }
    public StatementContent content(GraphRow graph, ProposeRequest request) {
        if(request==null) throw bad("Statement content required");
        return content(graph,request,assertion(request.assertionText()));
    }
    /** Reuse a parsed assertion within one bounded, version-pinned batch. Validation still checks indexes. */
    public StatementContent content(GraphRow graph, ProposeRequest request, AssertionPayload payload) {
        if(request==null || !Objects.equals(request.assertionText(),payload.functionalSyntax())) throw bad("Parsed assertion does not match request");
        try {
            if(request.evidenceIds()!=null && new HashSet<>(request.evidenceIds()).size()!=request.evidenceIds().size()) throw bad("Evidence references must be unique");
            Optional<PredicateRef> predicate=payload.predicateIri().map(iri -> switch(payload.kind()) {
                case POSITIVE_OBJECT_PROPERTY, NEGATIVE_OBJECT_PROPERTY -> PredicateRef.relation(iri);
                case POSITIVE_DATA_PROPERTY, NEGATIVE_DATA_PROPERTY -> PredicateRef.property(iri);
                default -> throw bad("Unexpected predicate in non-property assertion");
            });
            Validity validity=switch(Objects.requireNonNull(request.validityKind(),"validityKind")) {
                case "UNKNOWN" -> { if(request.validFrom()!=null || request.validTo()!=null) throw bad("UNKNOWN validity cannot have interval bounds"); yield Validity.unknown(); }
                case "INTERVAL" -> Validity.interval(request.validFrom(),request.validTo());
                default -> throw bad("Unsupported validityKind");
            };
            Set<EvidenceId> evidence=new LinkedHashSet<>();
            if(request.evidenceIds()!=null) request.evidenceIds().forEach(id->evidence.add(new EvidenceId(id)));
            return new StatementContent(scope(graph),new OntologyRevisionId(graph.getOntologyRevisionId()),new EntityId(request.subjectId()),predicate,payload,validity,evidence);
        } catch(SemanticApiException exception) { throw exception; }
        catch(RuntimeException exception) { throw bad("Statement identity, assertion or validity is invalid"); }
    }
    public void validate(GraphRow graph, StatementContent content) {
        validate(ontology(graph), graph, content);
    }
    public vip.mate.semantic.core.validation.ValidationReport validation(OntologyRevision revision, GraphRow graph, StatementContent content) {
        return validation(revision,graph,content,entities(graph));
    }
    public vip.mate.semantic.core.validation.ValidationReport validation(OntologyRevision revision, GraphRow graph,
            StatementContent content, Map<EntityId,Entity> entities) {
        return new StatementValidator(assertions).validate(scope(graph),revision,content,entities);
    }
    private void validate(OntologyRevision revision, GraphRow graph, StatementContent content) {
        var report=validation(revision,graph,content);
        if(!report.valid()) throw new SemanticApiException(422,report.violations().getFirst().code(),report.violations().getFirst().message());
    }
    public Optional<vip.mate.semantic.core.conflict.ConflictKind> compare(OntologyRevision revision, GraphRow graph,
            StatementContent left,StatementContent right) {
        var subject=entities(graph).get(left.subjectId());
        return new vip.mate.semantic.core.conflict.ConflictDetector().compare(revision,left,right,
                subject==null?Set.of():subject.assertedTypes());
    }
    private static SemanticApiException bad(String message) { return new SemanticApiException(422,"INVALID_STATEMENT",message); }
}
