package vip.mate.presales;

import static vip.mate.presales.PresalesDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final PresalesAccess access;
  private final WikiKnowledgeBaseService wiki;
  private final ObjectProvider<GraphApplicationService> graphs;
  private final ObjectProvider<StatementApplicationService> statements;
  private final SemanticProperties semantic;
  private final ObjectProvider<vip.mate.semantic.query.SemanticQueryService> queries;
  private final PresalesArtifactRenderer renderer;
  private final ObjectProvider<PresalesEmployeeRuntime> employees;

  public PresalesService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      PresalesAccess access,
      WikiKnowledgeBaseService wiki,
      ObjectProvider<GraphApplicationService> graphs,
      ObjectProvider<StatementApplicationService> statements,
      SemanticProperties semantic,
      ObjectProvider<vip.mate.semantic.query.SemanticQueryService> queries,
      PresalesArtifactRenderer renderer, ObjectProvider<PresalesEmployeeRuntime> employees) {
    this.employees = employees;
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
    this.wiki = wiki;
    this.graphs = graphs;
    this.statements = statements;
    this.semantic = semantic;
    this.queries = queries;
    this.renderer = renderer;
  }

  public ArrayNode sources(String scope) {
    access.require(scope, "viewer");
    ArrayNode out = json.createArrayNode();
    for (var kb : wiki.listByWorkspace(Long.valueOf(scope)).stream().limit(200).toList()) {
      var item = out.addObject().put("kbId", kb.getId().toString()).put("name", kb.getName());
      if (semantic.isEnabled() && graphs.getIfAvailable() != null)
        try {
          var binding = graphs.getObject().get(scope, kb.getId().toString());
          if (binding.enabled())
            item.put("graphId", binding.graphId())
                .put("ontologyRevisionId", binding.ontologyRevisionId());
        } catch (SemanticApiException e) {
          if (e.status() != 404) throw e;
        }
    }
    return out;
  }

  public ArrayNode trustedStatements(String scope, String projectId) {
    var p = get(scope, projectId);
    requireSemantic();
    ArrayNode out = json.createArrayNode();
    Set<String> seen = new HashSet<>();
    for (var material : p.withArray("materials")) {
      String graph = material.path("graphId").asText();
      if (graph.isBlank() || !seen.add(graph)) continue;
      for (var fact : statements.getObject().trusted(scope, graph)) {
        var item =
            out.addObject()
                .put("id", fact.id())
                .put("revision", fact.revision())
                .put("graphId", graph)
                .put("ontologyRevisionId", fact.ontologyRevisionId())
                .put("label", fact.assertion().functionalSyntax());
        item.set("evidenceIds", json.valueToTree(fact.evidenceIds()));
        if (out.size() >= 500) return out;
      }
    }
    return out;
  }

  public Map<String, Object> capabilities(String scope) {
    access.require(scope, "viewer");
    return Map.of(
        "enabled",
        true,
        "semanticEnabled",
        semantic.isEnabled(),
        "canWrite",
        access.allowed(scope, "member"),
        "canApprove",
        access.allowed(scope, "admin"));
  }

  public Page list(
      String scope,
      String query,
      String status,
      String ownerId,
      String stageFilter,
      int page,
      int size) {
    access.require(scope, "viewer");
    if (page < 1 || size < 1 || size > 100) throw bad("Invalid pagination");
    String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
    var all =
        jdbc
            .query(
                "SELECT body_json FROM mate_presales_project WHERE workspace_id=? ORDER BY name,id",
                (r, n) -> decode(r.getString(1)),
                scope)
            .stream()
            .filter(
                p ->
                    p.path("name").asText().toLowerCase(Locale.ROOT).contains(q)
                        || p.path("customer").asText().toLowerCase(Locale.ROOT).contains(q))
            .filter(
                p -> status == null || status.isBlank() || status.equals(p.path("status").asText()))
            .filter(
                p ->
                    ownerId == null
                        || ownerId.isBlank()
                        || ownerId.equals(p.path("ownerId").asText()))
            .filter(
                p -> stageFilter == null || stageFilter.isBlank() || stageFilter.equals(stage(p)))
            .map(this::summary)
            .toList();
    return new Page(
        all.stream().skip((long) (page - 1) * size).limit(size).toList(), all.size(), page, size);
  }

  private ObjectNode summary(ObjectNode p) {
    ObjectNode summary = p.deepCopy();
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
            "tasks",
            "contextCards")) summary.remove(key);
    summary
        .put("stage", stage(p))
        .put(
            "openClarificationCount",
            java.util.stream.StreamSupport.stream(
                    p.withArray("clarifications").spliterator(), false)
                .filter(c -> !"ANSWERED".equals(c.path("status").asText()))
                .count())
        .put(
            "latestSolutionVersion",
            p.withArray("solutions").isEmpty()
                ? 0
                : p.withArray("solutions")
                    .get(p.withArray("solutions").size() - 1)
                    .path("version")
                    .asInt());
    return summary;
  }

  private String stage(ObjectNode p) {
    if ("ARCHIVED".equals(p.path("status").asText())) return "ARCHIVED";
    if (!p.withArray("releases").isEmpty()) return "RELEASE";
    if (!p.withArray("solutions").isEmpty()) return "SOLUTION";
    if (!p.withArray("baselines").isEmpty()) return "BASELINED";
    if (!p.withArray("requirements").isEmpty()) return "REQUIREMENTS";
    return "DISCOVERY";
  }

  public ObjectNode get(String scope, String id) {
    access.require(scope, "viewer");
    var p = load(scope, id, false);
    authorizeMaterials(scope, p);
    return p;
  }

  @Transactional
  public ObjectNode create(String scope, Create r) {
    String actor = access.require(scope, "member");
    if (r == null || r.expectedVersion() == null || r.expectedVersion() != 0)
      throw bad("expectedVersion must be 0");
    operation(r.operationId());
    String hash = hash(r);
    var replay = replay(scope, actor, r.operationId(), hash);
    if (replay != null) return replay;
    text(r.name(), "name", 300);
    text(r.customer(), "customer", 300);
    ObjectNode p = json.createObjectNode();
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
    if (r.agentId()!=null && !r.agentId().isBlank()) bindEmployee(scope,p,r.agentId());
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
    jdbc.update(
        "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json)"
            + " VALUES(?,?,?,?,?,?)",
        p.path("id").asText(),
        scope,
        1,
        r.name(),
        "ACTIVE",
        encode(p));
    record(p, actor, "CREATE");
    receipt(scope, actor, r.operationId(), hash, p);
    return p;
  }

  @Transactional
  public ObjectNode command(String scope, String projectId, Command r) {
    return applyCommand(scope,projectId,r,false);
  }

  @Transactional
  public ObjectNode saveEmployeeTask(String scope,String projectId,Command r) {
    if(!"SAVE_AI_TASK".equals(r.action()))throw bad("Employee task required");
    return applyCommand(scope,projectId,r,true);
  }

  private ObjectNode applyCommand(String scope,String projectId,Command r,boolean employeeResult) {
    if (r == null) throw bad("Command required");
    String action = Objects.toString(r.action(), "");
    String actor =
        access.require(
            scope,
            Set.of("APPROVE_BASELINE", "APPROVE_RELEASE", "PUBLISH_RELEASE").contains(action)
                ? "admin"
                : "member");
    operation(r.operationId());
    if (r.expectedVersion() == null) throw bad("expectedVersion required");
    String hash = hash(List.of(projectId, r));
    ObjectNode p = load(scope, projectId, true);
    authorizeMaterials(scope, p);
    var replay = replay(scope, actor, r.operationId(), hash);
    if (replay != null) {
      authorizeMaterials(scope, replay);
      return replay;
    }
    if (p.path("version").asInt() != r.expectedVersion())
      throw conflict("VERSION_CONFLICT", "Project has changed; reload before saving");
    if ("ARCHIVED".equals(p.path("status").asText()))
      throw conflict("PROJECT_ARCHIVED", "Archived projects are read only");
    ObjectNode value = r.payload() == null ? json.createObjectNode() : r.payload().deepCopy();
    switch (action) {
      case "UPDATE_PROJECT" -> {
        if(value.has("agentId")) bindEmployee(scope,p,value.path("agentId").asText());
        if (value.has("ownerId"))
          p.put(
              "ownerId",
              access.owner(scope, value.path("ownerId").asText(), p.path("ownerId").asText()));
        for (String key : List.of("name", "customer", "industry", "goal"))
          if (value.has(key)) {
            text(value.path(key).asText(), key, key.equals("goal") ? 10000 : 300);
            p.set(key, value.get(key));
          }
      }
      case "ARCHIVE" -> p.put("status", "ARCHIVED");
      case "BIND_MATERIAL" -> bind(scope, p, value, actor);
      case "SAVE_REQUIREMENT" -> {
        text(value.path("title").asText(), "title", 1000);
        enumValue(value, "scope", Set.of("IN", "OUT", "UNKNOWN"), "UNKNOWN");
        enumValue(value, "priority", Set.of("HIGH", "MEDIUM", "LOW"), "MEDIUM");
        value.put("customerConfirmationStatus", "UNCONFIRMED");
        saveItem(p, "requirements", value, actor, false);
      }
      case "SAVE_CLARIFICATION" -> {
        text(value.path("question").asText(), "question", 5000);
        enumValue(value, "status", Set.of("OPEN", "ANSWERED"), "OPEN");
        if (!value.path("requirementId").asText().isBlank())
          find(p, "requirements", value.path("requirementId").asText());
        if (!value.path("ownerId").asText().isBlank())
          value.put("ownerId", access.owner(scope, value.path("ownerId").asText(), actor));
        if ("ANSWERED".equals(value.path("status").asText())) {
          text(value.path("answer").asText(), "answer", 10000);
          text(value.path("answerSourceId").asText(), "answer source", 2000);
          value.put("answeredBy", actor).put("answeredAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        } else {
          value.remove(List.of("answeredBy", "answeredAt"));
        }
        saveItem(p, "clarifications", value, actor, false);
      }
      case "UNBIND_MATERIAL" -> {
        String materialId = value.path("id").asText();
        find(p, "materials", materialId);
        var items = p.withArray("materials");
        for (int i = 0; i < items.size(); i++)
          if (materialId.equals(items.get(i).path("id").asText())) {
            items.remove(i);
            break;
          }
      }
      case "CANCEL_AI_TASK" -> {
        var task = find(p, "tasks", value.path("taskId").asText());
        if (!"RUNNING".equals(task.path("status").asText()))
          throw conflict("TASK_STATE", "Task is not running");
        task.put("status", "CANCELLED");
      }
      case "SAVE_AI_TASK" -> {
        value
            .put("status", value.path("status").asText("DRAFT"))
            .put("authority", "UNTRUSTED_DRAFT");
        enumValue(value, "status", Set.of("RUNNING", "SUCCEEDED", "FAILED", "DRAFT"), "DRAFT");
        if (employeeResult && "SUCCEEDED".equals(value.path("status").asText())) {
          if (!(value.path("result") instanceof ObjectNode result))
            throw PresalesModelAdapter.error(422, "MODEL_FORMAT");
          PresalesModelAdapter.validate(result, value.path("contextSnapshot") instanceof ObjectNode snapshot ? snapshot : json.createObjectNode(), value.path("skill").asText());
        }
        saveItem(p, "tasks", value, actor, false);
        if(employeeResult && "SUCCEEDED".equals(value.path("status").asText())) {
          projectEmployeeResult(p, value, actor);
        }
      }
      case "SAVE_CONTEXT" -> {
        value.put("authority", "UNTRUSTED_DRAFT");
        saveItem(p, "contextCards", value, actor, false);
      }
      case "SAVE_REVIEW" -> {
        find(p, "solutions", value.path("solutionId").asText());
        text(value.path("summary").asText(), "summary", 10000);
        if (!value.path("issues").isArray()) throw bad("Review issues required");
        for (var issue : value.path("issues")) {
          if (!(issue instanceof ObjectNode o)) throw bad("Review issue object required");
          enumValue(o, "severity", Set.of("BLOCKER", "WARNING", "INFO"), "WARNING");
          enumValue(o, "status", Set.of("OPEN", "RESOLVED", "ACCEPTED"), "OPEN");
        }
        value.remove("authority");
        value.put("kind", "HUMAN_REVIEW").put("authority", "HUMAN_REVIEW");
        saveItem(p, "reviews", value, actor, true);
      }
      case "CREATE_RELEASE" -> createRelease(scope, p, value, actor);
      case "APPROVE_RELEASE" -> {
        var release = find(p, "releases", value.path("releaseId").asText());
        if (!"PENDING".equals(release.path("status").asText()))
          throw conflict("RELEASE_STATE", "Release is not pending");
        releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
        text(value.path("reason").asText(), "reason", 10000);
        verifyArtifacts(p.path("id").asText(), release);
        release
            .put("status", "APPROVED")
            .put("approvedBy", actor)
            .put("approvalReason", value.path("reason").asText());
      }
      case "PUBLISH_RELEASE" -> {
        var release = find(p, "releases", value.path("releaseId").asText());
        if (!"APPROVED".equals(release.path("status").asText()))
          throw conflict("RELEASE_STATE", "Exact release must be approved");
        releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
        verifyArtifacts(p.path("id").asText(), release);
        release.put("status", "PUBLISHED").put("publishedBy", actor)
            .put("publishedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        if (release.path("handoffSnapshot").path("release").isObject()) {
          ObjectNode frozenRelease = (ObjectNode) release.path("handoffSnapshot").path("release");
          frozenRelease.put("status", "PUBLISHED").put("publishedBy", actor)
              .put("publishedAt", release.path("publishedAt").asText());
          ObjectNode snapshot = (ObjectNode) release.path("handoffSnapshot");
          snapshot.set("clarifications", p.path("clarifications").deepCopy());
          ArrayNode refs = json.createArrayNode();
          for (var ref : snapshot.path("sourceRefs")) refs.add(ref.deepCopy());
          for (var ref : publicationClarificationRefs(p)) refs.add(ref.deepCopy());
          snapshot.set("sourceRefs", refs);
        }
      }
      case "APPROVE_BASELINE" -> baseline(scope, p, value, actor);
      case "SAVE_FIT_GAP" -> {
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
      case "SAVE_SOLUTION" -> saveSolutionDraft(p, value, actor, false);
      default -> throw bad("Unsupported command: " + action);
    }
    p.put("stage", stage(p));
    p.put("version", r.expectedVersion() + 1)
        .put("updatedBy", actor)
        .put("updatedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
    if (jdbc.update(
            "UPDATE mate_presales_project SET version=?,name=?,status=?,body_json=? WHERE id=? AND"
                + " workspace_id=? AND version=?",
            r.expectedVersion() + 1,
            p.path("name").asText(),
            p.path("status").asText(),
            encode(p),
            projectId,
            scope,
            r.expectedVersion())
        != 1) throw conflict("VERSION_CONFLICT", "Concurrent update");
    record(p, actor, action);
    receipt(scope, actor, r.operationId(), hash, p);
    return p;
  }

  private void projectEmployeeResult(ObjectNode p, ObjectNode task, String actor) {
    String skill = task.path("skill").asText();
    ObjectNode result = (ObjectNode) task.path("result");
    String taskId = task.path("id").asText();
    String agentId = task.path("agentId").asText();
    if (Set.of("S1", "S2").contains(skill)) {
      for (var item : result.path("items")) {
        if (!"CLARIFICATION".equals(item.path("kind").asText())) {
          ObjectNode draft = ((ObjectNode) item).deepCopy();
          draft.remove(List.of("id", "approved", "customerConfirmationStatus"));
          draft.put("proposedByTaskId", taskId).put("agentId", agentId).put("authority", "UNTRUSTED_DRAFT");
          if ("S2".equals(skill)) {
            draft.put("scope", "UNKNOWN").put("priority", "MEDIUM").put("customerConfirmationStatus", "UNCONFIRMED");
            saveItem(p, "requirements", draft, actor, false);
          } else saveItem(p, "contextCards", draft, actor, false);
        }
        if ("CLARIFICATION".equals(item.path("kind").asText())) {
          ObjectNode clarification = json.createObjectNode().put("question", item.path("text").asText())
              .put("status", "OPEN").put("proposedByTaskId", taskId).put("agentId", agentId)
              .put("authority", "UNTRUSTED_DRAFT");
          clarification.set("sourceRefs", item.path("sourceRefs").deepCopy());
          saveItem(p, "clarifications", clarification, actor, false);
        }
      }
      return;
    }
    switch (skill) {
      case "S3" -> {
        for (var item : result.path("capabilityMaps")) {
          ObjectNode draft = ((ObjectNode) item).deepCopy();
          draft.put("proposedByTaskId", taskId).put("agentId", agentId).put("authority", "UNTRUSTED_DRAFT").put("kind", "CAPABILITY_MAP");
          saveItem(p, "fitGaps", draft, actor, true);
        }
      }
      case "S4" -> {
        for (var item : result.path("cases")) {
          ObjectNode draft = ((ObjectNode) item).deepCopy();
          draft.put("proposedByTaskId", taskId).put("agentId", agentId).put("authority", "UNTRUSTED_DRAFT").put("kind", "CASE_MATCH");
          saveItem(p, "cases", draft, actor, true);
        }
      }
      case "S5", "S6" -> {
        JsonNode node = result.has("solution") ? result.path("solution") : result.path("solutionDraft");
        ObjectNode draft = ((ObjectNode) node).deepCopy();
        draft.put("proposedByTaskId", taskId).put("agentId", agentId).put("authority", "UNTRUSTED_DRAFT");
        saveSolutionDraft(p, draft, actor, true);
      }
      case "S7" -> {
        JsonNode node = result.has("review") ? result.path("review") : result.path("reviewDraft");
        ObjectNode draft = ((ObjectNode) node).deepCopy();
        draft.put("proposedByTaskId", taskId).put("agentId", agentId).put("authority", "UNTRUSTED_DRAFT").put("kind", "AI_REVIEW_DRAFT");
        saveItem(p, "reviewDrafts", draft, actor, true);
      }
      default -> { }
    }
  }

  private void saveSolutionDraft(ObjectNode p, ObjectNode value, String actor, boolean employeeResult) {
    text(value.path("title").asText(), "title", 1000);
    if (!value.path("sections").isArray() || value.path("sections").isEmpty()) throw bad("Solution sections required");
    for (var section : value.path("sections")) {
      text(section.path("title").asText(), "section title", 1000);
      text(section.path("text").asText(), "section text", 100000);
    }
    String base = value.path("baselineId").asText();
    if (!employeeResult && value.has("presentation")) throw PresalesModelAdapter.error(422, "PRESENTATION_METADATA_UNTRUSTED");
    ObjectNode baseline = null;
    if (!base.isBlank()) {
      baseline = find(p, "baselines", base);
      if (value.has("baselineVersion") && value.path("baselineVersion").asInt() != baseline.path("version").asInt())
        throw conflict("BASELINE_STALE", "Solution baseline version is stale");
      value.put("baselineVersion", baseline.path("version").asInt());
    }
    validateProjectSourceRefs(p, value);
    if (base.isBlank()) value.remove("baselineVersion");
    value.put("provisional", base.isBlank());
    value.set("coverage", coverage(p, value));
    var latestFits = new LinkedHashMap<String, String>();
    for (var fit : p.withArray("fitGaps")) latestFits.put(fit.path("requirementId").asText(), fit.path("id").asText());
    value.set("fitGapRefs", json.valueToTree(latestFits.values()));
    saveItem(p, "solutions", value, actor, true);
  }

  private void validateProjectSourceRefs(ObjectNode p, ObjectNode value) {
    Set<String> allowed = new HashSet<>();
    for (var task : p.withArray("tasks")) for (var source : task.path("contextSnapshot").path("sources")) allowed.add(source.path("sourceRef").asText());
    for (var baseline : p.withArray("baselines")) for (var reference : baseline.path("references")) for (var source : reference.path("sources")) allowed.add(source.path("sourceRef").asText());
    validateSourceRefs(value.path("sourceRefs"), allowed);
    for (var section : value.path("sections")) validateSourceRefs(section.path("sourceRefs"), allowed);
  }

  private void validateSourceRefs(JsonNode refs, Set<String> allowed) {
    if (refs.isMissingNode()) return;
    if (!refs.isArray() || refs.size() > 100) throw PresalesModelAdapter.error(422, "MODEL_FORMAT");
    for (var ref : refs) if (!ref.isTextual() || !allowed.contains(ref.asText())) throw PresalesModelAdapter.error(422, "INVALID_SOURCE_REFERENCE");
  }

  private void bindEmployee(String scope,ObjectNode p,String agentId) {
    if(employees.getIfAvailable()==null)throw PresalesModelAdapter.error(409,"EMPLOYEE_UNAVAILABLE");
    var employee=employees.getObject().require(scope,agentId);
    p.put("agentId",employee.getId().toString()).put("agentName",employee.getName());
  }

  private void bind(String scope, ObjectNode p, ObjectNode v, String actor) {
    String kb = v.path("kbId").asText();
    text(kb, "kbId", 64);
    var row = wiki.getById(parseId(kb));
    if (row == null
        || row.getWorkspaceId() == null
        || !scope.equals(row.getWorkspaceId().toString()))
      throw new SemanticApiException(404, "MATERIAL_UNAVAILABLE", "Knowledge base unavailable");
    String graph = v.path("graphId").asText();
    if (!graph.isBlank()) {
      requireSemantic();
      var g = graphs.getObject().requireGraph(scope, graph, true);
      if (!kb.equals(g.getKbId().toString())) throw bad("Graph belongs to another knowledge base");
      v.put("ontologyRevisionId", g.getOntologyRevisionId());
    }
    enumValue(v, "role", Set.of("PROJECT", "PRODUCT", "CASE"), "PROJECT");
    saveItem(p, "materials", v, actor, false);
  }

  private void baseline(String scope, ObjectNode p, ObjectNode v, String actor) {
    requireSemantic();
    text(v.path("reason").asText(), "reason", 10000);
    if (p.withArray("requirements").isEmpty()) throw bad("Requirements are required");
    for (var c : p.withArray("clarifications"))
      if (!"ANSWERED".equals(c.path("status").asText())
          || c.path("answer").asText().isBlank() || c.path("answerSourceId").asText().isBlank())
        throw conflict("OPEN_CLARIFICATIONS", "Resolve clarifications with answer sources before baseline approval");
    ArrayNode refs = json.createArrayNode();
    for (var req : p.withArray("requirements")) {
      if ("UNKNOWN".equals(req.path("scope").asText()))
        throw conflict("SCOPE_UNRESOLVED", "Resolve requirement scope before baseline approval");
      String graph = req.path("graphId").asText();
      boolean bound = false;
      for (var material : p.withArray("materials"))
        if (graph.equals(material.path("graphId").asText())) bound = true;
      if (graph.isBlank() || !bound) throw bad("Requirement graph must be bound to this project");
      var graphRow = graphs.getObject().requireGraph(scope, graph, true);
      for (var material : p.withArray("materials"))
        if (graph.equals(material.path("graphId").asText())
            && !graphRow
                .getOntologyRevisionId()
                .equals(material.path("ontologyRevisionId").asText()))
          throw conflict("BINDING_STALE", "Rebind graph after ontology change");
      var fact =
          statements.getObject().trusted(scope, graph).stream()
              .filter(
                  s ->
                      s.id().equals(req.path("statementId").asText())
                          && s.revision() == req.path("statementRevision").asInt())
              .findFirst()
              .orElseThrow(
                  () ->
                      conflict(
                          "SEMANTIC_REVIEW_REQUIRED",
                          "Each requirement must reference a current accepted supported"
                              + " statement"));
      ObjectNode ref = json.createObjectNode();
      ref.put("requirementId", req.path("id").asText())
          .put("requirementVersion", req.path("version").asInt())
          .put("scope", req.path("scope").asText())
          .put("graphId", graph)
          .put("statementId", fact.id())
          .put("statementRevision", fact.revision())
          .put("ontologyRevisionId", fact.ontologyRevisionId());
      ref.set("evidenceIds", json.valueToTree(fact.evidenceIds()));
      ref.set("assertion", json.valueToTree(fact.assertion()));
      ArrayNode evidence = ref.putArray("sources");
      for (String eid : fact.evidenceIds()) {
        var source = queries.getObject().evidence(scope, graph, eid);
        currentSource(scope, source.sourceRef(), source.textDigest(), true);
        evidence.add(json.valueToTree(source));
      }
      refs.add(ref);
    }
    v.set("references", refs);
    v.put("approvedBy", actor)
        .put("customerConfirmationStatus", "UNCONFIRMED")
        .put("projectVersion", p.path("version").asInt());
    saveItem(p, "baselines", v, actor, true);
  }

  public Object evidence(String scope, String projectId, String graphId, String evidenceId) {
    var p = get(scope, projectId);
    requireBoundGraph(p, graphId);
    requireSemantic();
    return queries.getObject().evidence(scope, graphId, evidenceId);
  }

  private void requireBoundGraph(ObjectNode p, String graph) {
    boolean found = false;
    for (var m : p.withArray("materials"))
      if (graph.equals(m.path("graphId").asText())) found = true;
    if (graph.isBlank() || !found) throw bad("Evidence graph must be bound to this project");
  }

  private void validateEvidence(String scope, ObjectNode p, ObjectNode v) {
    requireSemantic();
    String graph = v.path("graphId").asText();
    requireBoundGraph(p, graph);
    for (var id : v.path("evidenceIds")) queries.getObject().evidence(scope, graph, id.asText());
  }

  private ObjectNode coverage(ObjectNode p, ObjectNode solution) {
    String baselineId = solution.path("baselineId").asText();
    Map<String, String> scopes = new LinkedHashMap<>();
    if (!baselineId.isBlank())
      for (var ref : find(p, "baselines", baselineId).path("references"))
        scopes.put(ref.path("requirementId").asText(), ref.path("scope").asText());
    else
      for (var ref : p.withArray("requirements"))
        scopes.put(ref.path("id").asText(), ref.path("scope").asText());
    Set<String> linked = new HashSet<>();
    for (var section : solution.path("sections"))
      for (var ref : section.path("requirementRefs")) {
        if (!ref.isTextual() || !scopes.containsKey(ref.asText()))
          throw bad("Section requirementRefs must belong to selected baseline");
        linked.add(ref.asText());
      }
    Map<String, ObjectNode> responses = new LinkedHashMap<>();
    for (var response : solution.path("requirementResponses")) {
      if (!(response instanceof ObjectNode item)) throw bad("Requirement response object required");
      String rid = item.path("requirementId").asText();
      if (!scopes.containsKey(rid) || responses.containsKey(rid))
        throw bad("Unique baseline requirement response required");
      enumValue(
          item,
          "status",
          Set.of("FULL", "PARTIAL", "CONDITIONAL", "EXCLUDED", "UNHANDLED"),
          "UNHANDLED");
      if ("EXCLUDED".equals(item.path("status").asText()) && !"OUT".equals(scopes.get(rid)))
        throw bad("Only out-of-scope requirements can be EXCLUDED");
      if (Set.of("FULL", "PARTIAL", "CONDITIONAL").contains(item.path("status").asText())
          && !linked.contains(rid)) throw bad("Handled response must link to a section");
      if (!"FULL".equals(item.path("status").asText())
          && !"UNHANDLED".equals(item.path("status").asText()))
        text(item.path("reason").asText(), "response reason", 10000);
      responses.put(rid, item);
    }
    ObjectNode result = json.createObjectNode();
    ArrayNode list = result.putArray("responses");
    int total = 0, handled = 0;
    for (var entry : scopes.entrySet()) {
      ObjectNode response = responses.get(entry.getKey());
      if (response == null)
        response =
            json.createObjectNode().put("requirementId", entry.getKey()).put("status", "UNHANDLED");
      list.add(response.deepCopy());
      if ("IN".equals(entry.getValue())) {
        total++;
        if (Set.of("FULL", "PARTIAL", "CONDITIONAL").contains(response.path("status").asText()))
          handled++;
      }
    }
    result.put("applicable", total > 0).put("totalIn", total).put("handledIn", handled);
    if (total == 0) result.putNull("percentage");
    else result.put("percentage", Math.round(handled * 10000.0 / total) / 100.0);
    return result;
  }

  public ObjectNode handoff(String scope, String projectId) {
    var p = get(scope, projectId);
    ObjectNode release = null;
    for (var r : p.withArray("releases"))
      if ("PUBLISHED".equals(r.path("status").asText())) release = (ObjectNode) r;
    if (release == null)
      throw conflict("PUBLISHED_RELEASE_REQUIRED", "Publish a reviewed release before handoff");
    var solution = find(p, "solutions", release.path("solutionId").asText());
    releaseGate(scope, p, solution);
    verifyArtifacts(projectId, release);
    var baseline = find(p, "baselines", release.path("baselineId").asText());
    ObjectNode out =
        json.createObjectNode()
            .put("schemaVersion", 1)
            .put("engagementId", projectId)
            .put("caseRef", projectId)
            .put("workspaceId", scope);
    out.set("baseline", baseline.deepCopy());
    out.set("solution", solution.deepCopy());
    out.set("release", release.deepCopy());
    ArrayNode chosenFits = out.putArray("fitGaps");
    for (var fitId : solution.path("fitGapRefs"))
      chosenFits.add(find(p, "fitGaps", fitId.asText()).deepCopy());
    out.set("clarifications", p.path("clarifications").deepCopy());
    ArrayNode risks = out.putArray("risksAndUnknowns");
    for (var fit : chosenFits)
      if (Set.of("UNKNOWN", "GAP", "EXTEND", "PARTNER").contains(fit.path("status").asText()))
        risks.add(fit.deepCopy());
    for (var response : solution.path("coverage").path("responses"))
      if (!"FULL".equals(response.path("status").asText())) risks.add(response.deepCopy());
    out.put("customerConfirmationStatus", "UNCONFIRMED")
        .put("accessPolicy", "WORKSPACE_REAUTHORIZE_ON_READ");
    return out;
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
    verifyArtifacts(projectId, release);
    JsonNode snapshot = release.path("handoffSnapshot");
    if (!snapshot.isObject())
      throw new SemanticApiException(409, "HISTORICAL_SNAPSHOT_UNAVAILABLE", "该历史发布缺少可验证的冻结快照");
    ObjectNode result = ((ObjectNode) snapshot).deepCopy();
    result.put("releaseId", releaseId);
    return result;
  }

  private void releaseGate(String scope, ObjectNode p, ObjectNode solution) {
    requireSemantic();
    if (solution.path("provisional").asBoolean(true))
      throw conflict("BASELINE_REQUIRED", "Provisional solutions cannot be released");
    var baseline = find(p, "baselines", solution.path("baselineId").asText());
    var coverage = coverage(p, solution);
    if (coverage.path("handledIn").asInt() != coverage.path("totalIn").asInt())
      throw conflict(
          "REQUIREMENTS_UNHANDLED",
          "Every in-scope requirement needs an explicit linked response before release");
    if (!baseline.equals(p.withArray("baselines").get(p.withArray("baselines").size() - 1)))
      throw conflict("BASELINE_STALE", "Solution uses an older baseline");
    if (baseline.path("references").size() != p.withArray("requirements").size())
      throw conflict("BASELINE_STALE", "Requirements added after baseline approval");
    for (var ref : baseline.path("references")) {
      var requirement = find(p, "requirements", ref.path("requirementId").asText());
      if (requirement.path("version").asInt() != ref.path("requirementVersion").asInt())
        throw conflict("BASELINE_STALE", "Requirement changed after baseline approval");
      String graph = ref.path("graphId").asText();
      requireBoundGraph(p, graph);
      var currentGraph = graphs.getObject().requireGraph(scope, graph, true);
      if (!ref.path("ontologyRevisionId").asText().equals(currentGraph.getOntologyRevisionId()))
        throw conflict("BASELINE_STALE", "Graph ontology changed after baseline approval");
      boolean trusted =
          statements.getObject().trusted(scope, graph).stream()
              .anyMatch(
                  f ->
                      f.id().equals(ref.path("statementId").asText())
                          && f.revision() == ref.path("statementRevision").asInt());
      if (!trusted) throw conflict("BASELINE_STALE", "Semantic fact changed or support withdrawn");
      for (var eid : ref.path("evidenceIds")) {
        var source = queries.getObject().evidence(scope, graph, eid.asText());
        currentSource(scope, source.sourceRef(), source.textDigest(), true);
      }
    }
    for (var fitId : solution.path("fitGapRefs")) {
      var fit = find(p, "fitGaps", fitId.asText());
      if (!"UNKNOWN".equals(fit.path("status").asText())) validateEvidence(scope, p, fit);
    }
    boolean reviewed = false;
    for (var review : p.withArray("reviews"))
      if ("HUMAN_REVIEW".equals(review.path("kind").asText())
          && !"UNTRUSTED_DRAFT".equals(review.path("authority").asText())
          && solution.path("id").asText().equals(review.path("solutionId").asText())
          && !solution.path("authorId").asText().equals(review.path("authorId").asText())) {
        boolean blocked = false;
        for (var issue : review.path("issues"))
          if (!"RESOLVED".equals(issue.path("status").asText())
              && "BLOCKER".equals(issue.path("severity").asText())) blocked = true;
        reviewed = !blocked;
      }
    if (!reviewed)
      throw conflict(
          "INDEPENDENT_REVIEW_REQUIRED",
          "A separate reviewer must inspect the exact solution and resolve blockers");
  }

  private void createRelease(String scope, ObjectNode p, ObjectNode v, String actor) {
    var solution = find(p, "solutions", v.path("solutionId").asText());
    releaseGate(scope, p, solution);
    String releaseId = id();
    for (var review : p.withArray("reviews"))
      if ("HUMAN_REVIEW".equals(review.path("kind").asText())
          && !"UNTRUSTED_DRAFT".equals(review.path("authority").asText())
          && solution.path("id").asText().equals(review.path("solutionId").asText())
          && !solution.path("authorId").asText().equals(review.path("authorId").asText()))
        v.put("reviewId", review.path("id").asText());
    List<PresalesArtifactRenderer.Section> sections = new ArrayList<>();
    for (var section : solution.path("sections"))
      sections.add(
          new PresalesArtifactRenderer.Section(
              section.path("title").asText(), section.path("text").asText()));
    var files =
        new LinkedHashMap<>(
            solution.path("presentation").path("artifactId").isTextual()
                ? renderer.renderWithoutSlides(
                    new PresalesArtifactRenderer.Document(
                        solution.path("title").asText(),
                        solution.path("id").asText(),
                        false,
                        sections,
                        ""))
                : renderer.render(
            new PresalesArtifactRenderer.Document(
                solution.path("title").asText(),
                solution.path("id").asText(),
                false,
                sections,
                "")));
    String presentationArtifact = solution.path("presentation").path("artifactId").asText();
    if (!presentationArtifact.isBlank()) {
      byte[] ppt = storedPresentationArtifact(p.path("id").asText(), presentationArtifact, "solution.pptx", solution.path("presentation").path("sha256").asText());
      files.put("solution.pptx", ppt);
    }
    ArrayNode manifest = v.putArray("files");
    for (var entry : files.entrySet()) {
      String digest = java.util.HexFormat.of().formatHex(sha(entry.getValue()));
      jdbc.update(
          "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64)"
              + " VALUES(?,?,?,?,?)",
          p.path("id").asText(),
          releaseId,
          entry.getKey(),
          digest,
          Base64.getEncoder().encodeToString(entry.getValue()));
      manifest
          .addObject()
          .put("filename", entry.getKey())
          .put("sha256", digest)
          .put("size", entry.getValue().length);
    }
    v.put("status", "PENDING")
        .put("baselineId", solution.path("baselineId").asText())
        .put("templateVersion", PresalesArtifactRenderer.TEMPLATE_VERSION);
    saveItem(p, "releases", v, actor, true);
    String storedReleaseId = v.path("id").asText();
    if (!releaseId.equals(storedReleaseId)) {
      jdbc.update("UPDATE mate_presales_artifact SET release_id=? WHERE project_id=? AND release_id=?",
          storedReleaseId, p.path("id").asText(), releaseId);
      releaseId = storedReleaseId;
    }
    ObjectNode handoff = json.createObjectNode().put("schemaVersion", 1)
        .put("engagementId", p.path("id").asText()).put("caseRef", p.path("id").asText())
        .put("workspaceId", scope).put("historicalClarificationsAvailable", true)
        .put("customerConfirmationStatus", "UNCONFIRMED").put("accessPolicy", "WORKSPACE_REAUTHORIZE_ON_READ");
    handoff.set("baseline", find(p, "baselines", v.path("baselineId").asText()).deepCopy());
    handoff.set("solution", solution.deepCopy());
    handoff.set("release", v.deepCopy());
    ArrayNode chosenFits = handoff.putArray("fitGaps");
    for (var fitId : solution.path("fitGapRefs")) chosenFits.add(find(p, "fitGaps", fitId.asText()).deepCopy());
    // The published release freezes the clarification state and its source references.
    handoff.set("clarifications", p.path("clarifications").deepCopy());
    handoff.put("historicalClarificationsAvailable", true);
    handoff.set("sourceRefs", find(p, "baselines", v.path("baselineId").asText()).path("references").deepCopy());
    handoff.set("materials", p.path("materials").deepCopy());
    ArrayNode risks = handoff.putArray("risksAndUnknowns");
    for (var fit : chosenFits)
      if (Set.of("UNKNOWN", "GAP", "EXTEND", "PARTNER").contains(fit.path("status").asText())) risks.add(fit.deepCopy());
    for (var response : solution.path("coverage").path("responses"))
      if (!"FULL".equals(response.path("status").asText())) risks.add(response.deepCopy());
    v.set("handoffSnapshot", handoff);
  }

  private ArrayNode publicationClarificationRefs(ObjectNode project) {
    ArrayNode refs = json.createArrayNode();
    for (var clarification : project.withArray("clarifications"))
      if (clarification.path("sourceRefs").isArray())
        for (var ref : clarification.path("sourceRefs")) refs.add(ref.deepCopy());
    return refs;
  }

  private void authorizeMaterials(String scope, ObjectNode p) {
    for (var m : p.withArray("materials")) {
      var kb = wiki.getById(parseId(m.path("kbId").asText()));
      if (kb == null
          || kb.getWorkspaceId() == null
          || !scope.equals(kb.getWorkspaceId().toString())
          || (kb.getDeleted() != null && kb.getDeleted() != 0))
        throw new SemanticApiException(
            403, "MATERIAL_UNAVAILABLE", "Project material access was revoked");
    }
    for (var baseline : p.withArray("baselines"))
      for (var ref : baseline.path("references"))
        for (var source : ref.path("sources")) {
          currentSource(scope, source.path("sourceRef").asText(), "", false);
          int withdrawn =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND"
                      + " source_id=? AND state='WITHDRAWN'",
                  Integer.class,
                  ref.path("graphId").asText(),
                  source.path("sourceRef").asText());
          if (withdrawn > 0)
            throw new SemanticApiException(
                403, "SOURCE_UNAVAILABLE", "Historical evidence source withdrawn");
        }
    for (var task : p.withArray("tasks"))
      for (var source : task.path("contextSnapshot").path("sources")) {
        currentSource(scope, source.path("sourceRef").asText(), "", false);
        String graph = source.path("graphId").asText();
        if (!graph.isBlank() && jdbc.queryForObject(
            "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=? AND state='WITHDRAWN'",
            Integer.class, graph, source.path("sourceRef").asText()) > 0)
          throw new SemanticApiException(403, "SOURCE_UNAVAILABLE", "Task source withdrawn");
      }
  }

  private void currentSource(String scope, String sourceId, String digest, boolean checkDigest) {
    var rows =
        jdbc.queryForList(
            "SELECT COALESCE(NULLIF(r.extracted_text,''),r.original_content) AS source_text FROM"
                + " mate_wiki_raw_material r JOIN mate_wiki_knowledge_base k ON k.id=r.kb_id WHERE"
                + " r.id=? AND k.workspace_id=? AND r.deleted=0 AND k.deleted=0",
            sourceId,
            scope);
    if (rows.size() != 1)
      throw new SemanticApiException(403, "SOURCE_UNAVAILABLE", "Referenced source access revoked");
    if (checkDigest
        && !digest.equals(
            PresalesArtifactRenderer.digest(
                Objects.toString(rows.getFirst().get("source_text"), "")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
      throw conflict(
          "SOURCE_CHANGED", "Source changed since semantic snapshot; review fresh evidence");
  }

  private void verifyArtifacts(String projectId, ObjectNode release) {
    for (var file : release.path("files")) {
      var rows =
          jdbc.queryForList(
              "SELECT digest,content_base64 FROM mate_presales_artifact WHERE project_id=? AND"
                  + " release_id=? AND filename=?",
              projectId,
              release.path("id").asText(),
              file.path("filename").asText());
      if (rows.size() != 1) throw conflict("ARTIFACT_MISSING", "Candidate file missing");
      var row = rows.getFirst();
      String digest =
          java.util.HexFormat.of()
              .formatHex(sha(Base64.getDecoder().decode(row.get("content_base64").toString())));
      if (!digest.equals(row.get("digest")) || !digest.equals(file.path("sha256").asText()))
        throw conflict("ARTIFACT_DIGEST_MISMATCH", "Candidate bytes changed");
    }
  }

  public byte[] preview(String scope, String projectId, String releaseId, String filename) {
    access.require(scope, "admin");
    var p = get(scope, projectId);
    var release = find(p, "releases", releaseId);
    releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
    verifyArtifacts(projectId, release);
    var rows =
        jdbc.queryForList(
            "SELECT content_base64 FROM mate_presales_artifact WHERE project_id=? AND release_id=?"
                + " AND filename=?",
            projectId,
            releaseId,
            filename);
    if (rows.isEmpty()) throw new SemanticApiException(404, "NOT_FOUND", "Artifact not found");
    return Base64.getDecoder().decode(rows.getFirst().get("content_base64").toString());
  }

  public byte[] draftArtifact(String scope, String projectId, String solutionId, String filename) {
    var p = get(scope, projectId);
    var solution = find(p, "solutions", solutionId);
    String presentationArtifact = solution.path("presentation").path("artifactId").asText();
    if (!presentationArtifact.isBlank() && presentationFile(solution, filename))
      return storedPresentationArtifact(projectId, presentationArtifact, filename, presentationDigest(solution, filename));
    List<PresalesArtifactRenderer.Section> sections = new ArrayList<>();
    for (var section : solution.path("sections"))
      sections.add(
          new PresalesArtifactRenderer.Section(
              section.path("title").asText(), section.path("text").asText()));
    byte[] bytes =
        renderer
            .render(
                new PresalesArtifactRenderer.Document(
                    solution.path("title").asText(),
                    solutionId,
                    true,
                    sections,
                    "UNAPPROVED DRAFT — internal review only"))
            .get(filename);
    if (bytes == null) throw new SemanticApiException(404, "NOT_FOUND", "Unknown artifact format");
    return bytes;
  }

  private boolean presentationFile(ObjectNode solution, String filename) {
    if ("solution.pptx".equals(filename)) return true;
    for (var slide : solution.path("presentation").path("slides"))
      if (filename.equals(slide.path("filename").asText())) return true;
    return "quality-report.json".equals(filename);
  }

  private String presentationDigest(ObjectNode solution, String filename) {
    if ("solution.pptx".equals(filename)) return solution.path("presentation").path("sha256").asText();
    if ("quality-report.json".equals(filename)) return solution.path("presentation").path("qualityReportSha256").asText();
    for (var slide : solution.path("presentation").path("slides")) if (filename.equals(slide.path("filename").asText())) return slide.path("sha256").asText();
    return "";
  }

  private byte[] storedPresentationArtifact(String projectId, String artifactId, String filename, String expectedDigest) {
    var rows = jdbc.queryForList(
        "SELECT digest,content_base64 FROM mate_presales_artifact WHERE project_id=? AND release_id=? AND filename=?",
        projectId, artifactId, filename);
    if (rows.size() != 1) throw new SemanticApiException(404, "NOT_FOUND", "Presentation artifact not found");
    byte[] bytes = Base64.getDecoder().decode(rows.getFirst().get("content_base64").toString());
    String digest = java.util.HexFormat.of().formatHex(sha(bytes));
    if (!digest.equals(rows.getFirst().get("digest").toString()) || (!expectedDigest.isBlank() && !digest.equals(expectedDigest)))
      throw conflict("ARTIFACT_DIGEST_MISMATCH", "Presentation artifact integrity failure");
    return bytes;
  }

  public byte[] artifact(String scope, String projectId, String releaseId, String filename) {
    var p = get(scope, projectId);
    var release = find(p, "releases", releaseId);
    if (!"PUBLISHED".equals(release.path("status").asText()))
      throw new SemanticApiException(
          403, "RELEASE_NOT_PUBLISHED", "Only published artifacts can be downloaded");
    releaseGate(scope, p, find(p, "solutions", release.path("solutionId").asText()));
    var rows =
        jdbc.queryForList(
            "SELECT digest,content_base64 FROM mate_presales_artifact WHERE project_id=? AND"
                + " release_id=? AND filename=?",
            projectId,
            releaseId,
            filename);
    if (rows.isEmpty()) throw new SemanticApiException(404, "NOT_FOUND", "Artifact not found");
    byte[] bytes = Base64.getDecoder().decode(rows.getFirst().get("content_base64").toString());
    if (!java.util.HexFormat.of().formatHex(sha(bytes)).equals(rows.getFirst().get("digest")))
      throw conflict("ARTIFACT_DIGEST_MISMATCH", "Artifact integrity failure");
    return bytes;
  }

  private static byte[] sha(byte[] bytes) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void requireSemantic() {
    if (!semantic.isEnabled()
        || graphs.getIfAvailable() == null
        || statements.getIfAvailable() == null)
      throw conflict("SEMANTIC_DISABLED", "Semantic module is required for approval");
  }

  private void saveItem(
      ObjectNode p, String collection, ObjectNode v, String actor, boolean immutable) {
    var items = p.withArray(collection);
    String requested = v.path("id").asText();
    int version = 1;
    if (!requested.isBlank()) {
      var old = find(p, collection, requested);
      version = old.path("version").asInt() + 1;
      if (immutable) v.put("previousId", requested);
      else
        for (int i = 0; i < items.size(); i++)
          if (requested.equals(items.get(i).path("id").asText())) {
            items.remove(i);
            break;
          }
    }
    v.put("id", immutable || requested.isBlank() ? id() : requested)
        .put("version", version)
        .put("authorId", actor)
        .put("createdAt", LocalDateTime.now(ZoneOffset.UTC).toString());
    items.add(v);
  }

  public ObjectNode find(ObjectNode p, String collection, String id) {
    for (var node : p.withArray(collection))
      if (id.equals(node.path("id").asText())) return (ObjectNode) node;
    throw new SemanticApiException(404, "NOT_FOUND", collection + " item not found");
  }

  private ObjectNode load(String scope, String id, boolean lock) {
    var found =
        jdbc.query(
            "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> decode(rs.getString(1)),
            id,
            scope);
    if (found.isEmpty()) throw new SemanticApiException(404, "NOT_FOUND", "Project not found");
    return found.getFirst();
  }

  private ObjectNode replay(String scope, String actor, String operation, String hash) {
    var rows =
        jdbc.queryForList(
            "SELECT request_hash,response_json FROM mate_presales_operation WHERE workspace_id=?"
                + " AND actor_id=? AND operation_id=?",
            scope,
            actor,
            operation);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!hash.equals(row.get("request_hash")))
      throw conflict("OPERATION_CONFLICT", "Operation id reused with different input");
    return decode(row.get("response_json").toString());
  }

  private void receipt(String scope, String actor, String operation, String hash, ObjectNode p) {
    jdbc.update(
        "INSERT INTO"
            + " mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json)"
            + " VALUES(?,?,?,?,?)",
        scope,
        actor,
        operation,
        hash,
        encode(p));
  }

  private void record(ObjectNode p, String actor, String action) {
    jdbc.update(
        "INSERT INTO"
            + " mate_presales_revision(project_id,version,actor_id,action,body_json,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        p.path("id").asText(),
        p.path("version").asInt(),
        actor,
        action,
        encode(p),
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

  private String hash(Object o) {
    return StatementApplicationService.hash(encode(o));
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

  private static void text(String s, String label, int max) {
    if (s == null || s.isBlank() || s.length() > max)
      throw bad(label + " required, max " + max + " characters");
  }

  private static void enumValue(ObjectNode v, String key, Set<String> allowed, String fallback) {
    String value = v.path(key).asText(fallback);
    if (!allowed.contains(value)) throw bad("Invalid " + key);
    v.put(key, value);
  }

  private static SemanticApiException bad(String message) {
    return new SemanticApiException(400, "INVALID_REQUEST", message);
  }

  private static SemanticApiException conflict(String code, String message) {
    return new SemanticApiException(409, code, message);
  }
}
