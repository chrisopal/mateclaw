package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import vip.mate.semantic.web.SemanticApiException;

/** Validates untrusted employee output before it can become a project draft. */
public final class PresalesModelAdapter {
  private static final Set<String> ORIGINS = Set.of("CUSTOMER_SOURCE", "PRODUCT_SOURCE", "INTERNAL_JUDGMENT", "ASSUMPTION", "AI_SUGGESTION");
  private static final Set<String> FIT_STATUSES = Set.of("FIT", "CONFIG", "EXTEND", "PARTNER", "GAP", "UNKNOWN");
  private static final Set<String> REVIEW_SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR", "WARNING", "INFO");

  private PresalesModelAdapter() {}

  /** Returns the skill-specific output contract appended to the employee prompt. */
  public static String instructions(String skill) {
    String key = skill == null ? "" : skill.toUpperCase(Locale.ROOT);
    String contract = switch (key) {
      case "S3" -> "Return JSON with schemaVersion:1, needsHumanReview:true, capabilityMaps:[{requirementId,status:FIT|CONFIG|EXTEND|PARTNER|GAP|UNKNOWN,productVersion,reason,sourceRefs:[exact sourceRef]}], assumptions:[], unknowns:[], warnings:[]. Each non-UNKNOWN map needs sourceRefs and every reference must come from the supplied project snapshot.";
      case "S4" -> "Return JSON with schemaVersion:1, needsHumanReview:true, cases:[{title,summary,requirementRefs:[],sourceRefs:[exact sourceRef],anonymizationRequired:true}], assumptions:[], unknowns:[], warnings:[]. Do not turn case outcomes into current-project promises.";
      case "S5" -> "Return JSON with schemaVersion:1, needsHumanReview:true, solution:{title,baselineId,baselineVersion,sourceRefs:[],sections:[{title,text,requirementRefs:[],sourceRefs:[]}],requirementResponses:[]}, assumptions:[], unknowns:[], warnings:[]. The solution must link the supplied baseline and every reference must be in the project snapshot.";
      case "S6" -> "Return ONLY JSON with schemaVersion:1, needsHumanReview:true, solution:{sourceSolutionId,presentation:{skill:'ppt-master-plus',slides:[{title,svg}]}}, assumptions:[], unknowns:[], warnings:[]. Do not repeat source solution sections or other fields: the server copies the exact selected immutable version. Produce 3 concise slides. Use single quotes for SVG attribute values to simplify JSON encoding. Slides are untrusted drafts, never approval.";
      case "S7" -> "Return JSON with schemaVersion:1, needsHumanReview:true, review:{solutionId,summary,sourceRefs:[],issues:[{severity:BLOCKER|MAJOR|MINOR|INFO,status:OPEN|RESOLVED|ACCEPTED,description,sourceRefs:[]}]}, assumptions:[], unknowns:[], warnings:[]. This is a review draft only; do not approve, publish, or modify the solution.";
      default -> "Return ONLY JSON: {schemaVersion:1,needsHumanReview:true,items:[{kind:CLARIFICATION|WORK_ITEM,title,text,originKind:CUSTOMER_SOURCE|PRODUCT_SOURCE|INTERNAL_JUDGMENT|ASSUMPTION|AI_SUGGESTION,sourceRefs:[exact sourceRef]}],assumptions:[],unknowns:[],warnings:[]}. Every result is an untrusted proposal requiring human review.";
    };
    return readSkill(key) + "\n\nPLATFORM OUTPUT CONTRACT:\n" + contract;
  }

  static void bindPresentationSource(ObjectNode result, ObjectNode context) {
    JsonNode proposed = result.path("solution");
    String target = context.path("targetSolutionId").asText();
    if (target.isBlank() || !target.equals(proposed.path("sourceSolutionId").asText())) throw error(422,"SOLUTION_VERSION_REQUIRED");
    for (JsonNode selected : context.path("solutions")) {
      if (!target.equals(selected.path("id").asText())) continue;
      ObjectNode bound = result.objectNode();
      for (String field : new String[]{"title","baselineId","baselineVersion","sourceRefs","sections","requirementResponses"}) {
        if (selected.has(field)) bound.set(field, selected.path(field).deepCopy());
      }
      bound.put("sourceSolutionId",target);
      if (proposed.has("presentation")) bound.set("presentation",proposed.path("presentation").deepCopy());
      result.set("solution",bound);
      return;
    }
    throw error(422,"SOLUTION_VERSION_REQUIRED");
  }

