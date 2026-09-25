package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dispatches fixed analysis packages and owns candidate and confirmed baseline revisions. */
@Service
public class BiddingAnalysisService implements BiddingResultHandler {
    private static final List<String> SKILLS = List.of("bidding-tender-profile", "bidding-elimination-analysis",
            "bidding-requirement-analysis", "bidding-scoring-analysis");
    private static final int SHARD_CODEPOINT_LIMIT = 12_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingTaskService tasks;
    private final BiddingRepository repository;
    private final BiddingSourceService sources;
    private final BiddingDependencies dependencies;
    private final BiddingAccess access;
    private final BiddingProjectService projects;
    private final BiddingSkillValidator validator;

    public BiddingAnalysisService(JdbcTemplate jdbc, ObjectMapper json, BiddingTaskService tasks,
            BiddingRepository repository, BiddingSourceService sources, BiddingDependencies dependencies, BiddingAccess access,
            BiddingProjectService projects, BiddingSkillValidator validator) {
        this.jdbc = jdbc; this.json = json; this.tasks = tasks; this.repository = repository; this.sources = sources;
        this.dependencies = dependencies; this.access = access; this.projects = projects; this.validator = validator;
    }

    @Override public Set<String> skillIds() { return Set.copyOf(SKILLS); }

    /** Returns only current, authorized analysis state. A withdrawn source invalidates read access to its derived results. */
    @Transactional(readOnly = true)
    public ObjectNode read(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope, scope.actorId());
        if (projects.get(scope) == null) throw BiddingAccess.error(404, "NOT_FOUND", "Project not found");
        ObjectNode result = json.createObjectNode();
        ObjectNode baseline = jdbc.query("SELECT r.id,r.kind,r.object_id,r.version,r.digest,r.status,r.payload_json,r.input_refs_json FROM mate_bidding_head h JOIN mate_bidding_revision r ON r.workspace_id=h.workspace_id AND r.project_id=h.project_id AND r.kind=h.kind AND r.object_id=h.object_id AND r.version=h.version WHERE h.workspace_id=? AND h.project_id=? AND h.kind='analysisBaseline' AND h.object_id='current'",
                rs -> rs.next() ? revisionRow(rs) : null, scope.workspaceId(), scope.projectId());
        if (baseline != null) {
            try {
                validateRevisionDependencies(scope, baseline);
                ObjectNode envelope = result.putObject("baseline"); envelope.set("ref", ref("analysisBaseline", "current", baseline.path("version").asLong(), baseline.path("digest").asText()));
                envelope.put("status", baseline.path("status").asText()); envelope.set("payload", baseline.path("payload").deepCopy());
            } catch (BiddingApiException stale) {
                if (stale.status() != 404 && stale.status() != 409 && stale.status() != 422) throw stale;
            }
        }
        ArrayNode groups = result.putArray("groups");
        List<String> groupIds = jdbc.query("SELECT input_json FROM mate_bidding_task WHERE workspace_id=? AND project_id=? ORDER BY created_at DESC,id",
                (rs,n) -> { try { return parseObject(rs.getString(1)).path("input").path("taskGroupId").asText(""); } catch(Exception e) { throw new IllegalStateException("Invalid analysis task snapshot",e); } }, scope.workspaceId(), scope.projectId());
        LinkedHashSet<String> unique = new LinkedHashSet<>(groupIds); int emitted=0;
        for (String groupId : unique) {
            if (groupId.isBlank() || emitted++ >= 20) continue;
            List<TaskRow> group = taskGroup(scope, groupId); if (group.isEmpty()) continue;
            BiddingTypes.Ref sourceSet = group.getFirst().refs().stream().filter(r -> "sourceSet".equals(r.kind())).findFirst().orElse(null);
            if (sourceSet == null) continue;
            try { dependencies.validateForRead(scope, List.of(sourceSet)); }
            catch (BiddingApiException stale) { if (stale.status()==404 || stale.status()==409 || stale.status()==422) continue; throw stale; }
            ObjectNode item = groups.addObject(); item.put("taskGroupId", groupId);
            boolean allSucceeded = group.stream().allMatch(task -> "SUCCEEDED".equals(task.status()));
            item.put("status", allSucceeded ? "SUCCEEDED" : group.stream().anyMatch(task -> Set.of("FAILED","STALE","CANCELLED").contains(task.status())) ? "PARTIAL" : "RUNNING");
            ObjectNode skills = item.putObject("skills"); ArrayNode conflicts = item.putArray("conflicts"); boolean complete = allSucceeded;
            int expectedShards = group.stream().mapToInt(task -> task.input().path("shardCount").asInt(0)).max().orElse(0);
            for (String skill : SKILLS) {
                List<ObjectNode> candidates = new ArrayList<>();
                for (int shard=0; shard<expectedShards; shard++) {
                    ObjectNode candidate = candidatePayload(scope, new BiddingTypes.Ref(candidateKind(skill), groupId, shard+1L, ""));
                    if (candidate == null) { complete=false; continue; }
                    candidates.add(candidate);
                }
                if (candidates.size()!=expectedShards || expectedShards<1) { complete=false; continue; }
                ArrayNode skillConflicts=json.createArrayNode(); ObjectNode payload=merge(skill,candidates,skillConflicts);
                ObjectNode edited=latestEdit(scope,groupId,skill); if(edited!=null) payload=(ObjectNode)edited.path("payload").deepCopy();
                skills.set(skill,payload); conflicts.addAll(skillConflicts);
            }
            item.put("complete", complete && skills.size()==SKILLS.size());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode readRevision(BiddingTypes.Scope scope, String revisionId) {
        access.requireReaderActor(scope, scope.actorId()); projects.get(scope);
        ObjectNode revision = jdbc.query("SELECT id,kind,object_id,version,digest,status,payload_json,input_refs_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND id=?",
                rs -> rs.next() ? revisionRow(rs) : null, scope.workspaceId(), scope.projectId(), revisionId);
        if (revision == null) throw BiddingAccess.error(404,"NOT_FOUND","Revision not found");
        validateRevisionDependencies(scope, revision);
        return revision;
    }

    private ObjectNode revisionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode value=json.createObjectNode(); value.put("id",rs.getString("id")); value.put("kind",rs.getString("kind")); value.put("objectId",rs.getString("object_id"));
        value.put("version",rs.getLong("version")); value.put("digest",rs.getString("digest")); value.put("status",rs.getString("status"));
        value.set("payload",parseObject(rs.getString("payload_json"))); try { value.set("inputRefs",json.readTree(rs.getString("input_refs_json"))); } catch(Exception e) { throw new IllegalStateException("Invalid revision dependencies",e); }
        return value;
    }
    private void validateRevisionDependencies(BiddingTypes.Scope scope,ObjectNode revision) {
        try {
            List<BiddingTypes.Ref> refs=json.convertValue(revision.path("inputRefs"),new TypeReference<List<BiddingTypes.Ref>>(){});
            if(!refs.isEmpty()) dependencies.validateForRead(scope,refs);
        } catch(BiddingApiException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("Invalid stored revision references",e); }
    }
    private ObjectNode ref(String kind,String id,long version,String digest) { ObjectNode node=json.createObjectNode(); node.put("kind",kind); node.put("id",id); node.put("version",version); node.put("digest",digest); return node; }

