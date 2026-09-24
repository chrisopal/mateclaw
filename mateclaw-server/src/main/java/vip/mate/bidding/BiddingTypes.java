package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BiddingTypes {
    private BiddingTypes() {}
    public record Scope(String workspaceId, String actorId, String projectId) {}
    public record Ref(String kind, String id, long version, String digest) {}
    public record Command(String operationId, Ref expected, String action, ObjectNode payload) {}
    public record NewProject(String operationId, String name, String lotName, String ownerId) {}
    public record SkillPin(String skillId, String version, String digest, Map<String,String> files) {}
    public record Claim(Scope scope, String taskId, String attemptId, String token, int attemptNo,
        int cycleAttempt, Instant deadlineAt, String agentId, SkillPin skill, String modelConfigId,
        String configDigest, List<Ref> inputRefs, ObjectNode input) {}
    public record Failure(String code, String category, Long retryAfterMs, boolean resultUnknown,
        boolean partial, boolean stopped) {}
    public record Execution(ObjectNode payload, Failure failure, String loadedSkillDigest,
        String configDigest, String rejectedOutput) {
        public Execution {
            if ((payload == null) == (failure == null)) throw new IllegalArgumentException("Exactly one of payload and failure is required");
            if (failure != null && payload != null) throw new IllegalArgumentException("Failure cannot contain a valid payload");
        }
    }
    public record ReadBlock(String id, Integer pdfPage, String locator, String text, String kind, String quality) {}
    public record Extraction(List<ReadBlock> blocks, boolean complete, List<String> problems) {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
}
