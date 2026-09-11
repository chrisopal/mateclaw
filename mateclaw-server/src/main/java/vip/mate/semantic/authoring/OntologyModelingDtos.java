package vip.mate.semantic.authoring;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.semantic.web.OntologyDtos.*;

/** Durable authoring candidates. Samples are JSON only, never semantic facts. */
public final class OntologyModelingDtos {
    private OntologyModelingDtos() {}
    public record SourceVersion(String knowledgeBaseId, String sourceRef, String sourceDigest) {}
    public record CreateTask(String operationId, String ontologyId, Metadata newOntology,
            String baseRevisionId, String goal, List<SourceVersion> sources) {}
    public record Evidence(String clientId, String knowledgeBaseId, String sourceRef,
            String sourceDigest, String exactQuote, Integer occurrence, String origin) {}
    public record SubmitProposal(String operationId, @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=vip.mate.semantic.web.SemanticCounterSerializer.class) Long expectedDraftVersion, List<ModelEdit> changes,
            List<Evidence> evidence, List<String> questions, List<JsonNode> samples) {}
    public record Decision(String operationId, String decision, Map<String,String> answers) {}
    public record StageChange(String stage, String message) {}
    public record Proposal(String id, SubmitProposal input, String status, String reason,
            Map<String,String> answers, ModelCommandResult result) {}
    public record IncrementalRequest(String expectedObservedDigest, String goal) {}
    public record Incremental(String reviewId, String bindingId, String baseRevisionId,
            String oldSnapshotId, String newSnapshotId, String oldDigest, String newDigest,
            List<String> affectedAxiomIds) {}
    public record Task(String id, String ontologyId, String draftId, String goal,
            List<SourceVersion> sources, String stage, String message, List<Proposal> proposals, Incremental incremental) {
        public Task(String id, String ontologyId, String draftId, String goal, List<SourceVersion> sources,
                String stage, String message, List<Proposal> proposals) {
            this(id, ontologyId, draftId, goal, sources, stage, message, proposals, null);
        }
    }
}