    /** Enqueues the four pinned skills against stable whole-block shards in one transaction. */
    @Transactional
    public ObjectNode dispatch(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        validateCommand(scope, command);
        access.requireActor(scope, scope.actorId());
        ObjectNode project = projects.get(scope);
        if (!matchesProject(project, command.expected())) throw BiddingAccess.error(409, "VERSION_CONFLICT", "Project changed; reload before dispatching analysis");
        BiddingTypes.Ref sourceSet = expectedSourceSet(command);
        dependencies.validate(scope, List.of(sourceSet));
        List<ObjectNode> blocks = sourceBlocks(scope, sourceSet);
        List<BiddingTypes.Ref> fixedRefs = fixedReferences(scope, sourceSet, blocks);
        dependencies.validate(scope, fixedRefs);
        List<List<ObjectNode>> shards = shard(blocks);
        String groupId = UUID.nameUUIDFromBytes((scope.workspaceId() + ":" + scope.projectId() + ":" + scope.actorId()
                + ":" + command.operationId()).getBytes(StandardCharsets.UTF_8)).toString();
        String requestDigest = digest(Map.of("action", command.action(), "expected", command.expected(), "payload", command.payload()));
        ObjectNode prior = operation(scope, command.operationId(), requestDigest);
        if (prior != null) return prior;
        ObjectNode pins = analystPins(project);
        ArrayNode taskIds = json.createArrayNode();
        for (String skill : SKILLS) {
            JsonNode pin = pinForName(pins, skill, scope.workspaceId());
            String skillId = pin.path("skillId").asText();
            String packageDigest = pin.path("digest").asText();
            if (skillId.isBlank() || !packageDigest.matches("[a-f0-9]{64}"))
                throw BiddingAccess.error(422, "ANALYSIS_SKILL_UNAVAILABLE", "An analysis skill package is not pinned: " + skill);
            for (int i = 0; i < shards.size(); i++) {
                ObjectNode input = json.createObjectNode(); input.put("schemaVersion", "1"); input.put("skillId", skill);
                input.put("taskGroupId", groupId); input.put("shardIndex", i); input.put("shardCount", shards.size());
                input.set("blocks", shards.get(i).stream().map(ObjectNode::deepCopy).collect(json::createArrayNode, ArrayNode::add, ArrayNode::addAll));
                input.putArray("readBlockIds"); // These are filled by the server from attempt receipts at acceptance.
                ObjectNode taskCommandPayload = json.createObjectNode();
                String operationId = "analysis-" + groupId + "-" + skillId + "-" + i;
                ObjectNode enqueued = tasks.enqueue(scope,
                        new BiddingTypes.Command(operationId, command.expected(), "DISPATCH_ANALYSIS", taskCommandPayload),
                        skillId, groupId, fixedRefs, input);
                taskIds.add(enqueued.path("taskId").asText());
            }
        }
        ObjectNode result = json.createObjectNode(); result.put("taskGroupId", groupId); result.set("taskIds", taskIds);
        result.put("shardCount", shards.size()); result.put("status", "QUEUED"); result.set("sourceSetRef", json.valueToTree(sourceSet));
        saveOperation(scope, command.operationId(), requestDigest, result);
        return result;
    }

    @Override @Transactional
    public BiddingTypes.Ref accept(BiddingTypes.Claim claim, ObjectNode payload) {
        if (claim == null || payload == null) throw new IllegalArgumentException("Claim and payload are required");
        String skill = skillName(claim.skill().skillId());
        if (!SKILLS.contains(skill)) throw BiddingAccess.error(422, "SKILL_ROLE_MISMATCH", "Task result is not an analysis skill");
        String groupId = claim.input().path("taskGroupId").asText(null);
        if (groupId == null || groupId.isBlank() || !skill.equals(claim.input().path("skillId").asText()))
            throw BiddingAccess.error(422, "ANALYSIS_INPUT_INVALID", "Task group or skill identity is missing");
        requirePinnedContract(claim.skill());
        ObjectNode trusted = trustedInput(claim);
        validator.validate(skill, payload, trusted);
        int shardIndex = trusted.path("shardIndex").asInt(-1);
        int shardCount = trusted.path("shardCount").asInt(0);
        if (shardIndex < 0 || shardCount < 1 || shardIndex >= shardCount)
            throw BiddingAccess.error(422, "ANALYSIS_INPUT_INVALID", "Shard position is invalid");
        String kind = candidateKind(skill);
        long version = shardIndex + 1L;
        ObjectNode candidate = json.createObjectNode(); candidate.put("skillId", skill); candidate.put("taskGroupId", groupId);
        candidate.put("taskId", claim.taskId()); candidate.put("shardIndex", shardIndex); candidate.put("shardCount", shardCount);
        candidate.set("payload", payload.deepCopy()); candidate.set("readBlockIds", trusted.path("readBlockIds").deepCopy());
        String canonical = canonical(candidate);
        if (repositoryInsertRevision(claim.scope(), kind, groupId, version, canonical, claim.inputRefs()) == 0)
            throw BiddingAccess.error(409, "ANALYSIS_SHARD_CONFLICT", "A candidate already exists for this analysis shard");
        return new BiddingTypes.Ref(kind, groupId, version, sha256(canonical));
    }

