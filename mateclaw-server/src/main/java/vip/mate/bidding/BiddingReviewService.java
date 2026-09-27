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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Independent, immutable technical review and human finding decisions. */
@Service
public class BiddingReviewService implements BiddingResultHandler {
    private static final String SKILL = "bidding-technical-review";
    private static final String REVIEW = "review";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingAccess access;
    private final BiddingRepository repository;
    private final BiddingDependencies dependencies;
    private final BiddingTaskService tasks;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    private final BiddingMaterials materials;
    private final BiddingSkillValidator validator;

    public BiddingReviewService(JdbcTemplate jdbc, ObjectMapper json, BiddingAccess access,
            BiddingRepository repository, BiddingDependencies dependencies, BiddingTaskService tasks,
            ObjectProvider<BiddingEmployeeBindings> employees, BiddingMaterials materials, BiddingSkillValidator validator) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.repository = repository;
        this.dependencies = dependencies; this.tasks = tasks; this.employees = employees;
        this.materials = materials; this.validator = validator;
    }

    @Override public Set<String> skillIds() { return Set.of(SKILL); }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordDispatchFailure(BiddingTypes.Scope scope,BiddingTypes.Ref manuscript,String reasonCode) {
        access.requireActor(scope,scope.actorId());
        if(manuscript==null||!"manuscript".equals(manuscript.kind()))return;
        String safeCode=reasonCode!=null&&reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")?reasonCode:"REVIEW_DISPATCH_FAILED";
        ObjectNode payload=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));
        payload.put("status","NOT_DISPATCHED").put("reasonCode",safeCode).put("recordedAt",Instant.now().toString());
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        payload.put("reviewerId",project.path("bindings").path("reviewer").path("agentId").asText(""));
        payload.put("reviewerConfigDigest",project.path("bindings").path("reviewer").path("configDigest").asText(""));
        saveBusinessRevision(scope,"reviewDispatch",manuscript.digest().substring(0,48),payload,List.of(manuscript),"NOT_DISPATCHED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public ObjectNode dispatch(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireActor(scope, scope.actorId());
        if (command == null || !"DISPATCH_REVIEW".equals(command.action()) || command.payload() == null
                || command.operationId() == null || command.operationId().isBlank() || command.operationId().length() > 128)
            throw BiddingAccess.error(400, "INVALID_REQUEST", "DISPATCH_REVIEW command is invalid");
        only(command.payload(), Set.of("manuscriptRef"), "REVIEW_REQUEST_INVALID");
        BiddingTypes.Ref manuscriptRef = ref(command.payload().path("manuscriptRef"));
        if (manuscriptRef == null || !same(command.expected(), manuscriptRef)
                || !"manuscript".equals(manuscriptRef.kind()))
            throw BiddingAccess.error(400, "INVALID_REQUEST", "Expected manuscriptRef must identify the reviewed manuscript");
        ObjectNode project = repository.findProject(scope.workspaceId(), scope.projectId());
        if (project == null) throw BiddingAccess.error(404, "NOT_FOUND", "Project not found");
        String writerId = project.path("bindings").path("writer").path("agentId").asText("");
        String reviewerId = project.path("bindings").path("reviewer").path("agentId").asText("");
        if (!reviewerId.isBlank() && reviewerId.equals(writerId)) throw BiddingAccess.error(422, "REVIEWER_MUST_DIFFER", "Reviewer must be a different employee from the writer");
        ObjectNode manuscriptRow = requiredManuscript(scope, manuscriptRef);
        ObjectNode manuscript = (ObjectNode) manuscriptRow.path("payload");
        BiddingTypes.Ref outlineRef = ref(manuscript.path("outlineRef"));
        ObjectNode outlineRow = requiredBusiness(scope, outlineRef, "outline");
        ObjectNode baselineRow = requiredBaseline(scope, outlineRow);
        ObjectNode baseline = (ObjectNode) baselineRow.path("payload");
        JsonNode analyses = baseline.path("analyses");
        List<BiddingTypes.Ref> chapterRefs = refs(manuscriptRow.path("refs")).stream().filter(r -> "chapter".equals(r.kind())).toList();
        Map<String, BiddingTypes.Ref> chapterById = new LinkedHashMap<>();
        for (BiddingTypes.Ref chapterRef : chapterRefs) chapterById.put(chapterRef.id(), chapterRef);
        List<ReviewChapter> chapters = chapters(manuscript, chapterById);
        if (chapters.isEmpty()) throw BiddingAccess.error(422, "MANUSCRIPT_INCOMPLETE", "Manuscript has no chapters to review");
        ObjectNode projectOutline = (ObjectNode) outlineRow.path("payload");
        Map<String, JsonNode> requirementById = indexed(analyses.path("bidding-requirement-analysis").path("requirements"));
        ArrayNode technicalRequirements = json.createArrayNode();
        for (JsonNode requirement : analyses.path("bidding-requirement-analysis").path("requirements"))
            if ("TECHNICAL".equals(requirement.path("category").asText())) technicalRequirements.add(requirement.deepCopy());
        ArrayNode criteria = copyArray(analyses.path("bidding-scoring-analysis").path("criteria"));
        ArrayNode eliminationItems = copyArray(analyses.path("bidding-elimination-analysis").path("items"));
        ObjectNode evidenceSnapshot = sourceEvidence(scope, baseline);
        ensureHumanTodos(scope, project, analyses.path("bidding-requirement-analysis").path("requirements"));
        if (reviewerId.isBlank()) return notReady("REVIEWER_NOT_BOUND");
        if (!evidenceSnapshot.path("unreadableSourceRefs").isEmpty()) return notReady("SOURCE_UNREADABLE");
        List<BiddingTypes.Ref> materialRefs = chapterRefs.stream().map(r -> repository.businessRefs(repository.businessRevision(scope, r)))
                .flatMap(List::stream).filter(r -> "material".equals(r.kind())).distinct().toList();
        ObjectNode materialSnapshot = materials.snapshotForReviewer(scope, reviewerId, materialRefs);
        ObjectNode reviewer = project.path("bindings").path("reviewer").deepCopy();
        ObjectNode reviewerPins = (ObjectNode) reviewer;
        String skillId = pinnedSkillId(reviewerPins, scope.workspaceId());
        // The task input digest captures every frozen source, material and business ref. A changed reviewer,
        // employee config, skill package or manuscript produces a different review key and cannot reuse old work.
        String reviewKey = digest(Map.ofEntries(Map.entry("manuscript", manuscriptRef), Map.entry("outline", outlineRef),
                Map.entry("baseline", ref(baselineRow.path("ref"))), Map.entry("chapters", chapterRefs),
                Map.entry("requirements", technicalRequirements), Map.entry("criteria", criteria),
                Map.entry("eliminationItems", eliminationItems), Map.entry("evidenceSnapshot", evidenceSnapshot),
                Map.entry("materials", materialSnapshot), Map.entry("reviewerId", reviewerId),
                Map.entry("reviewerConfig", reviewer.path("configDigest").asText("")), Map.entry("skillId", skillId),
                Map.entry("skillDigest", pinDigest(reviewerPins, skillId))));
        List<BiddingTypes.Ref> stableSources = refs(baselineRow.path("refs")).stream()
                .filter(r -> "sourceSet".equals(r.kind()) || "source".equals(r.kind())).toList();
        if (stableSources.isEmpty()) throw BiddingAccess.error(422, "SOURCE_SET_INCOMPLETE", "Review requires its exact source snapshot");
        dependencies.validate(scope, stableSources);
        ArrayNode taskItems = json.createArrayNode();
        for (ReviewChapter chapter : chapters) {
            ObjectNode input = reviewInput(baselineRow, outlineRef, manuscriptRef, criteria,
                    eliminationItems, evidenceSnapshot, materialSnapshot);
            ArrayNode scopedChapters = input.putArray("chapters"); scopedChapters.add(chapter.node());
            ArrayNode scopedRequirements = input.putArray("requirements");
            JsonNode outlineChapter = findOutlineChapter(projectOutline.path("chapters"), chapter.chapterId());
            for (JsonNode id : outlineChapter.path("requirementRefs")) {
                JsonNode requirement = requirementById.get(id.asText());
                if (requirement != null && "TECHNICAL".equals(requirement.path("category").asText())) {
                    scopedRequirements.add(requirement.deepCopy());
                }
            }
            String target = target(reviewKey, "chapter:" + chapter.chapterId());
            ObjectNode enqueued = enqueue(scope, command, manuscriptRef, stableSources, input, target, skillId);
            taskItems.add(enqueued);
        }
        ObjectNode crossInput = reviewInput(baselineRow, outlineRef, manuscriptRef, criteria,
                eliminationItems, evidenceSnapshot, materialSnapshot);
        ArrayNode crossChapters = crossInput.putArray("chapters"); chapters.forEach(c -> crossChapters.add(c.node()));
        crossInput.set("requirements", technicalRequirements.deepCopy());
        taskItems.add(enqueue(scope, command, manuscriptRef, stableSources, crossInput, target(reviewKey, "cross"), skillId));
        ObjectNode result = json.createObjectNode().put("status", "QUEUED").put("reviewKey", reviewKey);
        result.set("manuscriptRef", json.valueToTree(manuscriptRef)); result.set("tasks", taskItems);
        result.put("chapterTaskCount", chapters.size()).put("crossChapterTaskCount", 1);
        return result;
    }

    private ObjectNode enqueue(BiddingTypes.Scope scope, BiddingTypes.Command command, BiddingTypes.Ref manuscript,
            List<BiddingTypes.Ref> sourceRefs, ObjectNode input, String target, String skillId) {
        String taskOperation = "review-" + digest(target).substring(0, 56);
        return tasks.enqueue(scope, new BiddingTypes.Command(taskOperation, manuscript, "DISPATCH_REVIEW", json.createObjectNode()),
                skillId, target, sourceRefs, input);
    }

    private ObjectNode reviewInput(ObjectNode baseline, BiddingTypes.Ref outline, BiddingTypes.Ref manuscript,
            ArrayNode criteria, ArrayNode elimination, ObjectNode evidence, ObjectNode material) {
        ObjectNode input = json.createObjectNode().put("schemaVersion", "1");
        input.set("baselineRef", json.valueToTree(ref(baseline.path("ref"))));
        input.set("outlineRef", json.valueToTree(outline)); input.set("manuscriptRef", json.valueToTree(manuscript));
        input.putArray("chapters"); input.putArray("requirements"); input.set("criteria", criteria.deepCopy());
        input.set("eliminationItems", elimination.deepCopy());
        ObjectNode snapshot = evidence.deepCopy(); snapshot.set("materials", material.deepCopy());
        input.set("evidenceSnapshot", snapshot);
        return input;
    }

    @Override @Transactional
    public BiddingTypes.Ref accept(BiddingTypes.Claim claim, ObjectNode output) {
        if (claim == null || output == null) throw new IllegalArgumentException("Claim and review output are required");
        ObjectNode input = claim.input();
        BiddingTypes.Ref manuscript = ref(input.path("manuscriptRef"));
        if (manuscript == null) throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Exact manuscript reference is required");
        BiddingTypes.Ref baseline = ref(input.path("baselineRef")), outline = ref(input.path("outlineRef"));
        ObjectNode manuscriptRow = requiredManuscript(claim.scope(), manuscript);
        if (!same(ref(((ObjectNode) manuscriptRow.path("payload")).path("outlineRef")), outline))
            throw BiddingAccess.error(409, "DEPENDENCY_STALE", "Review outline differs from the manuscript snapshot");
        ObjectNode baselineRow = requiredBusiness(claim.scope(), baseline, "analysisBaseline");
        validator.validateReview(output, input);
        for (BiddingTypes.Ref materialRef : refs(input.path("evidenceSnapshot").path("materials").path("items"), "ref"))
            materials.requireReviewerReadable(claim.scope(), claim.agentId(), materialRef);
        String target = input.path("_biddingTargetId").asText("");
        if (!target.startsWith("review:")) throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Review target metadata is missing");
        String objectId = digest(target).substring(0, 48);
        ObjectNode stored = output.deepCopy();
        ObjectNode meta = stored.putObject("_bidding"); meta.set("manuscriptRef", json.valueToTree(manuscript));
        meta.set("outlineRef", json.valueToTree(outline)); meta.set("baselineRef", json.valueToTree(baseline));
        ArrayNode materialRefs = meta.putArray("materialRefs");
        for (JsonNode item : input.path("evidenceSnapshot").path("materials").path("items")) materialRefs.add(item.path("ref").deepCopy());
        int keySeparator = target.indexOf(':', "review:".length());
        if (keySeparator < 0) throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Review key metadata is invalid");
        meta.put("reviewKey", target.substring("review:".length(), keySeparator));
        meta.put("taskTargetId", target).put("reviewSegment", target.substring(keySeparator + 1).split(":", 2)[0]);
        meta.put("reviewerId", claim.agentId()).put("reviewerConfigDigest", claim.configDigest()).put("skillDigest", claim.skill().digest());
        long version = repository.maxRevisionVersion(claim.scope(), REVIEW, objectId) + 1;
        String payload = canonical(stored); String digest = sha(payload);
        BiddingTypes.Ref ref = new BiddingTypes.Ref(REVIEW, objectId, version, digest);
        repository.insertRevision(java.util.UUID.randomUUID().toString(), claim.scope().workspaceId(), claim.scope().projectId(), REVIEW,
                objectId, version, payload, write(claim.inputRefs()), "REVIEW_RESULT", digest, Timestamp.from(Instant.now()));
        for (JsonNode finding : output.path("findings")) {
            ObjectNode issue = json.createObjectNode(); issue.set("reviewRef", json.valueToTree(ref)); issue.set("finding", finding.deepCopy());
            String findingObject = digest(manuscript.digest() + ":" + objectId + ":" + finding.path("id").asText()).substring(0, 48);
            issue.put("findingId", finding.path("id").asText());
            BiddingTypes.Ref findingRef = saveBusinessRevision(claim.scope(), "reviewFinding", findingObject, issue, append(claim.inputRefs(), List.of(ref)), "OPEN");
        }
        return ref;
    }

    @Transactional
    public ObjectNode resolve(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireApprover(scope);
        validateCommand(command, "RESOLVE_FINDING");
        only(command.payload(), Set.of("findingRef", "decision", "reason", "evidenceRefs"), "FINDING_DECISION_INVALID");
        BiddingTypes.Ref findingRef = ref(command.payload().path("findingRef"));
        if (findingRef == null || !"reviewFinding".equals(findingRef.kind())) throw BiddingAccess.error(400, "FINDING_DECISION_INVALID", "findingRef must identify an immutable finding");
        String decision = command.payload().path("decision").asText("");
        String reason = bounded(command.payload().path("reason"), "reason", 2000);
        ArrayNode evidenceRefs = copyArray(command.payload().path("evidenceRefs"));
        ObjectNode findingRow = repository.businessRevision(scope, findingRef);
        if (findingRow == null || !"OPEN".equals(findingRow.path("status").asText()))
            throw BiddingAccess.error(404, "FINDING_NOT_FOUND", "Finding not found in this immutable review");
        if (latestDecision(scope,findingRef)!=null) throw BiddingAccess.error(409,"FINDING_ALREADY_RESOLVED","Finding already has a human decision");
        ObjectNode originalFinding = (ObjectNode) findingRow.path("finding");
        if ("DISMISS_WITH_EVIDENCE".equals(decision)) {
            if (evidenceRefs.isEmpty()) throw BiddingAccess.error(422, "FINDING_EVIDENCE_REQUIRED", "Dismissal requires evidence");
            validateDecisionEvidence(scope, originalFinding, evidenceRefs);
            if (blocksTechnicalApproval(originalFinding.path("category").asText(), originalFinding.path("severity").asText(), false)
                    && mandatoryMissing(originalFinding))
                throw BiddingAccess.error(422, "MANDATORY_PROOF_CANNOT_BE_DISMISSED", "A missing mandatory proof cannot be dismissed as risk acceptance");
        } else if ("DEFER_SUGGESTION".equals(decision)) {
            if (!"SUGGESTION".equals(originalFinding.path("severity").asText())
                    && !"STYLE_SUGGESTION".equals(originalFinding.path("category").asText()))
                throw BiddingAccess.error(422, "FINDING_DECISION_INVALID", "Only a general suggestion may be deferred");
        } else if (!"FIX".equals(decision)) throw BiddingAccess.error(400, "FINDING_DECISION_INVALID", "Unsupported finding decision");
        ObjectNode decisionPayload = json.createObjectNode(); decisionPayload.set("findingRef", json.valueToTree(findingRef));
        decisionPayload.set("finding", originalFinding.deepCopy()); decisionPayload.put("decision", decision).put("reason", reason);
        decisionPayload.set("evidenceRefs", evidenceRefs); decisionPayload.put("actorId", scope.actorId()); decisionPayload.put("createdAt", Instant.now().toString());
        BiddingTypes.Ref saved = saveBusinessRevision(scope, "findingDecision", findingRef.id(), decisionPayload, List.of(findingRef), decision);
        ObjectNode result = json.createObjectNode(); result.set("ref", json.valueToTree(saved)); result.put("decision", decision);
        if ("FIX".equals(decision)) result.set("selectedFindingRefs", json.createArrayNode().add(json.valueToTree(findingRef)));
        return result;
    }

    @Transactional
    public ObjectNode resolveHumanTodo(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireActor(scope, scope.actorId()); validateCommand(command, "RESOLVE_HUMAN_TODO");
        only(command.payload(), Set.of("todoRef", "reason", "evidenceRefs"), "HUMAN_TODO_INVALID");
        BiddingTypes.Ref todoRef = ref(command.payload().path("todoRef"));
        if (todoRef == null || !"HUMAN_TODO".equals(todoRef.kind())) throw BiddingAccess.error(400, "HUMAN_TODO_INVALID", "todoRef must identify a human todo");
        ObjectNode envelope = requiredBusiness(scope, todoRef, "HUMAN_TODO");
        if(!"OPEN".equals(envelope.path("status").asText()))throw BiddingAccess.error(409,"HUMAN_TODO_ALREADY_RESOLVED","Human todo is no longer open");
        ObjectNode row=(ObjectNode)envelope.path("payload");
        String owner = row.path("ownerId").asText("");
        if (!scope.actorId().equals(owner)) access.requireApprover(scope);
        String reason = bounded(command.payload().path("reason"), "reason", 2000);
        ArrayNode evidence = copyArray(command.payload().path("evidenceRefs"));
        if (evidence.isEmpty()) throw BiddingAccess.error(422, "HUMAN_TODO_EVIDENCE_REQUIRED", "Human todo resolution requires evidence");
        validateTodoEvidence(scope, row, evidence);
        ObjectNode resolved = row.deepCopy(); resolved.put("status", "RESOLVED").put("resolutionReason", reason)
                .put("resolvedBy", scope.actorId()).put("resolvedAt", Instant.now().toString()); resolved.set("resolutionEvidenceRefs", evidence);
        BiddingTypes.Ref saved = saveBusinessRevision(scope, "HUMAN_TODO", todoRef.id(), resolved,
                append(refs(envelope.path("refs")), evidenceSourceRefs(scope,evidence)), "RESOLVED");
        ObjectNode result = json.createObjectNode(); result.set("ref", json.valueToTree(saved)); result.put("status", "RESOLVED");
        return result;
    }

    public void requireReviewed(BiddingTypes.Scope scope, BiddingTypes.Ref manuscript) {
        access.requireActor(scope, scope.actorId());
        BiddingTypes.Ref latest = latestManuscript(scope);
        if (!same(latest, manuscript)) throw BiddingAccess.error(409, "REVIEW_STALE", "Review is not bound to the current manuscript");
        ObjectNode row = requiredManuscript(scope, manuscript);
        ObjectNode payload = (ObjectNode) row.path("payload");
        BiddingTypes.Ref outline = ref(payload.path("outlineRef"));
        ObjectNode outlineRow = requiredBusiness(scope, outline, "outline");
        ObjectNode baselineRow = requiredBaseline(scope, outlineRow);
        Map<String, JsonNode> reqById = indexed(baselineRow.path("payload").path("analyses").path("bidding-requirement-analysis").path("requirements"));
        Set<String> technicalIds = reqById.entrySet().stream().filter(e -> "TECHNICAL".equals(e.getValue().path("category").asText())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        List<BiddingTypes.Ref> chapterRefs = refs(row.path("refs")).stream().filter(r -> "chapter".equals(r.kind())).toList();
        List<ReviewChapter> chapters = chapters(payload, chapterRefs.stream().collect(java.util.stream.Collectors.toMap(BiddingTypes.Ref::id, r -> r)));
        Set<String> chapterIds = chapters.stream().map(ReviewChapter::chapterId).collect(java.util.stream.Collectors.toSet());
        ObjectNode project = repository.findProject(scope.workspaceId(), scope.projectId());
        String reviewerId = project.path("bindings").path("reviewer").path("agentId").asText("");
        if (reviewerId.isBlank() || reviewerId.equals(project.path("bindings").path("writer").path("agentId").asText("")))
            throw BiddingAccess.error(409, "REVIEWER_NOT_READY", "An independent reviewer must be bound");
        employees.getObject().validate(scope, reviewerId, project.path("bindings").path("reviewer").path("configDigest").asText(null));
        String currentSkill = pinnedSkillId((ObjectNode) project.path("bindings").path("reviewer"), scope.workspaceId());
        String currentSkillDigest = pinDigest((ObjectNode) project.path("bindings").path("reviewer"), currentSkill);
        List<ObjectNode> saved = jdbc.query("SELECT object_id,version,digest,payload_json,status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='review' ORDER BY created_at DESC",
                (rs, n) -> { ObjectNode r = object(rs.getString(4)); r.put("_status", rs.getString(5)).put("_objectId", rs.getString(1)).put("_version", rs.getLong(2)).put("_digest", rs.getString(3)); return r; },
                scope.workspaceId(), scope.projectId());
        Set<String> chapterCoverage = new LinkedHashSet<>(), requirementCoverage = new LinkedHashSet<>(); boolean cross = false;
        String currentReviewKey = null;
        for (ObjectNode review : saved) {
            if (!"REVIEW_RESULT".equals(review.path("_status").asText())) continue;
            ObjectNode body = review;
            BiddingTypes.Ref bound = ref(body.path("_bidding").path("manuscriptRef"));
            if (!same(bound, manuscript)) continue;
            ObjectNode meta = (ObjectNode) body.path("_bidding");
            if (!reviewerId.equals(meta.path("reviewerId").asText())
                    || !project.path("bindings").path("reviewer").path("configDigest").asText().equals(meta.path("reviewerConfigDigest").asText())
                    || !currentSkillDigest.equals(meta.path("skillDigest").asText())) continue;
            String groupKey = meta.path("reviewKey").asText("");
            if (currentReviewKey == null) currentReviewKey = groupKey;
            if (!currentReviewKey.equals(groupKey)) continue;
            JsonNode coverage = body.path("coverage");
            for (JsonNode item : coverage.path("chapterRefs")) chapterCoverage.add(ref(item).id());
            for (JsonNode item : coverage.path("requirementRefs")) requirementCoverage.add(item.asText());
            if ("cross".equals(meta.path("reviewSegment").asText())) cross = coverage.path("crossChapterReviewed").asBoolean(false);
        }
        if (!chapterCoverage.containsAll(chapterIds) || !requirementCoverage.containsAll(technicalIds) || !cross)
            throw BiddingAccess.error(409, "REVIEW_INCOMPLETE", "A complete review of this exact manuscript is required");
        for (ObjectNode review : saved) {
            if (!"REVIEW_RESULT".equals(review.path("_status").asText()) || !currentReviewKey.equals(review.path("_bidding").path("reviewKey").asText())) continue;
            if (!same(ref(review.path("_bidding").path("manuscriptRef")), manuscript)) continue;
            for (JsonNode finding : review.path("findings")) {
                BiddingTypes.Ref findingReference = findingRefFor(scope, refFromRow(review), finding.path("id").asText());
                if (findingReference == null) throw BiddingAccess.error(409,"REVIEW_FINDING_MISSING","Stored review finding reference is unavailable");
                String findingObject = findingReference.id();
                boolean resolved = hasResolution(scope, findingObject);
                if (blocksTechnicalApproval(finding.path("category").asText(), finding.path("severity").asText(), resolved))
                    throw BiddingAccess.error(409, "TECHNICAL_REVIEW_BLOCKED", "Unresolved technical review finding blocks approval");
            }
        }
        List<String> technicalTodos=jdbc.query("SELECT payload_json FROM mate_bidding_revision r WHERE r.workspace_id=? AND r.project_id=? AND r.kind='HUMAN_TODO' AND r.status='OPEN' AND r.version=(SELECT MAX(latest.version) FROM mate_bidding_revision latest WHERE latest.workspace_id=r.workspace_id AND latest.project_id=r.project_id AND latest.kind=r.kind AND latest.object_id=r.object_id)",
                (rs,n)->rs.getString(1),scope.workspaceId(),scope.projectId());
        for(String todoPayload:technicalTodos)if(object(todoPayload).path("affectsTechnical").asBoolean(false))
            throw BiddingAccess.error(409,"HUMAN_TODO_BLOCKS_TECHNICAL_APPROVAL","An unresolved business fact affects the technical response");
    }

    public static boolean blocksTechnicalApproval(String category, String severity, boolean resolved) {
        return !resolved && ("BLOCKER".equals(severity) || Set.of("MISSING_MANDATORY_PROOF", "UNANSWERED_TECHNICAL_REQUIREMENT",
                "UNSUPPORTED_COMMITMENT", "SOURCE_UNREADABLE", "VERSION_CONFLICT").contains(category));
    }

    private boolean hasResolution(BiddingTypes.Scope scope, String findingId) {
        String status = jdbc.query("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=? ORDER BY version DESC",
                rs -> rs.next() ? rs.getString(1) : null, scope.workspaceId(), scope.projectId(), findingId);
        return "DISMISS_WITH_EVIDENCE".equals(status) || "DEFER_SUGGESTION".equals(status);
    }

    public ArrayNode selectedFindings(BiddingTypes.Scope scope,String chapterId,JsonNode selected) {
        return selectedFindings(scope,chapterId,selected,latestManuscript(scope));
    }

    public ArrayNode selectedFindings(BiddingTypes.Scope scope,String chapterId,JsonNode selected,BiddingTypes.Ref manuscript) {
        access.requireActor(scope,scope.actorId());
        if(!selected.isArray()||selected.isEmpty())throw BiddingAccess.error(422,"REVISION_FINDING_REQUIRED","Selected findings are required");
        ArrayNode result=json.createArrayNode();Set<BiddingTypes.Ref> unique=new LinkedHashSet<>();
        for(JsonNode node:selected){BiddingTypes.Ref findingRef=ref(node);if(findingRef==null||!"reviewFinding".equals(findingRef.kind())||!unique.add(findingRef))throw BiddingAccess.error(422,"FINDING_REF_INVALID","Finding reference is invalid or repeated");
            ObjectNode row=repository.businessRevision(scope,findingRef);if(row==null||!"OPEN".equals(row.path("status").asText()))throw BiddingAccess.error(404,"FINDING_NOT_FOUND","Finding is unavailable");
            ObjectNode issue=row;ObjectNode review=reviewPayload(scope,ref(issue.path("reviewRef")));
            if(manuscript==null||!same(ref(review.path("_bidding").path("manuscriptRef")),manuscript))throw BiddingAccess.error(409,"REVIEW_STALE","Finding belongs to another manuscript");
            boolean targetsChapter=false;for(JsonNode chapter:issue.path("finding").path("chapterRefs"))if(chapterId.equals(chapter.path("id").asText()))targetsChapter=true;
            if(!targetsChapter)throw BiddingAccess.error(422,"FINDING_CHAPTER_MISMATCH","Selected finding does not target this chapter");
            String decision=jdbc.query("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=? ORDER BY version DESC",rs->rs.next()?rs.getString(1):null,scope.workspaceId(),scope.projectId(),findingRef.id());
            if(!"FIX".equals(decision))throw BiddingAccess.error(409,"FINDING_NOT_SELECTED_FOR_FIX","Resolve the finding as FIX before dispatching a revision");
            ObjectNode value=(ObjectNode)issue.path("finding").deepCopy();value.set("findingRef",json.valueToTree(findingRef));result.add(value);
        }
        return result;
    }

    @Transactional(readOnly=true)
    public ObjectNode read(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope,scope.actorId());
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());if(project==null)throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        BiddingTypes.Ref manuscript=latestManuscript(scope);ObjectNode out=json.createObjectNode();
        if(manuscript==null){out.putNull("manuscriptRef").put("status","NOT_ASSEMBLED");out.putArray("tasks");out.putArray("findings").addAll(json.createArrayNode());out.putArray("humanTodos");return out;}
        out.set("manuscriptRef",json.valueToTree(manuscript));ArrayNode findings=out.putArray("findings");
        String reviewerId=project.path("bindings").path("reviewer").path("agentId").asText("");
        if (reviewerId.isBlank() || reviewerId.equals(project.path("bindings").path("writer").path("agentId").asText(""))) {
            out.put("status", "NOT_DISPATCHED").put("reason", reviewerId.isBlank() ? "REVIEWER_NOT_BOUND" : "REVIEWER_MUST_DIFFER");
            out.putArray("tasks"); out.set("findings", findings); out.putArray("humanTodos"); readHumanTodos(scope,out.withArray("humanTodos"));
            return out;
        }
        ObjectNode reviewerBinding = (ObjectNode) project.path("bindings").path("reviewer");
        employees.getObject().validate(scope, reviewerId, reviewerBinding.path("configDigest").asText(null));
        String activeSkill = pinnedSkillId(reviewerBinding, scope.workspaceId());
        String activeSkillDigest = pinDigest(reviewerBinding, activeSkill);
        ArrayNode taskViews=out.putArray("tasks");
        List<ObjectNode> taskRows=jdbc.query("SELECT t.id,t.status,t.input_json FROM mate_bidding_task t JOIN mate_bidding_skill_package p ON p.id=t.skill_package_id AND p.workspace_id=t.workspace_id AND p.project_id=t.project_id WHERE t.workspace_id=? AND t.project_id=? AND t.agent_id=? AND t.config_digest=? AND p.skill_id=? AND p.digest=? ORDER BY t.created_at DESC",
                (rs,n)->{ObjectNode row=json.createObjectNode().put("taskId",rs.getString(1)).put("status",rs.getString(2));row.set("snapshot",object(rs.getString(3)));return row;},scope.workspaceId(),scope.projectId(),reviewerId,reviewerBinding.path("configDigest").asText(),activeSkill,activeSkillDigest);
        String taskReviewKey=null;
        for(ObjectNode row:taskRows){JsonNode input=row.path("snapshot").path("input");String target=row.path("snapshot").path("_bidding").path("targetId").asText("");
            if(!target.startsWith("review:")||!same(ref(input.path("manuscriptRef")),manuscript))continue;
            int separator=target.indexOf(':',"review:".length());if(separator<0)continue;
            String reviewKey=target.substring("review:".length(),separator);if(taskReviewKey==null)taskReviewKey=reviewKey;
            if(!taskReviewKey.equals(reviewKey))continue;
            taskViews.addObject().put("taskId",row.path("taskId").asText()).put("status",row.path("status").asText()).put("segment",target.substring(separator+1).split(":",2)[0]);
        }
        List<ObjectNode> results=jdbc.query("SELECT object_id,version,digest,payload_json,status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='review' ORDER BY created_at DESC",
                (rs,n)->{ObjectNode body=object(rs.getString(4));body.put("_objectId",rs.getString(1)).put("_status",rs.getString(5)).put("_version",rs.getLong(2)).put("_digest",rs.getString(3));return body;},scope.workspaceId(),scope.projectId());
        String currentKey=taskReviewKey;ObjectNode selectedGroup=json.createObjectNode();
        for(ObjectNode result:results){if(!"REVIEW_RESULT".equals(result.path("_status").asText()))continue;ObjectNode meta=(ObjectNode)result.path("_bidding");
            if(!same(ref(meta.path("manuscriptRef")),manuscript)||!reviewerId.equals(meta.path("reviewerId").asText())
                    || !reviewerBinding.path("configDigest").asText().equals(meta.path("reviewerConfigDigest").asText())
                    || !activeSkillDigest.equals(meta.path("skillDigest").asText()))continue;
            boolean readable = true;
            for (JsonNode material : meta.path("materialRefs")) {
                try { materials.requireReviewerReadable(scope, reviewerId, ref(material)); }
                catch (RuntimeException denied) { readable = false; break; }
            }
            if (!readable) { out.put("status", "REVIEW_ACCESS_REVOKED").put("reason", "REVIEWER_MATERIAL_ACCESS_REVOKED"); out.putArray("humanTodos"); readHumanTodos(scope,out.withArray("humanTodos")); return out; }
            if(currentKey==null)currentKey=meta.path("reviewKey").asText("");if(!currentKey.equals(meta.path("reviewKey").asText()))continue;
            selectedGroup.set(result.path("_objectId").asText(),result);
        }
        if(currentKey==null) {
            ObjectNode failure=jdbc.query("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='reviewDispatch' AND object_id=? ORDER BY version DESC LIMIT 1",
                    rs->rs.next()?object(rs.getString(1)):null,scope.workspaceId(),scope.projectId(),manuscript.digest().substring(0,48));
            if(failure!=null&&same(ref(failure.path("manuscriptRef")),manuscript)
                    &&reviewerId.equals(failure.path("reviewerId").asText())
                    &&reviewerBinding.path("configDigest").asText().equals(failure.path("reviewerConfigDigest").asText()))
                out.put("status","NOT_DISPATCHED").put("reason",failure.path("reasonCode").asText("REVIEW_DISPATCH_FAILED"));
            else out.put("status",reviewerId.isBlank()?"NOT_DISPATCHED":"NOT_STARTED");
        }
        else {
            Set<String> chapters=new LinkedHashSet<>(),requirements=new LinkedHashSet<>();boolean cross=false;
            for(JsonNode result:selectedGroup){for(JsonNode r:result.path("coverage").path("chapterRefs"))chapters.add(r.path("id").asText());for(JsonNode r:result.path("coverage").path("requirementRefs"))requirements.add(r.asText());if("cross".equals(result.path("_bidding").path("reviewSegment").asText()))cross=result.path("coverage").path("crossChapterReviewed").asBoolean(false);
                for(JsonNode finding:result.path("findings")){ObjectNode item=json.createObjectNode();BiddingTypes.Ref reviewRef=refFromRow((ObjectNode)result);item.set("reviewRef",json.valueToTree(reviewRef));item.set("finding",finding.deepCopy());BiddingTypes.Ref findingRef=findingRefFor(scope,reviewRef,finding.path("id").asText());item.set("findingRef",json.valueToTree(findingRef));String decision=latestDecision(scope,findingRef);item.put("decision",decision==null?"OPEN":decision);findings.add(item);}}
            ObjectNode manuscriptPayload=(ObjectNode)requiredManuscript(scope,manuscript).path("payload");
            Set<String> expectedChapters=new LinkedHashSet<>();for(JsonNode chapter:manuscriptPayload.path("chapters"))expectedChapters.add(chapter.path("chapterId").asText());
            ObjectNode outline=(ObjectNode)requiredBusiness(scope,ref(manuscriptPayload.path("outlineRef")),"outline").path("payload");
            ObjectNode baseline=(ObjectNode)requiredBaseline(scope,envelope(ref(manuscriptPayload.path("outlineRef")),outline)).path("payload");
            Set<String> expectedRequirements=new LinkedHashSet<>();for(JsonNode requirement:baseline.path("analyses").path("bidding-requirement-analysis").path("requirements"))if("TECHNICAL".equals(requirement.path("category").asText()))expectedRequirements.add(requirement.path("id").asText());
            boolean complete=cross&&chapters.containsAll(expectedChapters)&&requirements.containsAll(expectedRequirements);
            out.put("reviewKey",currentKey);out.put("status",complete?"REVIEWED":"IN_PROGRESS");ObjectNode coverage=out.putObject("coverage");coverage.set("chapterIds",json.valueToTree(chapters));coverage.set("requirementIds",json.valueToTree(requirements));coverage.set("expectedChapterIds",json.valueToTree(expectedChapters));coverage.set("expectedRequirementIds",json.valueToTree(expectedRequirements));coverage.put("crossChapterReviewed",cross);
        }
        ArrayNode todos=out.putArray("humanTodos");readHumanTodos(scope,todos);
        return out;
    }

    private void ensureHumanTodos(BiddingTypes.Scope scope,ObjectNode project,JsonNode requirements) {
        for(JsonNode requirement:requirements){if(!"COMMERCIAL".equals(requirement.path("category").asText()))continue;String id=requirement.path("id").asText();if(id.isBlank())continue;
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND object_id=?",Long.class,scope.workspaceId(),scope.projectId(),id);if(count!=null&&count>0)continue;
            List<BiddingTypes.Ref> sources=new ArrayList<>();JsonNode evidence=requirement.path("evidenceRefs");if(evidence.isArray())for(JsonNode e:evidence){String sourceId=e.path("sourceId").asText();long version=e.path("version").asLong(0);BiddingRepository.SourceRow source=repository.source(scope.workspaceId(),scope.projectId(),sourceId,version);if(source!=null)sources.add(new BiddingTypes.Ref("source",sourceId,version,source.digest()));}
            ObjectNode todo=json.createObjectNode().put("todoId",id).put("requirementRef",id).put("title",requirement.path("text").asText()).put("ownerId",project.path("ownerId").asText()).put("status","OPEN").put("affectsTechnical",requirement.path("affectsTechnical").asBoolean(false)).put("createdBy","bidding-review");todo.set("sourceRefs",json.valueToTree(sources));todo.set("evidenceRefs",evidence.deepCopy());
            saveBusinessRevision(scope,"HUMAN_TODO",id,todo,sources,"OPEN");
        }
    }
    private void readHumanTodos(BiddingTypes.Scope scope,ArrayNode output) {
        List<ObjectNode> rows=jdbc.query("SELECT object_id,version,digest,payload_json,status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' ORDER BY object_id,version DESC",
                (rs,n)->{ObjectNode row=object(rs.getString(4));row.put("_id",rs.getString(1)).put("_version",rs.getLong(2)).put("_digest",rs.getString(3)).put("_status",rs.getString(5));return row;},scope.workspaceId(),scope.projectId());
        Set<String> seen=new LinkedHashSet<>();for(ObjectNode row:rows){String id=row.path("_id").asText();if(!seen.add(id))continue;ObjectNode view=row.deepCopy();view.remove(List.of("_id","_version","_digest","_status","_bidding"));view.set("ref",json.valueToTree(new BiddingTypes.Ref("HUMAN_TODO",id,row.path("_version").asLong(),row.path("_digest").asText())));output.add(view);}
    }
    private String latestDecision(BiddingTypes.Scope scope,BiddingTypes.Ref finding) {
        if(finding==null)return null;
        return jdbc.query("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=? ORDER BY version DESC",rs->rs.next()?rs.getString(1):null,scope.workspaceId(),scope.projectId(),finding.id());
    }
    private BiddingTypes.Ref findingRefFor(BiddingTypes.Scope scope,BiddingTypes.Ref reviewRef,String findingId) {
        List<ObjectNode> candidates=jdbc.query("SELECT object_id,version,digest,payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='reviewFinding' ORDER BY created_at DESC",
                (rs,n)->{ObjectNode row=object(rs.getString(4));row.put("_objectId",rs.getString(1)).put("_version",rs.getLong(2)).put("_digest",rs.getString(3));return row;},scope.workspaceId(),scope.projectId());
        for(ObjectNode row:candidates)if(findingId.equals(row.path("findingId").asText())&&same(ref(row.path("reviewRef")),reviewRef))
            return new BiddingTypes.Ref("reviewFinding",row.path("_objectId").asText(),row.path("_version").asLong(),row.path("_digest").asText());
        return null;
    }
    private BiddingTypes.Ref latestManuscript(BiddingTypes.Scope scope) {
        return jdbc.query("SELECT version,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' AND object_id='manuscript' ORDER BY version DESC",rs->rs.next()?new BiddingTypes.Ref("manuscript","manuscript",rs.getLong(1),rs.getString(2)):null,scope.workspaceId(),scope.projectId());
    }

    private ObjectNode sourceEvidence(BiddingTypes.Scope scope, ObjectNode baseline) {
        ObjectNode result = json.createObjectNode(); ArrayNode blocks = result.putArray("blocks");
        BiddingTypes.Ref sourceSet = ref(baseline.path("sourceSetRef"));
        ObjectNode set = sourceSet == null ? null : repository.businessRevision(scope, sourceSet);
        if (set == null) throw BiddingAccess.error(422, "SOURCE_UNREADABLE", "Confirmed baseline source set is unavailable");
        for (BiddingTypes.Ref sourceRef : repository.businessRefs(set)) {
            if (!"source".equals(sourceRef.kind())) continue;
            BiddingRepository.SourceRow source = repository.source(scope.workspaceId(), scope.projectId(), sourceRef.id(), sourceRef.version());
            if (source == null || !sourceRef.digest().equals(source.digest()) || !"READY".equals(source.status())) {
                result.putArray("unreadableSourceRefs").add(json.valueToTree(sourceRef)); continue;
            }
            try { for (JsonNode block : json.readTree(source.blocks())) {
                ObjectNode item = blocks.addObject(); item.put("sourceId", sourceRef.id()).put("version", sourceRef.version());
                item.put("blockId", block.path("id").asText()).put("locator", block.path("locator").asText("")).put("text", block.path("text").asText());
            }} catch (Exception e) { result.putArray("unreadableSourceRefs").add(json.valueToTree(sourceRef)); }
        }
        return result;
    }

    private ObjectNode requiredManuscript(BiddingTypes.Scope scope, BiddingTypes.Ref ref) {
        if (ref == null || !"manuscript".equals(ref.kind())) throw BiddingAccess.error(404, "NOT_FOUND", "Manuscript not found");
        ObjectNode row = repository.businessRevision(scope, ref);
        if (row == null || !Set.of("DRAFT_PENDING_REVIEW", "CANDIDATE", "CONFIRMED", "NEEDS_RECONFIRMATION").contains(row.path("status").asText()))
            throw BiddingAccess.error(404, "NOT_FOUND", "Manuscript not found");
        return envelope(ref,row);
    }
    private ObjectNode requiredBusiness(BiddingTypes.Scope scope, BiddingTypes.Ref ref, String kind) {
        if (ref == null || !kind.equals(ref.kind())) throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Exact " + kind + " reference is required");
        ObjectNode row = repository.businessRevision(scope, ref);
        Set<String> statuses="HUMAN_TODO".equals(kind)?Set.of("OPEN","RESOLVED"):Set.of("CONFIRMED", "CANDIDATE", "NEEDS_RECONFIRMATION");
        if (row == null || !statuses.contains(row.path("status").asText()))
            throw BiddingAccess.error(404, "NOT_FOUND", "Review dependency not found");
        return envelope(ref,row);
    }
    private ObjectNode requiredBaseline(BiddingTypes.Scope scope, ObjectNode outline) {
        for (BiddingTypes.Ref ref : repository.businessRefs(outline)) if ("analysisBaseline".equals(ref.kind())) return requiredBusiness(scope, ref, "analysisBaseline");
        throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Outline does not reference an analysis baseline");
    }
    private ObjectNode reviewPayload(BiddingTypes.Scope scope, BiddingTypes.Ref ref) {
        ObjectNode row = repository.businessRevision(scope, ref);
        if (row == null || !REVIEW.equals(ref.kind())) throw BiddingAccess.error(404, "NOT_FOUND", "Review not found");
        return row;
    }
    private ObjectNode findFinding(JsonNode findings, String id) {
        if (id == null || id.isBlank() || !findings.isArray()) return null;
        for (JsonNode finding : findings) if (id.equals(finding.path("id").asText())) return (ObjectNode) finding.deepCopy();
        return null;
    }
    private void validateDecisionEvidence(BiddingTypes.Scope scope, ObjectNode finding, ArrayNode evidence) {
        for (JsonNode refNode : evidence) {
            BiddingTypes.Ref ref = evidenceSourceRef(scope,refNode);
            String block = refNode.path("blockId").asText(), quote = refNode.path("quote").asText();
            dependencies.validate(scope,List.of(ref));
            if (block.isBlank() || quote.isBlank() || !repository.hasEvidenceBlock(scope, ref, block, quote))
                throw BiddingAccess.error(422, "FINDING_EVIDENCE_INVALID", "Evidence does not match a stored source block");
        }
    }
    private void validateTodoEvidence(BiddingTypes.Scope scope, ObjectNode todo, ArrayNode evidence) {
        for (JsonNode node : evidence) {
            BiddingTypes.Ref ref = evidenceSourceRef(scope,node);dependencies.validate(scope,List.of(ref));
            if (!repository.hasEvidenceBlock(scope, ref, node.path("blockId").asText(), node.path("quote").asText()))
                throw BiddingAccess.error(422, "HUMAN_TODO_EVIDENCE_INVALID", "Evidence does not match a stored source block");
        }
    }
    private boolean mandatoryMissing(ObjectNode finding) {
        return "MISSING_MANDATORY_PROOF".equals(finding.path("category").asText());
    }
    private BiddingTypes.Ref saveBusinessRevision(BiddingTypes.Scope scope, String kind, String id, ObjectNode body,
            List<BiddingTypes.Ref> refs, String status) {
        long version = repository.maxRevisionVersion(scope, kind, id) + 1; String payload = canonical(body), digest = sha(payload);
        BiddingTypes.Ref ref = new BiddingTypes.Ref(kind, id, version, digest);
        repository.insertRevision(java.util.UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), kind, id,
                version, payload, write(refs), status, digest, Timestamp.from(Instant.now())); return ref;
    }
    private ObjectNode envelope(BiddingTypes.Ref ref,ObjectNode row) {
        ObjectNode value=json.createObjectNode();value.set("ref",json.valueToTree(ref));value.put("status",row.path("status").asText());
        value.set("payload",row.deepCopy());value.set("refs",row.path("refs").deepCopy());return value;
    }
    private BiddingTypes.Ref evidenceSourceRef(BiddingTypes.Scope scope,JsonNode evidence) {
        String id=evidence.path("sourceId").asText();long version=evidence.path("version").asLong(0);
        BiddingRepository.SourceRow source=repository.source(scope.workspaceId(),scope.projectId(),id,version);
        if(source==null||id.isBlank()||version<1)throw BiddingAccess.error(422,"FINDING_EVIDENCE_INVALID","Evidence source reference is invalid");
        return new BiddingTypes.Ref("source",id,version,source.digest());
    }
    private List<BiddingTypes.Ref> evidenceSourceRefs(BiddingTypes.Scope scope,JsonNode evidence) {
        List<BiddingTypes.Ref> refs=new ArrayList<>();if(evidence.isArray())for(JsonNode item:evidence)refs.add(evidenceSourceRef(scope,item));return refs;
    }
    private List<ReviewChapter> chapters(ObjectNode manuscript, Map<String, BiddingTypes.Ref> refs) {
        List<ReviewChapter> chapters = new ArrayList<>();
        for (JsonNode chapter : manuscript.path("chapters")) {
            String id = chapter.path("chapterId").asText(); BiddingTypes.Ref ref = refs.get(id);
            if (id.isBlank() || ref == null) throw BiddingAccess.error(422, "MANUSCRIPT_INCOMPLETE", "Chapter reference is missing from the manuscript snapshot");
            ObjectNode value = json.createObjectNode().put("chapterId", id).set("chapterRef", json.valueToTree(ref));
            value.set("content", chapter.deepCopy()); chapters.add(new ReviewChapter(id, ref, value));
        }
        return chapters;
    }
    private JsonNode findOutlineChapter(JsonNode chapters, String id) { for (JsonNode c : chapters) if (id.equals(c.path("id").asText())) return c; throw BiddingAccess.error(422, "REVIEW_INPUT_INVALID", "Manuscript chapter is absent from its outline"); }
    private Map<String, JsonNode> indexed(JsonNode values) { Map<String, JsonNode> result = new LinkedHashMap<>(); if (values.isArray()) for (JsonNode value : values) result.put(value.path("id").asText(), value); return result; }
    private String pinnedSkillId(ObjectNode reviewer, String workspace) {
        for (JsonNode pin : reviewer.path("skillPins")) try {
            String skill = jdbc.queryForObject("SELECT name FROM mate_skill WHERE id=? AND workspace_id=? AND deleted=0", String.class,
                    Long.valueOf(pin.path("skillId").asText()), Long.valueOf(workspace));
            if (SKILL.equals(skill)) return pin.path("skillId").asText();
        } catch (Exception ignored) { }
        throw BiddingAccess.error(422, "REVIEW_SKILL_REQUIRED", "Bind the pinned technical-review skill first");
    }
    private String pinDigest(ObjectNode reviewer, String skillId) { for (JsonNode pin : reviewer.path("skillPins")) if (skillId.equals(pin.path("skillId").asText())) return pin.path("digest").asText(); return ""; }
    private ObjectNode notReady(String code) { return json.createObjectNode().put("status", "NOT_DISPATCHED").put("reason", code); }
    private String target(String key, String segment) { return "review:" + key + ":" + segment; }
    private String materialKey(BiddingTypes.Ref ref) { return ref.kind() + ":" + ref.id() + ":" + ref.version() + ":" + ref.digest(); }
    private boolean same(BiddingTypes.Ref a, BiddingTypes.Ref b) { return Objects.equals(a, b); }
    private BiddingTypes.Ref ref(JsonNode node) { try { return node == null || node.isMissingNode() || node.isNull() ? null : json.treeToValue(node, BiddingTypes.Ref.class); } catch (Exception e) { return null; } }
    private BiddingTypes.Ref refFromRow(ObjectNode row) { return new BiddingTypes.Ref(REVIEW, row.path("_objectId").asText(), row.path("_version").asLong(), row.path("_digest").asText()); }
    private List<BiddingTypes.Ref> refs(JsonNode nodes) { return refs(nodes, null); }
    private List<BiddingTypes.Ref> refs(JsonNode nodes, String objectField) {
        List<BiddingTypes.Ref> result = new ArrayList<>(); if (!nodes.isArray()) return result;
        for (JsonNode node : nodes) result.add(ref(objectField == null ? node : node.path(objectField))); return result;
    }
    private List<BiddingTypes.Ref> append(List<BiddingTypes.Ref> a, List<BiddingTypes.Ref> b) { List<BiddingTypes.Ref> result = new ArrayList<>(a); for (BiddingTypes.Ref ref : b) if (!result.contains(ref)) result.add(ref); return result; }
    private ArrayNode copyArray(JsonNode node) { return node instanceof ArrayNode array ? array.deepCopy() : json.createArrayNode(); }
    private void only(JsonNode node, Set<String> allowed, String code) { if (!node.isObject()) throw BiddingAccess.error(422, code, "Expected object"); node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw BiddingAccess.error(422, code, "Unknown field: " + key); }); }
    private void validateCommand(BiddingTypes.Command command, String action) { if (command == null || !action.equals(command.action()) || command.payload() == null || command.operationId() == null || command.operationId().isBlank() || command.operationId().length() > 128) throw BiddingAccess.error(400, "INVALID_REQUEST", action + " is invalid"); }
    private String bounded(JsonNode value, String name, int max) { if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw BiddingAccess.error(422, "INVALID_REQUEST", name + " is required"); return value.asText().trim(); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ObjectNode object(String value) { try { JsonNode parsed = json.readTree(value); return parsed instanceof ObjectNode object ? object : json.createObjectNode(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String canonical(Object value) { try { return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String sha(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String digest(Object value) { return sha(write(value)); }
    private record ReviewChapter(String chapterId, BiddingTypes.Ref ref, ObjectNode node) { }
}
