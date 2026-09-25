package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
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
