package vip.mate.semantic.query;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import vip.mate.semantic.web.SemanticCounterSerializer;

public final class SemanticContextDtos {
    private SemanticContextDtos() {}
    public record ReasoningOptions(
            vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope scope,
            vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind task,
            String individualIri,String axiomFunctionalSyntax) {}
    public record Request(String question,Set<String> entityIris,Instant asOf,Integer budget,String cursor,ReasoningOptions reasoning) {
        public Request(String question,Set<String> entityIris,Instant asOf,Integer budget,String cursor) {
            this(question,entityIris,asOf,budget,cursor,null);
        }
    }
    public record Ontology(String ontologyId,String revisionId,int version,String documentDigest,String importLockDigest,String policyVersion,String ontologyIri,String versionIri) {}
    public record OntologyAnnotation(String originRevisionId,String artifactId,
        vip.mate.semantic.core.ontology.OntologyAnnotationDescriptor annotation) {}
    public record Snapshot(Instant asOf,String validityPolicy,Instant capturedAt) {}
    public record Term(String iri,List<String> kinds,List<String> labels,String definition,List<String> axiomIds) {}
    public record Axiom(String id,String originRevisionId,String artifactId,String kind,String functionalSyntax,
                        Set<String> signature,String origin,List<String> sourceBindingIds,List<SourceState> sources) {}
    public record SourceState(String bindingId,String snapshotId,String digest,String currentState,String reviewState) {}
    public record Validity(String kind,Instant from,Instant to) {}
    public record Fact(String statementId,int revision,String assertion,String status,List<String> evidenceIds,Validity validity,List<FactSourceReview> sourceReviews) {}
    public record FactSourceReview(String reviewId,String snapshotId,String observedDigest,String sourceState,String reviewState,String decision) {}
    public record Evidence(String id,String snapshotId,String digest,String exactQuote,int startCodePoint,int endCodePoint) {}
    public record Reasoning(String scope,String engine,String engineVersion,String status,String task,
                            List<String> supportedDatatypes,List<Conclusion> conclusions,
                            String outcome,String inputDigest,String explanationStatus) {
        public Reasoning(String scope,String engine,String engineVersion,String status,String task,
                         List<String> supportedDatatypes,List<Conclusion> conclusions) {
            this(scope,engine,engineVersion,status,task,supportedDatatypes,conclusions,null,null,"UNAVAILABLE");
        }
    }
    public record Conclusion(String assertion,String origin,List<String> premiseAxiomIds,List<String> premiseFactRevisions,String explanationStatus) {}
    public record Coverage(boolean truncated,Integer omittedAxioms,Integer omittedFacts,String reason,int usedTokens,
                           boolean tokenEstimate,boolean dependencyClosureComplete,String nextCursor,Integer omittedConclusions,Integer omittedOntologyAnnotations) {
        public Coverage(boolean truncated,Integer omittedAxioms,Integer omittedFacts,String reason,int usedTokens,
                        boolean tokenEstimate,boolean dependencyClosureComplete,String nextCursor,Integer omittedConclusions) {
            this(truncated,omittedAxioms,omittedFacts,reason,usedTokens,tokenEstimate,dependencyClosureComplete,nextCursor,omittedConclusions,0);
        }
        public Coverage(boolean truncated,Integer omittedAxioms,Integer omittedFacts,String reason,int usedTokens,
                        boolean tokenEstimate,boolean dependencyClosureComplete,String nextCursor) {
            this(truncated,omittedAxioms,omittedFacts,reason,usedTokens,tokenEstimate,dependencyClosureComplete,nextCursor,0);
        }
    }
    public record Context(String schema,String traceId,String graphId,
            @JsonSerialize(using=SemanticCounterSerializer.class) long graphMutationVersion,
            Ontology ontology,Snapshot snapshot,List<Term> terms,List<Axiom> axioms,List<Fact> facts,
            List<Evidence> evidence,Reasoning reasoning,Coverage coverage,List<String> warnings,List<OntologyAnnotation> ontologyAnnotations) {
        public Context(String schema,String traceId,String graphId,long graphMutationVersion,Ontology ontology,Snapshot snapshot,
                List<Term> terms,List<Axiom> axioms,List<Fact> facts,List<Evidence> evidence,Reasoning reasoning,Coverage coverage,List<String> warnings) {
            this(schema,traceId,graphId,graphMutationVersion,ontology,snapshot,terms,axioms,facts,evidence,reasoning,coverage,warnings,List.of());
        }
    }
}