  private static String readSkill(String skill) {
    String[] names = {"S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8"};
    String[] paths = {"customer-context-analysis", "requirement-analysis-and-clarification", "capability-mapping", "case-retrieval", "solution-composer", "proposal-generation", "solution-review", "context-maintenance"};
    for (int i = 0; i < names.length; i++) if (names[i].equals(skill)) {
      try (var input = PresalesModelAdapter.class.getClassLoader().getResourceAsStream("skills/presales-" + paths[i] + "/SKILL.md")) {
        if (input == null) throw new IllegalStateException("Presales skill missing: " + skill);
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      } catch (java.io.IOException e) { throw new IllegalStateException("Presales skill unreadable: " + skill, e); }
    }
    return "";
  }

  static void validate(ObjectNode result, ObjectNode context) { validate(result, context, context.path("skill").asText("")); }

  static void validate(ObjectNode result, ObjectNode context, String skill) {
    if (result == null || context == null) throw error(422, "MODEL_FORMAT");
    if (result.path("schemaVersion").asInt() != 1 || !result.path("needsHumanReview").asBoolean()
        || !result.path("assumptions").isArray() || result.path("assumptions").size() > 100
        || !result.path("unknowns").isArray() || result.path("unknowns").size() > 100) throw error(422, "MODEL_FORMAT");
    validateTextArray(result.path("assumptions"), 2000); validateTextArray(result.path("unknowns"), 2000);
    if (result.has("warnings")) { if (!result.path("warnings").isArray() || result.path("warnings").size() > 100) throw error(422, "MODEL_FORMAT"); validateTextArray(result.path("warnings"), 2000); }
    rejectAuthority(result);
    Set<String> sources = sourceRefs(context), requirements = requirementRefs(context), baselines = ids(context.path("baselines")), solutions = ids(context.path("solutions"));
    switch (skill == null ? "" : skill.toUpperCase(Locale.ROOT)) {
      case "S3" -> validateCapabilityMaps(result, sources, requirements);
      case "S4" -> validateCases(result, sources, requirements);
      case "S5", "S6" -> validateSolution(result, context, skill.toUpperCase(Locale.ROOT), sources, requirements, baselines, solutions);
      case "S7" -> validateReview(result, sources, solutions);
      default -> validateItems(result, sources);
    }
  }

  private static void validateItems(ObjectNode result, Set<String> sources) {
    if (!result.path("items").isArray() || result.path("items").size() > 100) throw error(422, "MODEL_FORMAT");
    for (JsonNode item : result.path("items")) {
      if (!item.isObject() || item.path("title").asText().isBlank() || item.path("title").asText().length() > 1000 || item.path("text").asText().isBlank() || item.path("text").asText().length() > 100000 || (item.has("kind") && !Set.of("CLARIFICATION", "WORK_ITEM").contains(item.path("kind").asText())) || !ORIGINS.contains(item.path("originKind").asText()) || !item.path("sourceRefs").isArray()) throw error(422, "MODEL_FORMAT");
      validateSourceRefs(item.path("sourceRefs"), sources);
      if (Set.of("CUSTOMER_SOURCE", "PRODUCT_SOURCE").contains(item.path("originKind").asText()) && item.path("sourceRefs").isEmpty()) throw error(422, "EVIDENCE_REQUIRED");
    }
  }

  private static void validateCapabilityMaps(ObjectNode result, Set<String> sources, Set<String> requirements) {
    if (!result.path("capabilityMaps").isArray() || result.path("capabilityMaps").size() > 100) throw error(422, "MODEL_FORMAT");
    for (JsonNode map : result.path("capabilityMaps")) {
      if (!map.isObject() || !requirements.contains(map.path("requirementId").asText())) throw error(422, "INVALID_REQUIREMENT_REFERENCE");
      if (!FIT_STATUSES.contains(map.path("status").asText()) || blankOrTooLong(map.path("productVersion").asText(), 300) || blankOrTooLong(map.path("reason").asText(), 10000) || !map.path("sourceRefs").isArray()) throw error(422, "MODEL_FORMAT");
      validateSourceRefs(map.path("sourceRefs"), sources);
      if (!"UNKNOWN".equals(map.path("status").asText()) && map.path("sourceRefs").isEmpty()) throw error(422, "EVIDENCE_REQUIRED");
    }
  }

