package vip.mate.presales;

import static vip.mate.presales.PresalesDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.presales.repository.PresalesRenderTaskRepository;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesService {
    private final PresalesArtifacts artifacts;
    private final PresalesRenderTaskRepository renderTasks;
    private final ProjectAuthorityFence authorityFence;
    private final PresalesReleaseAuthority releaseAuthority;
    private final PresalesRenderExecution renderExecution;
    private final TransactionTemplate commandTransaction;
    private final PresalesProjectRepository projects;
    private final ObjectMapper json;
    private final PresalesSolutionPolicy solutionPolicy;
    private final PresalesReleaseAuthorization releaseAuthorization;
    private final PresalesBaselineApproval baselineApproval;
    private final PresalesReleaseSnapshot releaseSnapshot;
    private final PresalesRepairPolicy repairPolicy;
    private final PresalesEmployeeResultProjection employeeResultProjection;
    private final PresalesAccess access;
    private final WikiKnowledgeBaseService wiki;
    private final ObjectProvider<GraphApplicationService> graphs;
    private final ObjectProvider<PresalesEmployeeRuntime> employees;
    private final PresalesTaskAcceptance taskAcceptance;
    private final PresalesSourceAuthorization sourceAuthorization;

    public PresalesService(
            PresalesArtifactRepository artifacts,
            PresalesProjectRepository projects,
            ObjectMapper json,
            PresalesAccess access,
            WikiKnowledgeBaseService wiki,
            ObjectProvider<GraphApplicationService> graphs,
            ObjectProvider<StatementApplicationService> statements,
            SemanticProperties semantic,
            ObjectProvider<vip.mate.semantic.query.SemanticQueryService> queries,
            PresalesArtifactRenderer renderer,
            ObjectProvider<PresalesEmployeeRuntime> employees,
            ProjectAuthorityFence authorityFence,
            PresalesSourceAuthorization sourceAuthorization,
            PresalesRenderTaskRepository renderTasks,
            PlatformTransactionManager transactions) {
        this.sourceAuthorization = sourceAuthorization;
        this.renderTasks = renderTasks;
        this.authorityFence = authorityFence;
        this.commandTransaction = new TransactionTemplate(transactions);
        this.employees = employees;
        this.taskAcceptance = new PresalesTaskAcceptance(employees, authorityFence);
        this.artifacts = new PresalesArtifacts(artifacts, renderer);
        this.projects = projects;
        this.json = json;
        this.solutionPolicy = new PresalesSolutionPolicy(json);
        this.releaseAuthorization =
                new PresalesReleaseAuthorization(
                        semantic, graphs, statements, queries, sourceAuthorization, solutionPolicy);
        this.releaseAuthority =
                new PresalesReleaseAuthority(
                        authorityFence,
                        sourceAuthorization,
                        releaseAuthorization,
                        access,
                        employees);
        this.renderExecution =
                new PresalesRenderExecution(renderTasks, this.artifacts, json, transactions);
        this.baselineApproval =
                new PresalesBaselineApproval(
                        json, graphs, statements, queries, sourceAuthorization);
        this.releaseSnapshot = new PresalesReleaseSnapshot(json);
        this.repairPolicy = new PresalesRepairPolicy(json);
        this.employeeResultProjection = new PresalesEmployeeResultProjection(json, solutionPolicy);
        this.access = access;
        this.wiki = wiki;
        this.graphs = graphs;
    }

    public ObjectNode get(String scope, String id) {
        access.require(scope, "viewer");
        var p = load(scope, id, false);
        authorizeMaterials(scope, p);
        authorizeReleaseSources(scope, p);
        return p;
    }

    /** Returns only safe metadata and opaque binding handles after source access is revoked. */
    public ObjectNode repairContext(String scope, String id) {
        access.require(scope, "member");
        return repairPolicy.restrictedView(load(scope, id, false));
    }

    /** Read a project using the durable actor bound to a project execution. */
    public ObjectNode getForExecution(String scope, String id, String actorId) {
        access.requireActor(scope, actorId, "member");
        var p = load(scope, id, false);
        authorizeMaterials(scope, p);
        authorizeReleaseSources(scope, p);
        return p;
    }

    @Transactional
    public ObjectNode create(String scope, Create r) {
        String actor = access.require(scope, "member");
        if (r == null || r.expectedVersion() == null || r.expectedVersion() != 0)
            throw bad("expectedVersion must be 0");
        operation(r.operationId());
        String encodedRequest = encode(r);
        lockOperationActor(scope, actor, "member");
        var replay = replay(scope, actor, r.operationId(), encodedRequest);
        if (replay != null) return replay;
        rejectRenderOperation(scope, actor, r.operationId());
        text(r.name(), "name", 300);
        text(r.customer(), "customer", 300);
        ObjectNode p = json.createObjectNode();
        p.put("storageVersion", 2);
        p.put("id", id())
                .put("workspaceId", scope)
                .put("version", 1)
                .put("name", r.name())
                .put("customer", r.customer())
                .put("ownerId", access.owner(scope, r.ownerId(), actor))
                .put("industry", Objects.toString(r.industry(), ""))
                .put("goal", Objects.toString(r.goal(), ""))
                .put("status", "ACTIVE")
                .put("stage", "DISCOVERY")
                .put("createdBy", actor);
        if (r.agentId() != null && !r.agentId().isBlank()) bindEmployee(scope, p, r.agentId());
        for (String key :
                List.of(
                        "materials",
                        "requirements",
                        "clarifications",
                        "baselines",
                        "fitGaps",
                        "cases",
                        "solutions",
                        "reviews",
                        "reviewDrafts",
                        "releases",
                        "tasks")) p.putArray(key);
        String body = PresalesListingProjectionV1.storageJson(encode(p));
        projects.insert(
                new PresalesProjectRepository.ProjectRow(
                        p.path("id").asText(),
                        scope,
                        1,
                        r.name(),
                        "ACTIVE",
                        body,
                        PresalesListingProjectionV1.fromBody(body, json)));
        record(p, actor, "CREATE");
        receipt(scope, actor, r.operationId(), PresalesRequestHashV2.digest(encodedRequest), p);
        return p;
    }

    public ObjectNode command(String scope, String projectId, Command r) {
        if (r != null && r.parsedAction().kind() == CommandKind.CREATE_RELEASE) {
            try {
                return renderExecution.execute(
                        scope,
                        projectId,
                        r,
                        () -> prepareRelease(scope, projectId, r),
                        (prepared, files) -> acceptRelease(scope, projectId, r, prepared, files));
            } catch (PresalesRejected rejection) {
                throw legacyRejection(rejection);
            } catch (PresalesSourceAuthorization.Denied denied) {
                throw new SemanticApiException(denied.status(), denied.code(), denied.getMessage());
            }
        }
        return commandTransaction.execute(status -> applyCommand(scope, projectId, r, false));
    }

    /** Server-only typed creation; public commands cannot manufacture execution authority. */
    ObjectNode queueEmployeeTask(
            String scope,
            String projectId,
            long expectedVersion,
            String operationId,
            PresalesQueuedTask queued,
            PresalesTaskPackage original) {
        if (queued == null
                || original == null
                || queued.status() != PresalesQueuedTask.Status.RUNNING
                || !original.skill().equals(queued.skill())
                || !original.skillName().equals(queued.skillName())
                || !original.skillDigest().equals(queued.skillDigest())
                || !original.presentationDigest().equals(queued.presentationDigest()))
            throw conflict("TASK_SCOPE_CHANGED", "Invalid queued package");
        return commandTransaction.execute(
                status -> {
                    String actor = access.require(scope, "member");
                    ObjectNode task = json.valueToTree(queued);
                    task.put("packageDigest", original.digest());
                    var request = new Command(expectedVersion, operationId, "SAVE_AI_TASK", task);
                    ObjectNode current = load(scope, projectId, true);
                    if (PresalesTaskDependencies.newProject(current))
                        PresalesTaskDependencies.requireCurrent(current, queued.contextSnapshot());
                    ObjectNode result = applyCommand(scope, projectId, request, false, true);
                    ObjectNode stored = null;
                    for (var item : result.path("tasks"))
                        if (queued.runId().equals(item.path("runId").asText()))
                            stored = (ObjectNode) item;
                    if (stored == null) throw conflict("TASK_SCOPE_CHANGED", "Queued task missing");
                    var existing =
                            projects.findTaskPackage(
                                    scope, projectId, stored.path("id").asText(), queued.runId());
                    if (existing.isEmpty())
                        projects.insertTaskPackage(
                                new PresalesProjectRepository.TaskPackageRow(
                                        scope,
                                        projectId,
                                        stored.path("id").asText(),
                                        queued.runId(),
                                        actor,
                                        queued.agentId(),
                                        original.digest(),
                                        encode(original)));
                    else if (!existing.get().packageDigest().equals(original.digest()))
                        throw conflict("TASK_SCOPE_CHANGED", "Queued package changed");
                    return result;
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public ObjectNode saveEmployeeTask(String scope, String projectId, Command r) {
        if (r.parsedAction().kind() != CommandKind.SAVE_AI_TASK)
            throw bad("Employee task required");
        return applyCommand(scope, projectId, r, true);
    }

    private record PreparedCommand(
            String actor,
            String encodedRequest,
            ObjectNode project,
            ObjectNode value,
            long nextVersion,
            ObjectNode replay) {}

    private PreparedCommand prepareCommand(
            String scope, String projectId, Command r, boolean employeeResult) {
        if (r == null) throw bad("Command required");
        var parsedAction = r.parsedAction();
        String role =
                Set.of(
                                        CommandKind.APPROVE_BASELINE,
                                        CommandKind.APPROVE_RELEASE,
                                        CommandKind.PUBLISH_RELEASE)
                                .contains(parsedAction.kind())
                        ? "admin"
                        : "member";
        String actor = access.require(scope, role);
        operation(r.operationId());
        if (r.expectedVersion() == null) throw bad("expectedVersion required");
        String encodedRequest = encode(List.of(projectId, r));
        ObjectNode p = load(scope, projectId, true);
        boolean repair = repairPolicy.allows(r);
        if (!repair) authorizeMaterials(scope, p);
        lockOperationActor(scope, actor, role);
        var replay = replay(scope, actor, r.operationId(), encodedRequest);
        if (replay != null) {
            return new PreparedCommand(
                    actor, encodedRequest, p, null, 0, commandResponse(scope, p, replay));
        }
        if (parsedAction.kind() != CommandKind.CREATE_RELEASE)
            rejectRenderOperation(scope, actor, r.operationId());
        else
            renderTasks
                    .findOperation(scope, actor, r.operationId(), true)
                    .ifPresent(
                            task -> {
                                if (!task.requestHash()
                                        .equals(PresalesRequestHashV2.digest(encodedRequest)))
                                    throw conflict(
                                            "OPERATION_CONFLICT",
                                            "Operation id reused with different input");
                            });
        if (!PresalesProjectRevision.matchesRevision(p.path("version"), r.expectedVersion()))
            throw conflict("VERSION_CONFLICT", "Project has changed; reload before saving");
        if ("ARCHIVED".equals(p.path("status").asText()))
            throw conflict("PROJECT_ARCHIVED", "Archived projects are read only");
        ObjectNode value = r.payload() == null ? json.createObjectNode() : r.payload().deepCopy();
        long nextVersion;
        try {
            if (!employeeResult) PresalesCommandPayload.validate(parsedAction.kind(), value);
            nextVersion = PresalesProjectRevision.nextRevision(r.expectedVersion());
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
        return new PreparedCommand(actor, encodedRequest, p, value, nextVersion, null);
    }

    private ObjectNode applyCommand(
            String scope, String projectId, Command r, boolean employeeResult) {
        return applyCommand(scope, projectId, r, employeeResult, false);
    }

    private ObjectNode applyCommand(
            String scope, String projectId, Command r, boolean employeeResult, boolean queued) {
        var prepared = prepareCommand(scope, projectId, r, employeeResult);
        if (prepared.replay() != null) return prepared.replay();
        String actor = prepared.actor();
        ObjectNode p = prepared.project();
        ObjectNode value = prepared.value();
        var parsedAction = r.parsedAction();
        switch (parsedAction.kind()) {
            case UPDATE_PROJECT -> {
                if (value.has("agentId")) {
                    var currentEmployee = p.path("agentId");
                    boolean unassigned =
                            currentEmployee.isMissingNode()
                                    || currentEmployee.isNull()
                                    || (currentEmployee.isTextual()
                                            && currentEmployee.textValue().isEmpty());
                    // The project form includes an empty employee even for metadata-only edits.
                    if (!unassigned || !value.path("agentId").asText().isEmpty())
                        bindEmployee(scope, p, value.path("agentId").asText());
                }
                if (value.has("ownerId"))
                    p.put(
                            "ownerId",
                            access.owner(
                                    scope,
                                    value.path("ownerId").asText(),
                                    p.path("ownerId").asText()));
                for (String key : List.of("name", "customer", "industry", "goal"))
                    if (value.has(key)) {
                        text(value.path(key).asText(), key, key.equals("goal") ? 10000 : 300);
                        p.set(key, value.get(key));
                    }
            }
            case ARCHIVE -> p.put("status", "ARCHIVED");
            case BIND_MATERIAL -> bind(scope, p, value, actor);
            case SAVE_REQUIREMENT -> {
                text(value.path("title").asText(), "title", 1000);
                enumValue(value, "scope", Set.of("IN", "OUT", "UNKNOWN"), "UNKNOWN");
                enumValue(value, "priority", Set.of("HIGH", "MEDIUM", "LOW"), "MEDIUM");
                value.put("customerConfirmationStatus", "UNCONFIRMED");
                saveItem(p, "requirements", value, actor, false);
            }
            case SAVE_CLARIFICATION -> {
                try {
                    var decision =
                            PresalesClarificationSave.decide(
                                    PresalesClarificationCodec.decode(value),
                                    actor,
                                    id -> find(p, "requirements", id),
                                    id -> access.owner(scope, id, actor));
                    PresalesClarificationCodec.apply(value, decision);
                } catch (PresalesRejected rejection) {
                    throw legacyRejection(rejection);
                }
                saveItem(p, "clarifications", value, actor, false);
            }
            case UNBIND_MATERIAL -> {
                String materialId = value.path("id").asText();
                find(p, "materials", materialId);
                var items = p.withArray("materials");
                for (int i = 0; i < items.size(); i++)
                    if (materialId.equals(items.get(i).path("id").asText())) {
                        items.remove(i);
                        break;
                    }
            }
            case CANCEL_AI_TASK -> {
                var task = find(p, "tasks", value.path("taskId").asText());
                if (!"RUNNING".equals(task.path("status").asText()))
                    throw conflict("TASK_STATE", "Task is not running");
                task.put("status", "CANCELLED");
            }
            case SAVE_AI_TASK -> {
                try {
                    taskAcceptance.prepare(scope, actor, p, value, employeeResult, queued);
                } catch (PresalesRejected rejection) {
                    throw legacyRejection(rejection);
                }
                saveItem(p, "tasks", value, actor, false);
                if (employeeResult && "SUCCEEDED".equals(value.path("status").asText())) {
                    projectEmployeeResult(p, value, actor);
                }
            }
            case SAVE_CONTEXT -> {
                value.put("authority", "UNTRUSTED_DRAFT");
                saveItem(p, "contextCards", value, actor, false);
            }
            case SAVE_REVIEW -> {
                try {
                    var issues =
                            PresalesReviewSave.decide(
                                    PresalesReviewCodec.decode(value),
                                    id -> PresalesProjectItems.find(p, "solutions", id));
                    PresalesReviewCodec.apply(value, issues);
                } catch (PresalesRejected rejection) {
                    throw legacyRejection(rejection);
                }
                saveItem(p, "reviews", value, actor, true);
            }
            case CREATE_RELEASE ->
                    throw new IllegalStateException("Release requires durable render execution");
            case APPROVE_RELEASE -> {
                var release = find(p, "releases", value.path("releaseId").asText());
                if (!"PENDING".equals(release.path("status").asText()))
                    throw conflict("RELEASE_STATE", "Release is not pending");
                releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
                text(value.path("reason").asText(), "reason", 10000);
                verifyArtifacts(p.path("id").asText(), release);
                release.put("status", "APPROVED")
                        .put("approvedBy", actor)
                        .put("approvalReason", value.path("reason").asText());
            }
            case PUBLISH_RELEASE -> {
                var release = find(p, "releases", value.path("releaseId").asText());
                if (!"APPROVED".equals(release.path("status").asText()))
                    throw conflict("RELEASE_STATE", "Exact release must be approved");
                releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
                verifyArtifacts(p.path("id").asText(), release);
                release.put("status", "PUBLISHED")
                        .put("publishedBy", actor)
                        .put("publishedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
                if (release.path("handoffSnapshot").path("release").isObject()) {
                    ObjectNode frozenRelease =
                            (ObjectNode) release.path("handoffSnapshot").path("release");
                    frozenRelease
                            .put("status", "PUBLISHED")
                            .put("publishedBy", actor)
                            .put("publishedAt", release.path("publishedAt").asText());
                    ObjectNode snapshot = (ObjectNode) release.path("handoffSnapshot");
                    snapshot.set("clarifications", p.path("clarifications").deepCopy());
                    ArrayNode refs = json.createArrayNode();
                    for (var ref : snapshot.path("sourceRefs")) refs.add(ref.deepCopy());
                    for (var ref : publicationClarificationRefs(p)) refs.add(ref.deepCopy());
                    snapshot.set("sourceRefs", refs);
                }
            }
            case APPROVE_BASELINE -> baseline(scope, p, value, actor);
            case SAVE_FIT_GAP -> {
                find(p, "requirements", value.path("requirementId").asText());
                enumValue(
                        value,
                        "status",
                        Set.of("FIT", "CONFIG", "EXTEND", "PARTNER", "GAP", "UNKNOWN"),
                        "UNKNOWN");
                if (!"UNKNOWN".equals(value.path("status").asText())) {
                    if (!value.path("evidenceIds").isArray() || value.path("evidenceIds").isEmpty())
                        throw bad("Fit assessment requires evidence; otherwise use UNKNOWN");
                    text(value.path("reason").asText(), "reason", 10000);
                    text(value.path("productVersion").asText(), "productVersion", 300);
                    validateEvidence(scope, p, value);
                }
                saveItem(p, "fitGaps", value, actor, true);
            }
            case SAVE_SOLUTION -> saveSolutionDraft(p, value, actor, false);
            default -> throw bad("Unsupported command: " + parsedAction.raw());
        }
        return persistCommand(scope, projectId, r, prepared);
    }

    private ObjectNode persistCommand(
            String scope, String projectId, Command r, PreparedCommand prepared) {
        ObjectNode p = prepared.project();
        String actor = prepared.actor();
        p.put("stage", PresalesProjectListing.stage(p));
        p.set("version", PresalesProjectRevision.number(prepared.nextVersion()));
        p.put("updatedBy", actor).put("updatedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        String body = PresalesListingProjectionV1.storageJson(encode(p));
        if (projects.update(
                        new PresalesProjectRepository.ProjectRow(
                                projectId,
                                scope,
                                prepared.nextVersion(),
                                p.path("name").asText(),
                                p.path("status").asText(),
                                body,
                                PresalesListingProjectionV1.fromBody(body, json)),
                        r.expectedVersion())
                != 1) throw conflict("VERSION_CONFLICT", "Concurrent update");
        record(p, actor, r.parsedAction().raw());
        receipt(
                scope,
                actor,
                r.operationId(),
                PresalesRequestHashV2.digest(prepared.encodedRequest()),
                p);
        return commandResponse(scope, p, p);
    }

    private ObjectNode commandResponse(String scope, ObjectNode current, ObjectNode response) {
        ObjectNode view = response.deepCopy();
        view.put("agentId", current.path("agentId").asText());
        try {
            authorizeMaterials(scope, view);
            view.set("materials", current.path("materials").deepCopy());
            authorizeReleaseSources(scope, view);
            return response;
        } catch (SemanticApiException unavailable) {
            if (unavailable.status() != 403) throw unavailable;
            // Repair remains possible without returning source-rich historical data.
            return repairPolicy.restrictedView(response);
        }
    }

    private void projectEmployeeResult(ObjectNode p, ObjectNode task, String actor) {
        try {
            employeeResultProjection.apply(p, task, actor);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private void saveSolutionDraft(
            ObjectNode p, ObjectNode value, String actor, boolean employeeResult) {
        try {
            solutionPolicy.prepare(p, value, employeeResult);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
        saveItem(p, "solutions", value, actor, true);
    }

    private void bindEmployee(String scope, ObjectNode p, String agentId) {
        if (employees.getIfAvailable() == null)
            throw PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE");
        var employee = employees.getObject().require(scope, agentId);
        p.put("agentId", employee.getId().toString()).put("agentName", employee.getName());
    }

    private void bind(String scope, ObjectNode p, ObjectNode v, String actor) {
        String kb = v.path("kbId").asText();
        text(kb, "kbId", 64);
        var row = wiki.getById(parseId(kb));
        if (row == null
                || row.getWorkspaceId() == null
                || !scope.equals(row.getWorkspaceId().toString())
                || (row.getDeleted() != null && row.getDeleted() != 0))
            throw new SemanticApiException(
                    404, "MATERIAL_UNAVAILABLE", "Knowledge base unavailable");
        String graph = v.path("graphId").asText();
        if (!graph.isBlank()) {
            requireSemantic();
            var g = graphs.getObject().requireGraph(scope, graph, true);
            if (!kb.equals(g.getKbId().toString()))
                throw bad("Graph belongs to another knowledge base");
            v.put("ontologyRevisionId", g.getOntologyRevisionId());
        }
        authorizeEmployeeKb(scope, p, kb);
        enumValue(v, "role", Set.of("PROJECT", "PRODUCT", "CASE"), "PROJECT");
        saveItem(p, "materials", v, actor, false);
    }

    private void baseline(String scope, ObjectNode p, ObjectNode v, String actor) {
        requireSemantic();
        try {
            checkSourceAccess(() -> baselineApproval.prepare(scope, p, v, actor));
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
        saveItem(p, "baselines", v, actor, true);
    }

    private void validateEvidence(String scope, ObjectNode p, ObjectNode v) {
        try {
            releaseAuthorization.validateEvidence(scope, p, v);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    public ObjectNode handoff(String scope, String projectId) {
        var p = get(scope, projectId);
        ObjectNode release = null;
        for (var r : p.withArray("releases"))
            if ("PUBLISHED".equals(r.path("status").asText())) release = (ObjectNode) r;
        if (release == null)
            throw conflict(
                    "PUBLISHED_RELEASE_REQUIRED", "Publish a reviewed release before handoff");
        return frozenHandoff(projectId, release.path("id").asText(), release);
    }

    /** Returns the immutable handoff captured when this exact release was published. */
    public ObjectNode handoff(String scope, String projectId, String releaseId) {
        var p = get(scope, projectId);
        ObjectNode release = null;
        for (var item : p.withArray("releases"))
            if (releaseId.equals(item.path("id").asText())) release = (ObjectNode) item;
        if (release == null) throw new SemanticApiException(404, "NOT_FOUND", "发布版本不存在");
        if (!"PUBLISHED".equals(release.path("status").asText()))
            throw new SemanticApiException(409, "PUBLISHED_RELEASE_REQUIRED", "请选择已发布版本");
        return frozenHandoff(projectId, releaseId, release);
    }

    /** Translates the application's HTTP errors at the public handoff port boundary. */
    ObjectNode handoffForDelivery(String scope, String projectId, String releaseId) {
        try {
            return handoff(scope, projectId, releaseId);
        } catch (SemanticApiException unavailable) {
            throw new vip.mate.presales.api.PresalesHandoffReader.Unavailable(
                    unavailable.status(), unavailable.code());
        }
    }

    private ObjectNode frozenHandoff(String projectId, String releaseId, ObjectNode release) {
        try {
            return artifacts.handoff(projectId, releaseId, release);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private String releaseGate(String scope, ObjectNode p, ObjectNode solution) {
        try {
            return releaseAuthorization.reviewId(scope, p, solution);
        } catch (PresalesSourceAuthorization.Denied denied) {
            throw new SemanticApiException(denied.status(), denied.code(), denied.getMessage());
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private PresalesRenderExecution.Prepared prepareRelease(
            String scope, String projectId, Command r) {
        var prepared = prepareCommand(scope, projectId, r, false);
        String hash = PresalesRequestHashV2.digest(prepared.encodedRequest());
        if (prepared.replay() != null)
            return new PresalesRenderExecution.Prepared(
                    prepared.actor(), hash, null, prepared.replay());
        ObjectNode p = prepared.project();
        ObjectNode value = prepared.value();
        var solution = find(p, "solutions", value.path("solutionId").asText());
        releaseAuthorization.requireEnabled();
        var sources = releaseAuthority.lockAndCapture(scope, prepared.actor(), p, solution);
        value.put("reviewId", releaseGate(scope, p, solution));
        ObjectNode input = json.createObjectNode();
        input.set("project", p);
        input.set("candidate", value);
        input.set("sources", sources);
        input.set("artifacts", artifacts.capture(projectId, solution));
        return new PresalesRenderExecution.Prepared(prepared.actor(), hash, input, null);
    }

    private ObjectNode acceptRelease(
            String scope,
            String projectId,
            Command r,
            PresalesRenderExecution.Prepared prepared,
            List<PresalesArtifacts.RenderedFile> files) {
        ObjectNode p = ((ObjectNode) prepared.input().path("project")).deepCopy();
        ObjectNode value = ((ObjectNode) prepared.input().path("candidate")).deepCopy();
        createRelease(scope, p, value, prepared.actor(), files);
        return persistCommand(
                scope,
                projectId,
                r,
                new PreparedCommand(
                        prepared.actor(),
                        encode(List.of(projectId, r)),
                        p,
                        value,
                        PresalesProjectRevision.nextRevision(r.expectedVersion()),
                        null));
    }

    private void createRelease(
            String scope,
            ObjectNode p,
            ObjectNode v,
            String actor,
            List<PresalesArtifacts.RenderedFile> files) {
        var solution = find(p, "solutions", v.path("solutionId").asText());
        String releaseId = id();
        artifacts.storeCandidate(p.path("id").asText(), releaseId, files, v);
        v.put("status", "PENDING")
                .put("baselineId", solution.path("baselineId").asText())
                .put("templateVersion", PresalesArtifactRenderer.TEMPLATE_VERSION);
        saveItem(p, "releases", v, actor, true);
        artifacts.reassignCandidate(p.path("id").asText(), releaseId, v.path("id").asText());
        try {
            v.set("handoffSnapshot", releaseSnapshot.candidate(scope, p, solution, v));
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    /** Serializes the shared operation namespace across projects and command kinds. */
    private void lockOperationActor(String scope, String actor, String role) {
        if (!authorityFence.lockForCommand(
                scope, List.of(actor), null, List.of(), List.of(), List.of()))
            throw conflict("EXECUTION_AUTHORITY_CHANGED", "Operation authority changed");
        access.requireLockedActor(scope, actor, role);
    }

    private void rejectRenderOperation(String scope, String actor, String operation) {
        if (renderTasks.findOperation(scope, actor, operation, true).isPresent())
            throw conflict(
                    "OPERATION_CONFLICT", "Operation id already belongs to a render request");
    }

    private ArrayNode publicationClarificationRefs(ObjectNode project) {
        ArrayNode refs = json.createArrayNode();
        for (var clarification : project.withArray("clarifications"))
            if (clarification.path("sourceRefs").isArray())
                for (var ref : clarification.path("sourceRefs")) refs.add(ref.deepCopy());
        return refs;
    }

    private void authorizeMaterials(String scope, ObjectNode project) {
        checkSourceAccess(() -> sourceAuthorization.authorizeMaterials(scope, project));
    }

    private void authorizeReleaseSources(String scope, ObjectNode project) {
        checkSourceAccess(() -> sourceAuthorization.authorizeReleaseSources(scope, project));
    }

    private void authorizeEmployeeKb(String scope, ObjectNode project, String kbId) {
        checkSourceAccess(() -> sourceAuthorization.authorizeEmployeeKb(scope, project, kbId));
    }

    private static void checkSourceAccess(Runnable check) {
        try {
            check.run();
        } catch (PresalesSourceAuthorization.Denied denied) {
            throw new SemanticApiException(denied.status(), denied.code(), denied.getMessage());
        }
    }

    private void verifyArtifacts(String projectId, ObjectNode release) {
        try {
            artifacts.verifyCandidate(projectId, release);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    public byte[] preview(String scope, String projectId, String releaseId, String filename) {
        access.require(scope, "admin");
        var p = get(scope, projectId);
        var release = find(p, "releases", releaseId);
        if (!PresalesSourceAuthorization.hasFrozenSourceSnapshot(scope, p, release))
            releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
        try {
            return artifacts.preview(projectId, release, filename);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    public byte[] draftArtifact(
            String scope, String projectId, String solutionId, String filename) {
        var p = get(scope, projectId);
        var solution = find(p, "solutions", solutionId);
        try {
            return artifacts.draft(projectId, solutionId, solution, filename);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    public byte[] artifact(String scope, String projectId, String releaseId, String filename) {
        var p = get(scope, projectId);
        var release = find(p, "releases", releaseId);
        if (!"PUBLISHED".equals(release.path("status").asText()))
            throw new SemanticApiException(
                    403, "RELEASE_NOT_PUBLISHED", "Only published artifacts can be downloaded");
        if (!PresalesSourceAuthorization.hasFrozenSourceSnapshot(scope, p, release))
            releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
        try {
            return artifacts.releaseFile(projectId, release, filename);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private void requireSemantic() {
        try {
            releaseAuthorization.requireEnabled();
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    public ObjectNode find(ObjectNode p, String collection, String id) {
        try {
            return PresalesProjectItems.find(p, collection, id);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private ObjectNode load(String scope, String id, boolean lock) {
        return projects.findBody(scope, id, lock)
                .map(this::decode)
                .orElseThrow(() -> new SemanticApiException(404, "NOT_FOUND", "Project not found"));
    }

    private ObjectNode replay(String scope, String actor, String operation, String encodedRequest) {
        var row = projects.findReceipt(scope, actor, operation);
        if (row.isEmpty()) return null;
        String storedHash = row.get().requestHash();
        PresalesRequestHashV2.Version version;
        try {
            version = PresalesRequestHashV2.versionOf(storedHash);
        } catch (IllegalArgumentException unsupported) {
            throw conflict(
                    "OPERATION_REPLAY_UNVERIFIABLE", "Stored operation hash cannot be verified");
        }
        String requestHash =
                switch (version) {
                    case LEGACY -> StatementApplicationService.hash(encodedRequest);
                    case V2 -> PresalesRequestHashV2.digest(encodedRequest);
                };
        if (!requestHash.equals(storedHash))
            throw conflict("OPERATION_CONFLICT", "Operation id reused with different input");
        if (version == PresalesRequestHashV2.Version.LEGACY
                && PresalesRequestHashV2.hasLegacyEncodingAmbiguity(encodedRequest))
            throw conflict(
                    "OPERATION_REPLAY_UNVERIFIABLE", "Legacy operation input cannot be verified");
        return decode(row.get().responseJson());
    }

    private void receipt(String scope, String actor, String operation, String hash, ObjectNode p) {
        projects.insertReceipt(
                scope, actor, operation, hash, PresalesListingProjectionV1.storageJson(encode(p)));
    }

    private void record(ObjectNode p, String actor, String action) {
        projects.insertRevision(
                p.path("id").asText(),
                p.path("version").longValue(),
                actor,
                action,
                PresalesListingProjectionV1.storageJson(encode(p)),
                LocalDateTime.now(ZoneOffset.UTC));
    }

    private String encode(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode decode(String s) {
        try {
            return (ObjectNode) json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            throw bad("Invalid ID");
        }
    }

    private static void operation(String s) {
        text(s, "operationId", 128);
    }

    private void saveItem(
            ObjectNode p, String collection, ObjectNode v, String actor, boolean immutable) {
        try {
            PresalesProjectItems.saveItem(p, collection, v, actor, immutable);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private static void text(String s, String label, int max) {
        try {
            PresalesProjectItems.text(s, label, max);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private static void enumValue(ObjectNode v, String key, Set<String> allowed, String fallback) {
        try {
            PresalesProjectItems.enumValue(v, key, allowed, fallback);
        } catch (PresalesRejected rejection) {
            throw legacyRejection(rejection);
        }
    }

    private static SemanticApiException legacyRejection(PresalesRejected rejection) {
        return new SemanticApiException(
                rejection.status(), rejection.code(), rejection.getMessage());
    }

    private static SemanticApiException bad(String message) {
        return new SemanticApiException(400, "INVALID_REQUEST", message);
    }

    private static SemanticApiException conflict(String code, String message) {
        return new SemanticApiException(409, code, message);
    }
}
