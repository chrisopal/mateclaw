package vip.mate.semantic.application.extraction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import vip.mate.semantic.core.fact.StatementContent;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

/** Consumer-owned boundaries. Implementations enforce workspace/graph scope and atomic CAS writes. */
public final class ExtractionPorts {
    private ExtractionPorts() {}
    public interface ContextPort {
        vip.mate.semantic.core.ontology.OntologyRevision ontology(Actor actor, String graphId);
        vip.mate.semantic.core.identity.GraphScope scope(Actor actor, String graphId);
        void requireSnapshot(Actor actor, String graphId, SourceSnapshotInput snapshot);
        ModelConfiguration configuration(Actor actor, String graphId, String configurationId);
        java.util.Map<vip.mate.semantic.core.identity.SemanticIds.EntityId, vip.mate.semantic.core.fact.Entity> entities(Actor actor, String graphId);
    }
    public interface SourceContentPort {
        SourceSnapshotInput read(Actor actor, String graphId, String sourceRef);
    }
    public interface AccessPolicyPort {
        void require(Actor actor, String graphId, String sourceRef, Action action);
    }
    public interface ExtractionModelPort {
        ModelResult extract(ModelRequest request);
    }
    public interface ExtractionTaskRepository {
        /** Same operation and hash replay; same operation with a different hash must conflict. */
        Task insertOrReplay(Task task);
        Optional<Task> find(Actor actor, String graphId, String taskId);
        Task findClaimed(Lease lease);
        Task requeue(Actor actor, String graphId, String taskId, String operationId, Instant now);
        /** Atomic capacity check and claim, with a new fencing generation for every claim. */
        Optional<Attempt> claim(String workerId, Instant now, Instant expiresAt,
                                int workspaceConcurrency, int instanceConcurrency);
        boolean heartbeat(Lease lease, Instant expiresAt);
        boolean progress(Lease lease, int completedChunks, Instant now);
        /** Every worker write requires task ID, generation and RUNNING state to match. */
        boolean complete(Lease lease, Attempt attempt, List<Suggestion> suggestions, Instant now);
        boolean complete(Lease lease, Attempt attempt, List<Suggestion> suggestions, Instant now, String explanation);
        boolean fail(Lease lease, Attempt attempt, Instant now);
        boolean update(Task task, long expectedVersion);
        List<Suggestion> suggestions(Actor actor, String graphId, String taskId, int offset, int limit);
        Optional<Suggestion> suggestion(Actor actor, String graphId, String suggestionId);
        boolean edit(Actor actor, String graphId, Suggestion suggestion, long expectedVersion);
        Optional<Receipt> receipt(Actor actor, String graphId, String suggestionId, long editVersion);
        void reserveSubmission(Actor actor, String graphId, Suggestion suggestion, String operationId, String requestHash);
        void saveReceipt(Actor actor, String graphId, Receipt receipt);
    }
    public interface KnowledgeSubmissionPort {
        SubmissionRef submit(Actor actor, String graphId, StatementContent content,
                             SourceSnapshotInput source, List<Quote> quotes, String operationId);
    }
}