  private static void validateCases(ObjectNode result, Set<String> sources, Set<String> requirements) {
    if (!result.path("cases").isArray() || result.path("cases").size() > 100) throw error(422, "MODEL_FORMAT");
    for (JsonNode item : result.path("cases")) {
      String summary = item.has("summary") ? item.path("summary").asText() : item.path("text").asText();
      if (!item.isObject() || blankOrTooLong(item.path("title").asText(), 1000) || blankOrTooLong(summary, 10000) || !item.path("sourceRefs").isArray() || item.path("sourceRefs").isEmpty()) throw error(422, "MODEL_FORMAT");
      validateSourceRefs(item.path("sourceRefs"), sources); validateRequirementRefs(item.path("requirementRefs"), requirements);
    }
  }

  private static void validateSolution(ObjectNode result, ObjectNode context, String skill, Set<String> sources, Set<String> requirements, Set<String> baselines, Set<String> solutions) {
    JsonNode node = result.has("solution") ? result.path("solution") : result.path("solutionDraft");
    if (!node.isObject() || blankOrTooLong(node.path("title").asText(), 1000) || !node.path("sections").isArray() || node.path("sections").isEmpty() || node.path("sections").size() > 100 || !node.path("baselineId").isTextual() || (!node.path("baselineId").asText().isBlank() && !baselines.contains(node.path("baselineId").asText())) || ("S6".equals(skill) && node.path("baselineId").asText().isBlank() && !node.has("sourceSolutionId"))) throw error(422, "BASELINE_REQUIRED");
    if (!node.path("baselineId").asText().isBlank() && node.has("baselineVersion") && node.path("baselineVersion").asInt(-1) < 1) throw error(422, "MODEL_FORMAT");
    validateSourceRefs(node.path("sourceRefs"), sources);
    for (JsonNode section : node.path("sections")) {
      if (!section.isObject() || blankOrTooLong(section.path("title").asText(), 1000) || blankOrTooLong(section.path("text").asText(), 100000)) throw error(422, "MODEL_FORMAT");
      validateRequirementRefs(section.path("requirementRefs"), requirements); validateSourceRefs(section.path("sourceRefs"), sources);
    }
    if (node.has("requirementResponses")) {
      if (!node.path("requirementResponses").isArray() || node.path("requirementResponses").size() > 100) throw error(422, "MODEL_FORMAT");
      for (JsonNode response : node.path("requirementResponses")) if (!response.isObject() || !requirements.contains(response.path("requirementId").asText())) throw error(422, "INVALID_REQUIREMENT_REFERENCE");
    }
    if ("S6".equals(skill)) {
      String target = context.path("targetSolutionId").asText(); String source = node.path("sourceSolutionId").asText();
      if (source.isBlank() || !solutions.contains(source) || target.isBlank() || !target.equals(source)) throw error(422, "SOLUTION_VERSION_REQUIRED");
      JsonNode selected = null;
      for (JsonNode candidate : context.path("solutions")) if (source.equals(candidate.path("id").asText())) selected = candidate;
      if (selected == null || !selected.path("title").equals(node.path("title")) || !selected.path("baselineId").equals(node.path("baselineId")) || !selected.path("sections").equals(node.path("sections"))) throw error(422, "SOLUTION_VERSION_REQUIRED");
      validatePresentation(node.path("presentation"));
    }
  }