    @Transactional
    public ObjectNode confirm(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        validateCommand(scope, command); access.requireApprover(scope);
        String groupId = command.payload().path("taskGroupId").asText(null);
        if (groupId == null || groupId.isBlank()) throw BiddingAccess.error(400, "INVALID_REQUEST", "taskGroupId is required");
        Boolean projectLocked = jdbc.query("SELECT id FROM mate_bidding_project WHERE id=? AND workspace_id=? FOR UPDATE",
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), scope.projectId(), scope.workspaceId());
        if (!Boolean.TRUE.equals(projectLocked))
            throw BiddingAccess.error(404, "NOT_FOUND", "Project not found");
        ObjectNode project = projects.get(scope);
        if (!matchesProject(project, command.expected())) throw BiddingAccess.error(409, "VERSION_CONFLICT", "Project changed; reload before confirming analysis");
        String requestDigest = digest(Map.of("action", command.action(), "expected", command.expected(), "payload", command.payload()));
        ObjectNode prior = operation(scope, command.operationId(), requestDigest); if (prior != null) return prior;
        List<TaskRow> group = taskGroup(scope, groupId);
        if (group.isEmpty()) throw BiddingAccess.error(404, "NOT_FOUND", "Analysis task group not found");
        if (group.stream().anyMatch(t -> !"SUCCEEDED".equals(t.status())))
            throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "All analysis tasks must finish successfully before confirmation");
        for (String skill : SKILLS) requireOriginalCoverage(scope, skill, group);
        Map<String, List<ObjectNode>> shardPayloads = new LinkedHashMap<>();
        Map<String, Integer> shardCounts = new LinkedHashMap<>();
        Map<String, Set<Integer>> shardIndexes = new LinkedHashMap<>();
        for (TaskRow task : group) {
            String skill = task.input().path("skillId").asText();
            if (!SKILLS.contains(skill)) throw BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Task group contains an unsupported skill");
            int declaredCount = task.input().path("shardCount").asInt(0);
            int shardIndex = task.input().path("shardIndex").asInt(-1);
            Integer priorCount = shardCounts.putIfAbsent(skill, declaredCount);
            if (priorCount != null && priorCount != declaredCount)
                throw BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Task group has inconsistent shard counts");
            if (declaredCount < 1 || shardIndex < 0 || shardIndex >= declaredCount
                    || !shardIndexes.computeIfAbsent(skill, ignored -> new HashSet<>()).add(shardIndex))
                throw BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Task group has inconsistent or duplicate shard assignments");
            BiddingTypes.Ref ref = new BiddingTypes.Ref(candidateKind(skill), groupId, task.input().path("shardIndex").asLong() + 1,
                    "");
            ObjectNode payload = candidatePayload(scope, ref);
            if (payload == null || !task.id().equals(payload.path("taskId").asText()))
                throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "A completed task has no matching stored candidate revision");
            shardPayloads.computeIfAbsent(skill, ignored -> new ArrayList<>()).add(payload);
        }
        int expectedShards = shardCounts.getOrDefault(SKILLS.getFirst(), 0);
        if (!shardPayloads.keySet().containsAll(SKILLS) || expectedShards < 1
                || SKILLS.stream().anyMatch(skill -> shardCounts.getOrDefault(skill, -1) != expectedShards
                        || shardIndexes.getOrDefault(skill, Set.of()).size() != expectedShards))
            throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "All four analysis skills and every assigned shard are required");
        BiddingTypes.Ref sourceSet = group.getFirst().refs().stream().filter(r -> "sourceSet".equals(r.kind())).findFirst()
                .orElseThrow(() -> BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Confirmed source set is missing"));
        dependencies.validate(scope, List.of(sourceSet));
        ObjectNode baseline = json.createObjectNode(); baseline.put("schemaVersion", "1"); baseline.put("taskGroupId", groupId);
        baseline.set("sourceSetRef", json.valueToTree(sourceSet)); ObjectNode analyses = baseline.putObject("analyses");
        ArrayNode conflicts = baseline.putArray("conflicts"), resolutions = baseline.putArray("conflictResolutions");
        for (String skill : SKILLS) {
            ArrayNode skillConflicts = json.createArrayNode();
            ObjectNode merged = merge(skill, shardPayloads.get(skill), skillConflicts);
            ObjectNode edited = latestEdit(scope, groupId, skill);
            if (edited != null) {
                ObjectNode replacement = (ObjectNode) edited.path("payload").deepCopy();
                ArrayNode replacementConflicts = json.createArrayNode();
                merge(skill, List.of(wrapperFor(replacement)), replacementConflicts);
                Set<String> selfConflictIds = conflictIds(replacementConflicts);
                Map<String, ObjectNode> recorded = editResolutions(edited);
                for (JsonNode conflict : skillConflicts) {
                    ObjectNode disposition = recorded.get(conflict.path("id").asText());
                    if (disposition == null || !"MANUAL_REPLACEMENT".equals(disposition.path("disposition").asText())
                            || selfConflictIds.contains(conflict.path("id").asText())) conflicts.add(conflict);
                    else {
                        ObjectNode audit = disposition.deepCopy(); audit.put("skillId", skill); resolutions.add(audit);
                    }
                }
                merged = replacement;
            } else conflicts.addAll(skillConflicts);
            ObjectNode trusted = fullSourceInput(scope, sourceSet, skill);
            validator.validate(skill, merged, trusted);
            requireCompleteCoverage(skill, merged, group);
            analyses.set(skill, merged);
        }
        if (!conflicts.isEmpty()) {
            String unresolved = conflictIds(conflicts).stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
            throw BiddingAccess.error(422, "ANALYSIS_CONFLICTS", "Resolve every conflicting clause or score with EDIT_ANALYSIS_ITEM; unresolved conflict IDs: " + unresolved);
        }
        baseline.put("decision", "CONFIRMED");
        baseline.put("nextStageState", "CONFIGURATION_REQUIRED"); baseline.put("nextStageSkillId", "bidding-outline-planning");
        String canonical = canonical(baseline), kind = "analysisBaseline", objectId = "current";
        long version = nextRevisionVersion(scope, kind, objectId); String revisionId = UUID.randomUUID().toString(); Timestamp now = Timestamp.from(Instant.now());
        String digest = sha256(canonical);
        int inserted = jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                revisionId, scope.workspaceId(), scope.projectId(), kind, objectId, version, canonical, write(List.of(sourceSet)), "CONFIRMED", digest, now);
        if (inserted != 1) throw BiddingAccess.error(409, "ANALYSIS_BASELINE_CONFLICT", "Baseline could not be stored");
        BiddingTypes.Ref baselineRef = new BiddingTypes.Ref(kind, objectId, version, digest);
        replaceHead(scope, baselineRef);
        jdbc.update("INSERT INTO mate_bidding_decision(id,workspace_id,project_id,target_ref_json,decision,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), write(baselineRef), "CONFIRM_ANALYSIS",
                command.payload().path("reason").asText(null), scope.actorId(), now);
        ObjectNode result = json.createObjectNode(); result.set("ref", json.valueToTree(baselineRef)); result.set("baseline", baseline);
        result.put("nextStageState", "CONFIGURATION_REQUIRED");
        saveOperation(scope, command.operationId(), requestDigest, result); return result;
    }

    /** Saves a user-edited complete analysis payload as a separate immutable revision. */
    @Transactional
    public ObjectNode edit(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        validateCommand(scope, command); access.requireActor(scope, scope.actorId());
        String groupId = command.payload().path("taskGroupId").asText(null), skill = command.payload().path("skillId").asText(null);
        JsonNode payload = command.payload().path("payload");
        if (groupId == null || groupId.isBlank() || !SKILLS.contains(skill) || !payload.isObject())
            throw BiddingAccess.error(400, "INVALID_REQUEST", "taskGroupId, analysis skillId and complete payload are required");
        String requestDigest = digest(Map.of("action", command.action(), "expected", command.expected(), "payload", command.payload()));
        ObjectNode prior = operation(scope, command.operationId(), requestDigest); if (prior != null) return prior;
        List<TaskRow> group = taskGroup(scope, groupId);
        if (group.isEmpty()) throw BiddingAccess.error(404, "NOT_FOUND", "Analysis task group not found");
        requireOriginalCoverage(scope, skill, group);
        BiddingTypes.Ref sourceSet = group.getFirst().refs().stream().filter(r -> "sourceSet".equals(r.kind())).findFirst()
                .orElseThrow(() -> BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Confirmed source set is missing"));
        dependencies.validate(scope, List.of(sourceSet));
        ObjectNode trusted = fullSourceInput(scope, sourceSet, skill);
        validator.validate(skill, (ObjectNode) payload, trusted);
        String kind = editKind(skill); long version = nextRevisionVersion(scope, kind, groupId);
        ObjectNode revision = json.createObjectNode(); revision.put("skillId", skill); revision.put("taskGroupId", groupId);
        revision.put("actorId", scope.actorId()); revision.put("reason", command.payload().path("reason").asText("人工修订"));
        revision.set("payload", payload.deepCopy()); revision.set("sourceSetRef", json.valueToTree(sourceSet));
        ArrayNode requestedResolutions = json.createArrayNode(); JsonNode rawResolutions = command.payload().path("conflictResolutions");
        if (!rawResolutions.isMissingNode() && !rawResolutions.isArray())
            throw BiddingAccess.error(400, "INVALID_REQUEST", "conflictResolutions must be an array");
        Set<String> currentIds = conflictIds(detectCandidateConflicts(scope, groupId, skill));
        Set<String> seen = new HashSet<>(); Map<String, ObjectNode> requestedById = new LinkedHashMap<>();
        if (rawResolutions.isArray()) for (int i = 0; i < rawResolutions.size(); i++) {
            JsonNode resolution = rawResolutions.get(i);
            if (!resolution.isObject() || !"MANUAL_REPLACEMENT".equals(resolution.path("disposition").asText()))
                throw BiddingAccess.error(422, "ANALYSIS_RESOLUTION_INVALID", "Each conflict resolution must explicitly use MANUAL_REPLACEMENT");
            String conflictId = resolution.path("conflictId").asText();
            String reason = resolution.path("reason").asText();
            if (!currentIds.contains(conflictId) || !seen.add(conflictId) || reason.isBlank() || reason.length() > 1000)
                throw BiddingAccess.error(422, "ANALYSIS_RESOLUTION_INVALID", "Conflict resolution must identify one current conflict and include a reason");
            ObjectNode item = requestedResolutions.addObject(); item.put("conflictId", conflictId);
            item.put("disposition", "MANUAL_REPLACEMENT"); item.put("reason", reason);
            requestedById.put(conflictId, item);
        }
        revision.set("conflictResolutions", requestedResolutions);
        ArrayNode ledger = revision.putArray("conflictDispositions");
        for (String conflictId : new TreeSet<>(currentIds)) {
            ObjectNode disposition = ledger.addObject(); disposition.put("conflictId", conflictId);
            ObjectNode resolved = requestedById.get(conflictId);
            disposition.put("disposition", resolved == null ? "UNRESOLVED" : "MANUAL_REPLACEMENT");
            disposition.put("reason", resolved == null ? "尚未逐项处置" : resolved.path("reason").asText());
        }
        String canonical = canonical(revision); String digest = sha256(canonical); Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), kind, groupId, version, canonical, write(List.of(sourceSet)), "CANDIDATE", digest, now);
        BiddingTypes.Ref ref = new BiddingTypes.Ref(kind, groupId, version, digest);
        ObjectNode result = json.createObjectNode(); result.set("ref", json.valueToTree(ref)); result.put("status", "CANDIDATE");
        saveOperation(scope, command.operationId(), requestDigest, result); return result;
    }

    private ObjectNode trustedInput(BiddingTypes.Claim claim) {
        ObjectNode input = claim.input().deepCopy(); Set<String> readIds = new LinkedHashSet<>();
        String receipts = jdbc.query("SELECT tool_receipts_json FROM mate_bidding_attempt WHERE id=? AND task_id=? AND token=? AND workspace_id=? AND project_id=?",
                rs -> rs.next() ? rs.getString(1) : null, claim.attemptId(), claim.taskId(), claim.token(), claim.scope().workspaceId(), claim.scope().projectId());
        if (receipts == null) throw BiddingAccess.error(409, "ATTEMPT_STALE", "Attempt receipts are unavailable");
        try {
            JsonNode rows = json.readTree(receipts);
            for (JsonNode receipt : rows) if ("bidding_read_source".equals(receipt.path("tool").asText())) {
                String sourceId = receipt.path("sourceId").asText(); long version = receipt.path("version").asLong(); String blockId = receipt.path("blockId").asText();
                boolean assigned = false;
                for (JsonNode block : input.path("blocks")) if (blockId.equals(block.path("id").asText())
                        && sourceId.equals(block.path("sourceId").asText()) && version == block.path("version").asLong()) assigned = true;
                if (assigned) readIds.add(blockId);
            }
        } catch (Exception e) { throw new IllegalStateException("Invalid persisted read receipts", e); }
        for (JsonNode block : input.path("blocks")) {
            if (!block.isObject()) continue;
            // Result acceptance runs on the task worker, outside the originating
            // HTTP security context. Resolve the exact source snapshot through
            // the repository using the server-owned claim scope instead of the
            // interactive read endpoint's request-authentication guard.
            BiddingRepository.SourceRow source = repositorySource(claim, block);
            BiddingTypes.ReadBlock stored = storedBlock(source, block.path("id").asText());
            if (!Objects.equals(stored.text(), block.path("text").asText()))
                throw BiddingAccess.error(409, "ANALYSIS_INPUT_STALE", "A task block does not match its fixed source revision");
        }
        input.set("readBlockIds", json.valueToTree(readIds)); return input;
    }

    private BiddingRepository.SourceRow repositorySource(BiddingTypes.Claim claim, JsonNode block) {
        String sourceId = block.path("sourceId").asText();
        long version = block.path("version").asLong(-1);
        BiddingTypes.Ref fixed = claim.inputRefs().stream().filter(ref -> "source".equals(ref.kind())
                && sourceId.equals(ref.id()) && version == ref.version()).findFirst()
                .orElseThrow(() -> BiddingAccess.error(403, "SOURCE_NOT_IN_CLAIM", "Source is outside the fixed task inputs"));
        BiddingRepository.SourceRow source = repository.source(claim.scope().workspaceId(), claim.scope().projectId(), sourceId, version);
        if (source == null || !fixed.digest().equals(source.digest()))
            throw BiddingAccess.error(409, "SOURCE_STALE", "Fixed source reference is no longer available");
        return source;
    }

    private BiddingTypes.ReadBlock storedBlock(BiddingRepository.SourceRow source, String blockId) {
        try {
            return json.readValue(source.blocks(), new TypeReference<List<BiddingTypes.ReadBlock>>() {}).stream()
                    .filter(block -> blockId.equals(block.id())).findFirst()
                    .orElseThrow(() -> BiddingAccess.error(404, "BLOCK_NOT_FOUND", "Source block not found"));
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid source block snapshot", e);
        }
    }

    private ObjectNode fullSourceInput(BiddingTypes.Scope scope, BiddingTypes.Ref sourceSet, String skill) {
        ObjectNode input = json.createObjectNode(); input.put("schemaVersion", "1"); input.put("skillId", skill);
        input.put("taskGroupId", "manual"); input.put("shardIndex", 0); input.put("shardCount", 1);
        ArrayNode blocks = input.putArray("blocks"), reads = input.putArray("readBlockIds");
        for (ObjectNode sourceBlock : sourceBlocks(scope, sourceSet)) { blocks.add(sourceBlock); reads.add(sourceBlock.path("id").asText()); }
        return input;
    }

    private List<ObjectNode> sourceBlocks(BiddingTypes.Scope scope, BiddingTypes.Ref sourceSet) {
        String payloadRaw = jdbc.query("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='sourceSet' AND object_id='current' AND version=? AND digest=? AND status='CONFIRMED'",
                rs -> rs.next() ? rs.getString(1) : null, scope.workspaceId(), scope.projectId(), sourceSet.version(), sourceSet.digest());
        if (payloadRaw == null) throw BiddingAccess.error(404, "NOT_FOUND", "Confirmed source set not found");
        try {
            JsonNode payload = json.readTree(payloadRaw); List<ObjectNode> blocks = new ArrayList<>();
            for (JsonNode coverage : payload.path("coverage")) {
                if (!"IN_SCOPE".equals(coverage.path("disposition").asText())) continue;
                JsonNode refNode = coverage.path("sourceRef"); BiddingTypes.Ref sourceRef = json.treeToValue(refNode, BiddingTypes.Ref.class);
                BiddingTypes.ReadBlock block = sources.evidence(scope, sourceRef.id(), sourceRef.version(), coverage.path("blockId").asText());
                if (block.text() == null || block.text().isBlank()) throw BiddingAccess.error(422, "SOURCE_NOT_READY", "An assigned source block is not readable");
                ObjectNode item = json.createObjectNode(); item.put("id", block.id()); item.put("sourceId", sourceRef.id());
                item.put("version", sourceRef.version()); item.put("text", block.text()); item.put("locator", Objects.toString(block.locator(), "")); blocks.add(item);
            }
            return blocks;
        } catch (BiddingApiException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Invalid confirmed source-set snapshot", e); }
    }

    private List<BiddingTypes.Ref> fixedReferences(BiddingTypes.Scope scope, BiddingTypes.Ref sourceSet, List<ObjectNode> blocks) {
        LinkedHashMap<String, BiddingTypes.Ref> refs = new LinkedHashMap<>(); refs.put("sourceSet", sourceSet);
        for (ObjectNode block : blocks) {
            String sourceId = block.path("sourceId").asText(); long version = block.path("version").asLong();
            String key = sourceId + ":" + version;
            refs.computeIfAbsent(key, ignored -> {
                String digest = jdbc.query("SELECT digest FROM mate_bidding_source WHERE workspace_id=? AND project_id=? AND source_id=? AND version=?",
                        rs -> rs.next() ? rs.getString(1) : null, scope.workspaceId(), scope.projectId(), sourceId, version);
                if (digest == null) throw BiddingAccess.error(404, "NOT_FOUND", "Source not found");
                return new BiddingTypes.Ref("source", sourceId, version, digest);
            });
        }
        return List.copyOf(refs.values());
    }

    private List<List<ObjectNode>> shard(List<ObjectNode> blocks) {
        if (blocks.isEmpty()) throw BiddingAccess.error(422, "SOURCE_SET_EMPTY", "The confirmed source set has no readable blocks");
        List<List<ObjectNode>> result = new ArrayList<>(); List<ObjectNode> current = new ArrayList<>(); int count = 0;
        for (ObjectNode block : blocks) {
            int length = block.path("text").asText().codePointCount(0, block.path("text").asText().length());
            if (length > SHARD_CODEPOINT_LIMIT) throw BiddingAccess.error(422, "INPUT_BLOCK_TOO_LARGE", "A source block exceeds the 12,000 character analysis limit");
            if (!current.isEmpty() && count + length > SHARD_CODEPOINT_LIMIT) { result.add(List.copyOf(current)); current.clear(); count = 0; }
            current.add(block); count += length;
        }
        if (!current.isEmpty()) result.add(List.copyOf(current)); return List.copyOf(result);
    }

    private ObjectNode merge(String skill, List<ObjectNode> wrappers, ArrayNode conflicts) {
        List<ObjectNode> payloads = wrappers.stream().sorted(Comparator.comparingInt(p -> p.path("shardIndex").asInt())).map(p -> (ObjectNode) p.path("payload")).toList();
        ObjectNode merged = json.createObjectNode().put("schemaVersion", "1");
        if (skill.equals("bidding-tender-profile")) {
            ObjectNode basic = merged.putObject("basicInfo");
            for (String field : List.of("project", "tenderer", "lot")) basic.set(field, mergeScalar(skill, "/basicInfo/" + field, payloads.stream().map(p -> p.path("basicInfo").path(field)).toList(), conflicts));
            for (String field : List.of("deadlines", "deliveryConditions", "mandatoryOutline", "formatRequirements"))
                detectConflicts(skill, field, payloads, "name", List.of("value", "reason"), conflicts);
            for (String field : List.of("deadlines", "deliveryConditions", "mandatoryOutline", "formatRequirements", "unknowns")) merged.set(field, mergeArray(payloads, field));
        } else {
            String field = switch (skill) { case "bidding-elimination-analysis" -> "items"; case "bidding-requirement-analysis" -> "requirements"; default -> "criteria"; };
            if (skill.equals("bidding-elimination-analysis")) detectConflicts(skill, field, payloads, "text", List.of("scope", "trigger"), conflicts);
            else if (skill.equals("bidding-requirement-analysis")) detectConflicts(skill, field, payloads, "text", List.of("category", "constraints", "acceptance"), conflicts);
            else detectConflicts(skill, field, payloads, "title", List.of("parentId", "score", "unit", "rule", "requiredProof"), conflicts);
            merged.set(field, mergeArray(payloads, field));
            if (skill.equals("bidding-scoring-analysis")) {
                detectConflicts(skill, "totalChecks", payloads, "name", List.of("criterionIds", "statedTotal", "calculatedTotal", "difference"), conflicts);
                merged.set("totalChecks", mergeArray(payloads, "totalChecks"));
            }
        }
        merged.set("coverage", mergeCoverage(payloads)); merged.set("warnings", mergeArray(payloads, "warnings")); return merged;
    }

    private ObjectNode wrapperFor(ObjectNode payload) {
        ObjectNode wrapper = json.createObjectNode(); wrapper.put("shardIndex", 0); wrapper.set("payload", payload); return wrapper;
    }

    private Set<String> conflictIds(ArrayNode conflicts) {
        Set<String> ids = new LinkedHashSet<>();
        conflicts.forEach(conflict -> ids.add(conflict.path("id").asText()));
        return ids;
    }

    private Map<String, ObjectNode> editResolutions(ObjectNode edit) {
        Map<String, ObjectNode> result = new HashMap<>();
        for (JsonNode item : edit.path("conflictDispositions"))
            if (item.isObject() && item.path("conflictId").isTextual()) result.put(item.path("conflictId").asText(), (ObjectNode) item);
        return result;
    }

    private void requireCompleteCoverage(String skill, ObjectNode payload, List<TaskRow> group) {
        Set<String> assigned = new TreeSet<>(), processed = new TreeSet<>(), unprocessed = new TreeSet<>();
        for (TaskRow task : group) if (skill.equals(task.input().path("skillId").asText()))
            task.input().path("blocks").forEach(block -> assigned.add(block.path("id").asText()));
        payload.path("coverage").path("processedBlockIds").forEach(block -> processed.add(block.asText()));
        payload.path("coverage").path("unprocessedBlockIds").forEach(block -> unprocessed.add(block.asText()));
        if (!unprocessed.isEmpty() || !assigned.equals(processed))
            throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "Every assigned source block must be processed for " + skill);
    }

    /** Manual revisions may refine a complete candidate, but can never replace evidence that an original shard was actually read and processed. */
    private void requireOriginalCoverage(BiddingTypes.Scope scope, String skill, List<TaskRow> group) {
        int expectedShards = -1; Set<Integer> indexes = new HashSet<>();
        for (TaskRow task : group) {
            if (!skill.equals(task.input().path("skillId").asText())) continue;
            int count = task.input().path("shardCount").asInt(0), index = task.input().path("shardIndex").asInt(-1);
            if (count < 1 || index < 0 || index >= count || expectedShards >= 0 && expectedShards != count || !indexes.add(index))
                throw BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "Original analysis shard assignments are inconsistent for " + skill);
            expectedShards = count;
            ObjectNode candidate = candidatePayload(scope, new BiddingTypes.Ref(candidateKind(skill),
                    task.input().path("taskGroupId").asText(), index + 1L, ""));
            if (candidate == null || !task.id().equals(candidate.path("taskId").asText()))
                throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "An original shard has no matching stored candidate for " + skill);
            Set<String> assigned = new TreeSet<>(), read = new TreeSet<>(), processed = new TreeSet<>(), unprocessed = new TreeSet<>();
            task.input().path("blocks").forEach(block -> assigned.add(block.path("id").asText()));
            candidate.path("readBlockIds").forEach(block -> read.add(block.asText()));
            candidate.path("payload").path("coverage").path("processedBlockIds").forEach(block -> processed.add(block.asText()));
            candidate.path("payload").path("coverage").path("unprocessedBlockIds").forEach(block -> unprocessed.add(block.asText()));
            if (assigned.isEmpty() || !assigned.equals(read) || !assigned.equals(processed) || !unprocessed.isEmpty())
                throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "Every original shard block must have a server read receipt and be processed for " + skill);
        }
        if (expectedShards < 1 || indexes.size() != expectedShards)
            throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "Every original shard must exist for " + skill);
    }

    private ArrayNode detectCandidateConflicts(BiddingTypes.Scope scope, String groupId, String skill) {
        ArrayNode conflicts = json.createArrayNode(); List<ObjectNode> wrappers = new ArrayList<>();
        for (TaskRow task : taskGroup(scope, groupId)) {
            if (!skill.equals(task.input().path("skillId").asText())) continue;
            if (!"SUCCEEDED".equals(task.status())) throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "Analysis tasks must succeed before conflicts can be edited");
            BiddingTypes.Ref ref = new BiddingTypes.Ref(candidateKind(skill), groupId, task.input().path("shardIndex").asLong() + 1, "");
            ObjectNode candidate = candidatePayload(scope, ref);
            if (candidate == null || !task.id().equals(candidate.path("taskId").asText()))
                throw BiddingAccess.error(409, "ANALYSIS_INCOMPLETE", "A matching candidate is required before conflict resolutions can be recorded");
            wrappers.add(candidate);
        }
        if (wrappers.isEmpty()) throw BiddingAccess.error(409, "ANALYSIS_GROUP_INVALID", "No task exists for the edited skill");
        merge(skill, wrappers, conflicts); return conflicts;
    }

    /** Report inconsistent repeated facts instead of letting shard order choose one. */
    private void detectConflicts(String skill, String field, List<ObjectNode> payloads, String identityField,
            List<String> comparedFields, ArrayNode conflicts) {
        Map<String, List<JsonNode>> byIdentity = new TreeMap<>();
        for (ObjectNode payload : payloads) for (JsonNode item : payload.path(field)) {
            String identity = item.path(identityField).asText();
            if (!identity.isBlank()) byIdentity.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(item);
        }
        byIdentity.forEach((identity, values) -> {
            List<String> signatures = values.stream().map(item -> {
                ObjectNode projection = json.createObjectNode();
                comparedFields.forEach(name -> projection.set(name, item.path(name)));
                return canonical(projection);
            }).distinct().toList();
            if (signatures.size() > 1) addConflict(conflicts, skill, "/" + field + "/" + identity, values);
        });
    }

    private JsonNode mergeScalar(String skill, String path, List<JsonNode> values, ArrayNode conflicts) {
        List<JsonNode> known = values.stream().filter(v -> !v.isMissingNode() && !v.isNull() && !v.asText().isBlank()).distinct().toList();
        if (known.size() > 1) addConflict(conflicts, skill, path, known);
        return known.isEmpty() ? json.nullNode() : known.getFirst().deepCopy();
    }

    private ArrayNode mergeArray(List<ObjectNode> payloads, String field) {
        TreeMap<String, JsonNode> unique = new TreeMap<>();
        for (ObjectNode payload : payloads) for (JsonNode node : payload.path(field)) {
            String key = mergeKey(node);
            JsonNode existing = unique.get(key);
            if (existing == null) unique.put(key, node.deepCopy());
            else if (node.isObject() && existing.isObject() && node.path("evidenceRefs").isArray()) {
                TreeMap<String, JsonNode> refs = new TreeMap<>();
                existing.path("evidenceRefs").forEach(ref -> refs.put(canonical(ref), ref));
                node.path("evidenceRefs").forEach(ref -> refs.put(canonical(ref), ref));
                ArrayNode combined = json.createArrayNode(); refs.values().forEach(combined::add);
                ((ObjectNode) existing).set("evidenceRefs", combined);
            }
        }
        ArrayNode result = json.createArrayNode(); unique.values().forEach(result::add); return result;
    }

    private String mergeKey(JsonNode node) {
        if (!node.isObject() || !node.path("evidenceRefs").isArray()) return canonical(node);
        ObjectNode facts = ((ObjectNode) node).deepCopy();
        facts.remove(List.of("id", "evidenceRefs"));
        return canonical(facts);
    }

    private ObjectNode mergeCoverage(List<ObjectNode> payloads) {
        ObjectNode coverage = json.createObjectNode(); TreeSet<String> processed = new TreeSet<>(), unprocessed = new TreeSet<>();
        for (ObjectNode payload : payloads) {
            payload.path("coverage").path("processedBlockIds").forEach(n -> processed.add(n.asText()));
            payload.path("coverage").path("unprocessedBlockIds").forEach(n -> unprocessed.add(n.asText()));
        }
        unprocessed.removeAll(processed); coverage.set("processedBlockIds", json.valueToTree(processed)); coverage.set("unprocessedBlockIds", json.valueToTree(unprocessed)); return coverage;
    }

    private void addConflict(ArrayNode conflicts, String skill, String path, List<JsonNode> values) {
        ObjectNode conflict = conflicts.addObject(); conflict.put("id", sha256(skill + path + canonical(json.valueToTree(values))).substring(0, 24));
        conflict.put("skillId", skill); conflict.put("field", path); conflict.set("values", json.valueToTree(values));
    }

    private ObjectNode latestEdit(BiddingTypes.Scope scope, String groupId, String skill) {
        String raw = jdbc.query("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, scope.workspaceId(), scope.projectId(), editKind(skill), groupId);
        return raw == null ? null : parseObject(raw);
    }

    private ObjectNode candidatePayload(BiddingTypes.Scope scope, BiddingTypes.Ref ref) {
        String raw = jdbc.query("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND digest=? AND status='CANDIDATE'",
                rs -> rs.next() ? rs.getString(1) : null, scope.workspaceId(), scope.projectId(), ref.kind(), ref.id(), ref.version(),
                revisionDigest(scope, ref));
        return raw == null ? null : parseObject(raw);
    }

    private String revisionDigest(BiddingTypes.Scope scope, BiddingTypes.Ref ref) {
        return jdbc.query("SELECT digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=?",
                rs -> rs.next() ? rs.getString(1) : "", scope.workspaceId(), scope.projectId(), ref.kind(), ref.id(), ref.version());
    }

    private List<TaskRow> taskGroup(BiddingTypes.Scope scope, String groupId) {
        return jdbc.query("SELECT id,status,input_json,input_refs_json FROM mate_bidding_task WHERE workspace_id=? AND project_id=? ORDER BY created_at,id",
                (rs, n) -> {
                    try {
                        ObjectNode stored = parseObject(rs.getString("input_json")); ObjectNode input = parseObject(stored.path("input").toString());
                        return input.path("taskGroupId").asText().equals(groupId)
                                ? new TaskRow(rs.getString("id"), rs.getString("status"), input, json.readValue(rs.getString("input_refs_json"), new TypeReference<List<BiddingTypes.Ref>>() {})) : null;
                    } catch (Exception e) { throw new IllegalStateException("Invalid stored analysis task", e); }
                }, scope.workspaceId(), scope.projectId()).stream().filter(Objects::nonNull).toList();
    }

    private void requirePinnedContract(BiddingTypes.SkillPin pin) {
        for (String file : List.of("SKILL.md", "input.schema.json", "output.schema.json"))
            if (pin.files() == null || !pin.files().containsKey(file) || pin.files().get(file).isBlank())
                throw BiddingAccess.error(422, "SKILL_CONTRACT_MISSING", "Pinned analysis skill is missing " + file);
        try {
            JsonNode schema = json.readTree(pin.files().get("output.schema.json"));
            if (!schema.isObject() || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean())
                throw BiddingAccess.error(422, "SKILL_CONTRACT_INVALID", "Pinned output schema must reject unknown root fields");
        } catch (BiddingApiException e) { throw e; }
        catch (Exception e) { throw BiddingAccess.error(422, "SKILL_CONTRACT_INVALID", "Pinned output schema is invalid JSON"); }
    }

    private ObjectNode analystPins(ObjectNode project) {
        JsonNode analyst = project.path("bindings").path("analyst");
        if (!analyst.hasNonNull("agentId") || !analyst.path("skillPins").isArray())
            throw BiddingAccess.error(422, "EMPLOYEE_UNAVAILABLE", "An analyst with the four analysis skills must be assigned");
        return (ObjectNode) analyst;
    }

    private JsonNode pinForName(ObjectNode analyst, String name, String workspaceId) {
        for (JsonNode pin : analyst.path("skillPins")) {
            String id = pin.path("skillId").asText();
            String actual = jdbc.query("SELECT name FROM mate_skill WHERE id=? AND workspace_id=? AND deleted=0",
                    rs -> rs.next() ? rs.getString(1) : null, Long.valueOf(id), Long.valueOf(workspaceId));
            if (name.equals(actual)) return pin;
        }
        return json.nullNode();
    }

    private String skillName(String id) {
        try { return jdbc.queryForObject("SELECT name FROM mate_skill WHERE id=?", String.class, Long.valueOf(id)); }
        catch (NumberFormatException | org.springframework.dao.EmptyResultDataAccessException e) { return id; }
    }

    private BiddingTypes.Ref expectedSourceSet(BiddingTypes.Command command) {
        BiddingTypes.Ref ref = command.expected();
        JsonNode value = command.payload().path("sourceSetRef"); if (value.isObject()) ref = json.convertValue(value, BiddingTypes.Ref.class);
        if (ref == null || !"sourceSet".equals(ref.kind()) || !"current".equals(ref.id()) || ref.version() < 1 || ref.digest() == null)
            throw BiddingAccess.error(400, "INVALID_SOURCE_SET_REF", "A confirmed sourceSetRef is required");
        return ref;
    }

    private void validateCommand(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        if (scope == null || scope.projectId() == null || command == null || command.operationId() == null || command.operationId().isBlank()
                || command.operationId().length() > 128 || command.expected() == null || command.payload() == null || command.action() == null)
            throw BiddingAccess.error(400, "INVALID_REQUEST", "A scoped command with operationId, expected ref and payload is required");
    }

    private boolean matchesProject(ObjectNode project, BiddingTypes.Ref ref) {
        return ref != null && "project".equals(ref.kind()) && project.path("id").asText().equals(ref.id())
                && project.path("version").asLong() == ref.version() && project.path("ref").path("digest").asText().equals(ref.digest());
    }

    private ObjectNode operation(BiddingTypes.Scope scope, String id, String digest) {
        List<StoredOperation> found = jdbc.query("SELECT request_digest,result_json FROM mate_bidding_operation WHERE workspace_id=? AND actor_id=? AND operation_id=?",
                (rs, n) -> new StoredOperation(rs.getString(1), parseObject(rs.getString(2))), scope.workspaceId(), scope.actorId(), id);
        if (found.isEmpty()) return null;
        if (!found.getFirst().digest().equals(digest)) throw BiddingAccess.error(409, "OPERATION_CONFLICT", "operationId was already used with a different command");
        return found.getFirst().result();
    }

    private void saveOperation(BiddingTypes.Scope scope, String id, String digest, ObjectNode result) {
        jdbc.update("INSERT INTO mate_bidding_operation(workspace_id,actor_id,operation_id,request_digest,result_json,created_at) VALUES(?,?,?,?,?,?)",
                scope.workspaceId(), scope.actorId(), id, digest, write(result), Timestamp.from(Instant.now()));
    }

    private int repositoryInsertRevision(BiddingTypes.Scope scope, String kind, String objectId, long version, String payload, List<BiddingTypes.Ref> refs) {
        return jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), kind, objectId, version, payload, write(refs), "CANDIDATE",
                sha256(payload), Timestamp.from(Instant.now()));
    }

    private long nextRevisionVersion(BiddingTypes.Scope scope, String kind, String objectId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=?",
                Long.class, scope.workspaceId(), scope.projectId(), kind, objectId); return value == null ? 1 : value + 1;
    }

    private void replaceHead(BiddingTypes.Scope scope, BiddingTypes.Ref ref) {
        ObjectNode selected = json.valueToTree(ref);
        int changed = jdbc.update("UPDATE mate_bidding_head SET version=?,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=?",
                ref.version(), write(selected), scope.workspaceId(), scope.projectId(), ref.kind(), ref.id());
        if (changed == 0) jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",
                scope.workspaceId(), scope.projectId(), ref.kind(), ref.id(), ref.version(), write(selected));
    }

    private String candidateKind(String skill) { return "analysisCandidate_" + skill; }
    private String editKind(String skill) { return "analysisEdit_" + skill; }
    private ObjectNode parseObject(String raw) { try { JsonNode node = json.readTree(raw); if (!node.isObject()) throw new IllegalStateException("Expected object"); return (ObjectNode) node; } catch (Exception e) { throw new IllegalStateException("Invalid persisted analysis JSON", e); } }
    private String canonical(Object value) { return canonical(json.valueToTree(value)); }
    private String canonical(JsonNode node) {
        if (node.isObject()) { ObjectNode sorted = json.createObjectNode(); TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add); for (String name : names) sorted.set(name, json.valueToTree(canonicalValue(node.get(name)))); return write(sorted); }
        return write(node);
    }
    private Object canonicalValue(JsonNode node) {
        if (node.isObject()) { TreeMap<String,Object> map = new TreeMap<>(); node.fields().forEachRemaining(e -> map.put(e.getKey(), canonicalValue(e.getValue()))); return map; }
        if (node.isArray()) { List<Object> values = new ArrayList<>(); node.forEach(n -> values.add(canonicalValue(n))); return values; }
        return node;
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Cannot serialize analysis result", e); } }
    private String digest(Object value) { return sha256(canonical(value)); }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record TaskRow(String id, String status, ObjectNode input, List<BiddingTypes.Ref> refs) {}
    private record StoredOperation(String digest, ObjectNode result) {}
}
