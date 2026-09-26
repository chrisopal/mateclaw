package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only source tool constrained to the immutable refs in the active claim. */
@Component
public class BiddingReadTool {
    private final BiddingEmployeeRuntime runtime;
    private final BiddingRepository repository;
    private final BiddingDependencies dependencies;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BiddingReadTool(BiddingEmployeeRuntime runtime, BiddingRepository repository,
            BiddingDependencies dependencies, JdbcTemplate jdbc, ObjectMapper json) {
        this.runtime = runtime;
        this.repository = repository;
        this.dependencies = dependencies;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Tool(name = "bidding_read_source", description = "Read one authorized block from a fixed tender source reference.")
    @Transactional
    public String readSource(@ToolParam(description = "Fixed source identifier") String sourceId,
            @ToolParam(description = "Fixed source version") long version,
            @ToolParam(description = "Block identifier") String blockId, ToolContext context) {
        BiddingTypes.Claim claim = runtime.claim(context);
        BiddingToolScope.require(claim, "bidding_read_source", sourceId + "/" + version + "/" + blockId);
        requireAssigned(claim, sourceId, version, blockId);
        BiddingTypes.Ref ref = claim.inputRefs().stream().filter(r -> "source".equals(r.kind())
                && sourceId.equals(r.id()) && version == r.version()).findFirst()
                .orElseThrow(() -> BiddingAccess.error(403, "SOURCE_NOT_IN_CLAIM", "Source is outside the fixed task inputs"));
        dependencies.validate(claim.scope(), claim.inputRefs());
        BiddingRepository.SourceRow source = repository.source(claim.scope().workspaceId(), claim.scope().projectId(), sourceId, version);
        if (source == null || !ref.digest().equals(source.digest()))
            throw BiddingAccess.error(409, "SOURCE_STALE", "Fixed source reference is no longer available");
        try {
            var blocks = json.readValue(source.blocks(), new TypeReference<java.util.List<BiddingTypes.ReadBlock>>() {});
            BiddingTypes.ReadBlock block = blocks.stream().filter(b -> blockId.equals(b.id())).findFirst()
                    .orElseThrow(() -> BiddingAccess.error(404, "BLOCK_NOT_FOUND", "Source block not found"));
            if (!"READABLE".equals(block.quality()) || block.text() == null || block.text().isBlank())
                throw BiddingAccess.error(422, "BLOCK_NOT_READABLE", "Source block requires manual review");
            runtime.requireActive(claim);
            appendReceipt(claim, Map.of("tool", "bidding_read_source", "sourceId", sourceId,
                    "version", version, "blockId", blockId, "digest", source.digest(), "readAt", Instant.now().toString()));
            return block.text();
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid source block snapshot", e);
        }
    }

    /**
     * Read several task-assigned blocks in one model/tool iteration. The model
     * supplies identifiers only; all text and all receipts are resolved from
     * the server-owned claim and the fixed source snapshots.
     */
    @Tool(name = "bidding_read_sources", description = "Read a JSON array of authorized task-assigned source blocks in one call. "
            + "Argument format: [{\"sourceId\":\"...\",\"version\":1,\"blockId\":\"...\"}]. "
            + "Use only block identifiers from the task input; the server returns the fixed source text and records one read receipt per block.")
    @Transactional
    public String readSources(
            @ToolParam(description = "JSON array of assigned blocks: [{\"sourceId\":\"...\",\"version\":1,\"blockId\":\"...\"}]")
            String requestsJson, ToolContext context) {
        BiddingTypes.Claim claim = runtime.claim(context);
        BiddingToolScope.require(claim, "bidding_read_sources", requestsJson);
        List<RequestedBlock> requests = parseRequests(requestsJson);
        dependencies.validate(claim.scope(), claim.inputRefs());

        Set<String> unique = new HashSet<>();
        List<BiddingTypes.ReadBlock> blocks = new ArrayList<>(requests.size());
        List<RequestedBlock> accepted = new ArrayList<>(requests.size());
        Map<SourceKey, List<BiddingTypes.ReadBlock>> sourceCache = new HashMap<>();
        for (RequestedBlock request : requests) {
            String key = request.sourceId() + "\u0000" + request.version() + "\u0000" + request.blockId();
            if (!unique.add(key))
                throw BiddingAccess.error(422, "DUPLICATE_BLOCK", "A batch cannot contain the same source block twice");
            if (!isAssigned(claim, request))
                throw BiddingAccess.error(403, "BLOCK_NOT_ASSIGNED", "Source block is outside the fixed task input");
            blocks.add(readAuthorizedBlock(claim, request, sourceCache));
            accepted.add(request);
        }

        ObjectNode result = json.createObjectNode();
        ArrayNode resultBlocks = result.putArray("blocks");
        ArrayNode readIds = result.putArray("readBlockIds");
        for (int i = 0; i < blocks.size(); i++) {
            BiddingTypes.ReadBlock block = blocks.get(i);
            RequestedBlock request = accepted.get(i);
            ObjectNode value = resultBlocks.addObject();
            value.put("id", request.blockId());
            value.put("sourceId", request.sourceId());
            value.put("version", request.version());
            value.put("text", block.text());
            if (block.pdfPage() != null) value.put("pdfPage", block.pdfPage());
            if (block.locator() != null) value.put("locator", block.locator());
            if (block.kind() != null) value.put("kind", block.kind());
            readIds.add(request.blockId());
            appendReceipt(claim, Map.of("tool", "bidding_read_source", "sourceId", request.sourceId(),
                    "version", request.version(), "blockId", request.blockId(),
                    "digest", sourceDigest(claim, request), "readAt", Instant.now().toString(),
                    "batch", true));
        }
        result.put("count", blocks.size());
        try {
            return json.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize source batch", e);
        }
    }

    private List<RequestedBlock> parseRequests(String requestsJson) {
        if (requestsJson == null || requestsJson.isBlank())
            throw BiddingAccess.error(422, "BATCH_READ_INVALID", "A non-empty JSON array of source blocks is required");
        try {
            JsonNode root = json.readTree(requestsJson);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > 256)
                throw BiddingAccess.error(422, "BATCH_READ_INVALID", "Batch size must be between 1 and 256 blocks");
            List<RequestedBlock> requests = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                if (!item.isObject() || !item.path("sourceId").isTextual()
                        || !item.path("blockId").isTextual() || !item.path("version").canConvertToLong())
                    throw BiddingAccess.error(422, "BATCH_READ_INVALID", "Each block requires sourceId, version and blockId");
                String sourceId = item.path("sourceId").asText();
                String blockId = item.path("blockId").asText();
                long version = item.path("version").asLong();
                if (sourceId.isBlank() || sourceId.length() > 128 || blockId.isBlank() || blockId.length() > 128 || version < 1)
                    throw BiddingAccess.error(422, "BATCH_READ_INVALID", "Source and block identifiers are invalid");
                requests.add(new RequestedBlock(sourceId, version, blockId));
            }
            return requests;
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw BiddingAccess.error(422, "BATCH_READ_INVALID", "Batch block JSON is invalid");
        }
    }

    private boolean isAssigned(BiddingTypes.Claim claim, RequestedBlock request) {
        return isAssigned(claim, request.sourceId(), request.version(), request.blockId());
    }

    private void requireAssigned(BiddingTypes.Claim claim, String sourceId, long version, String blockId) {
        if (!isAssigned(claim, sourceId, version, blockId))
            throw BiddingAccess.error(403, "BLOCK_NOT_ASSIGNED", "Source block is outside the fixed task input");
    }

    private boolean isAssigned(BiddingTypes.Claim claim, String sourceId, long version, String blockId) {
        for (JsonNode block : claim.input().path("blocks")) {
            if (blockId.equals(block.path("id").asText())
                    && sourceId.equals(block.path("sourceId").asText())
                    && version == block.path("version").asLong()) return true;
        }
        return false;
    }

    private BiddingTypes.ReadBlock readAuthorizedBlock(BiddingTypes.Claim claim, RequestedBlock request,
            Map<SourceKey, List<BiddingTypes.ReadBlock>> sourceCache) {
        String sourceId = request.sourceId();
        long version = request.version();
        String blockId = request.blockId();
        BiddingTypes.Ref ref = claim.inputRefs().stream().filter(r -> "source".equals(r.kind())
                && sourceId.equals(r.id()) && version == r.version()).findFirst()
                .orElseThrow(() -> BiddingAccess.error(403, "SOURCE_NOT_IN_CLAIM", "Source is outside the fixed task inputs"));
        List<BiddingTypes.ReadBlock> sourceBlocks = sourceCache.computeIfAbsent(new SourceKey(sourceId, version),
                key -> loadSourceBlocks(claim, ref));
        return sourceBlocks.stream()
                .filter(block -> blockId.equals(block.id())).findFirst()
                .map(this::requireReadable)
                .orElseThrow(() -> BiddingAccess.error(404, "BLOCK_NOT_FOUND", "Source block not found"));
    }

    private List<BiddingTypes.ReadBlock> loadSourceBlocks(BiddingTypes.Claim claim, BiddingTypes.Ref ref) {
        BiddingRepository.SourceRow source = repository.source(claim.scope().workspaceId(), claim.scope().projectId(), ref.id(), ref.version());
        if (source == null || !ref.digest().equals(source.digest()))
            throw BiddingAccess.error(409, "SOURCE_STALE", "Fixed source reference is no longer available");
        try {
            return json.readValue(source.blocks(), new TypeReference<List<BiddingTypes.ReadBlock>>() {});
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid source block snapshot", e);
        }
    }

    private BiddingTypes.ReadBlock requireReadable(BiddingTypes.ReadBlock block) {
        if (!"READABLE".equals(block.quality()) || block.text() == null || block.text().isBlank())
            throw BiddingAccess.error(422, "BLOCK_NOT_READABLE", "Source block requires manual review");
        return block;
    }

    private String sourceDigest(BiddingTypes.Claim claim, RequestedBlock request) {
        return claim.inputRefs().stream().filter(ref -> "source".equals(ref.kind())
                && request.sourceId().equals(ref.id()) && request.version() == ref.version())
                .map(BiddingTypes.Ref::digest).findFirst()
                .orElseThrow(() -> BiddingAccess.error(403, "SOURCE_NOT_IN_CLAIM", "Source is outside the fixed task inputs"));
    }

    private record RequestedBlock(String sourceId, long version, String blockId) {}
    private record SourceKey(String sourceId, long version) {}

    @Tool(name = "bidding_read_material", description = "Read a project-bound material snapshot.")
    public String readMaterial(@ToolParam(description = "Material identifier") String materialId, ToolContext context) {
        BiddingTypes.Claim claim = runtime.claim(context);
        BiddingToolScope.require(claim, "bidding_read_material", materialId);
        throw BiddingAccess.error(422, "MATERIAL_NOT_BOUND", "This task has no bound material snapshot");
    }

    @Transactional
    public void appendReceipt(BiddingTypes.Claim claim, Map<String, Object> receipt) {
        String raw = jdbc.query("SELECT tool_receipts_json FROM mate_bidding_attempt a JOIN mate_bidding_task t ON t.id=a.task_id "
                        + "WHERE a.id=? AND a.task_id=? AND a.token=? AND a.state='RUNNING' AND t.status='RUNNING' "
                        + "AND t.active_attempt_id=a.id AND t.workspace_id=? AND t.project_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, claim.attemptId(), claim.taskId(), claim.token(),
                claim.scope().workspaceId(), claim.scope().projectId());
        if (raw == null) throw BiddingAccess.error(409, "ATTEMPT_STALE", "Task attempt is no longer active");
        // The row lock prevents attempt replacement while we append. Recheck the
        // full authorization after taking it so actor/config/input revocation
        // between source read and receipt persistence cannot be accepted.
        runtime.requireActive(claim);
        try {
            var receipts = json.readValue(raw, new TypeReference<ArrayList<Map<String, Object>>>() {});
            receipts.add(receipt);
            String updated = json.writeValueAsString(receipts);
            int changed = jdbc.update("UPDATE mate_bidding_attempt SET tool_receipts_json=? WHERE id=? AND task_id=? AND token=? AND state='RUNNING'",
                    updated, claim.attemptId(), claim.taskId(), claim.token());
            if (changed != 1) throw BiddingAccess.error(409, "ATTEMPT_STALE", "Task attempt is no longer active");
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid tool receipt snapshot", e);
        }
    }
}