  private static void validatePresentation(JsonNode presentation) {
    if (presentation.isMissingNode()) return;
    if (!presentation.isObject() || !presentation.path("slides").isArray() || presentation.path("slides").size() > 20) throw error(422, "MODEL_FORMAT");
    for (JsonNode slide : presentation.path("slides")) {
      if (!slide.isObject() || blankOrTooLong(slide.path("title").asText(), 300)) throw error(422, "MODEL_FORMAT");
      boolean metadata = slide.has("filename") || slide.has("sha256");
      if (metadata) {
        if (blankOrTooLong(slide.path("filename").asText(), 300) || blankOrTooLong(slide.path("sha256").asText(), 128)) throw error(422, "MODEL_FORMAT");
      } else if (blankOrTooLong(slide.path("svg").asText(), 500000)) throw error(422, "MODEL_FORMAT");
    }
    if (presentation.has("artifactId") && blankOrTooLong(presentation.path("artifactId").asText(), 128)) throw error(422, "MODEL_FORMAT");
    if (presentation.has("sha256") && blankOrTooLong(presentation.path("sha256").asText(), 128)) throw error(422, "MODEL_FORMAT");
  }

  private static void validateReview(ObjectNode result, Set<String> sources, Set<String> solutions) {
    JsonNode review = result.has("review") ? result.path("review") : result.path("reviewDraft");
    if (!review.isObject() || !solutions.contains(review.path("solutionId").asText()) || blankOrTooLong(review.path("summary").asText(), 10000) || !review.path("issues").isArray() || review.path("issues").size() > 100) throw error(422, "MODEL_FORMAT");
    validateSourceRefs(review.path("sourceRefs"), sources);
    for (JsonNode issue : review.path("issues")) {
      if (!issue.isObject() || !Set.of("BLOCKER", "MAJOR", "MINOR", "WARNING", "INFO").contains(issue.path("severity").asText()) || !Set.of("OPEN", "RESOLVED", "ACCEPTED").contains(issue.path("status").asText()) || blankOrTooLong(issue.path("description").asText(), 10000)) throw error(422, "MODEL_FORMAT");
      validateSourceRefs(issue.path("sourceRefs"), sources);
    }
  }

  private static void rejectAuthority(JsonNode node) {
    if (node.isObject()) node.fields().forEachRemaining(e -> { if (("approved".equals(e.getKey()) || "published".equals(e.getKey())) && e.getValue().asBoolean(false)) throw error(422, "MODEL_AUTHORITY_REJECTED"); if ("authority".equals(e.getKey()) && !e.getValue().isNull() && !e.getValue().asText().isBlank() && !"UNTRUSTED_DRAFT".equals(e.getValue().asText())) throw error(422, "MODEL_AUTHORITY_REJECTED"); rejectAuthority(e.getValue()); });
    else if (node.isArray()) node.forEach(PresalesModelAdapter::rejectAuthority);
  }

  private static Set<String> sourceRefs(ObjectNode context) { Set<String> refs = new HashSet<>(); for (JsonNode source : context.path("sources")) if (source.path("sourceRef").isTextual()) refs.add(source.path("sourceRef").asText()); return refs; }
  private static Set<String> requirementRefs(ObjectNode context) { Set<String> refs = ids(context.path("requirements")); for (JsonNode baseline : context.path("baselines")) for (JsonNode ref : baseline.path("references")) if (ref.path("requirementId").isTextual()) refs.add(ref.path("requirementId").asText()); return refs; }
  private static Set<String> ids(JsonNode nodes) { Set<String> ids = new HashSet<>(); if (nodes.isArray()) for (JsonNode node : nodes) if (node.path("id").isTextual()) ids.add(node.path("id").asText()); return ids; }
  private static void validateRequirementRefs(JsonNode refs, Set<String> allowed) { if (refs.isMissingNode()) return; if (!refs.isArray() || refs.size() > 100) throw error(422, "MODEL_FORMAT"); for (JsonNode ref : refs) if (!ref.isTextual() || !allowed.contains(ref.asText())) throw error(422, "INVALID_REQUIREMENT_REFERENCE"); }
  private static void validateSourceRefs(JsonNode refs, Set<String> allowed) { if (refs.isMissingNode()) return; if (!refs.isArray() || refs.size() > 100) throw error(422, "MODEL_FORMAT"); for (JsonNode ref : refs) if (!ref.isTextual() || !allowed.contains(ref.asText())) throw error(422, "INVALID_SOURCE_REFERENCE"); }
  private static void validateTextArray(JsonNode values, int max) { for (JsonNode value : values) if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw error(422, "MODEL_FORMAT"); }
  private static boolean blankOrTooLong(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
  static SemanticApiException error(int status, String code) { return new SemanticApiException(status, code, code); }
}
