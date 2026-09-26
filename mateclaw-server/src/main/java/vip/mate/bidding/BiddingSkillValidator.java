package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic per-skill contract and source-evidence validation. */
@Component
public final class BiddingSkillValidator {
    private static final Set<String> SKILLS = Set.of("bidding-tender-profile", "bidding-elimination-analysis",
            "bidding-requirement-analysis", "bidding-scoring-analysis");

    public void validateWriting(ObjectNode payload, List<BiddingTypes.Ref> allowedMaterials) {
        if (payload == null || !"1".equals(payload.path("schemaVersion").asText())) invalid("/schemaVersion", "Expected writing schema version 1");
        only(payload, Set.of("schemaVersion", "chapter", "responses", "citations", "missingMaterials", "unresolvedItems", "warnings"), "");
        new BiddingContentBlocks().validate(payload.path("chapter").isObject()?(ObjectNode)payload.path("chapter"):null, allowedMaterials);
        for (String key : List.of("responses", "citations", "missingMaterials", "unresolvedItems", "warnings")) {
            if (!payload.path(key).isArray()) invalid("/" + key, "Expected an array");
        }
        if(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024)throw BiddingAccess.error(413,"WRITING_OUTPUT_LIMIT","Writing result exceeds 2 MiB");
    }

    public void validateWritingEvidence(ObjectNode payload,ObjectNode input) {
        Set<String> baselineEvidence=new HashSet<>();Set<String> assigned=new HashSet<>();
        for(String key:List.of("requirements","criteria"))if(input.path(key).isArray())for(JsonNode item:input.path(key)){
            String id=item.path("id").asText();if(!id.isBlank())assigned.add(id);
            JsonNode evidence=item.path("evidenceRefs");if(evidence.isArray())for(JsonNode ref:evidence)baselineEvidence.add(evidenceKey(ref));
        }
        Map<String,JsonNode> materials=new HashMap<>();JsonNode snapshot=input.path("materials").path("items");if(snapshot.isArray())for(JsonNode item:snapshot){JsonNode ref=item.path("ref");materials.put(ref.path("kind").asText()+":"+ref.path("id").asText()+":"+ref.path("version").asText()+":"+ref.path("digest").asText(),item.path("content"));}
        Set<String> citedRequirements=new HashSet<>();
        for(int i=0;i<payload.path("citations").size();i++){
            JsonNode c=payload.path("citations").get(i);String at="/citations/"+i;
            only(c,Set.of("requirementRef","criterionRef","sourceId","version","blockId","quote","materialRef"),at);
            String linked=c.path("requirementRef").asText(c.path("criterionRef").asText(""));if(!assigned.contains(linked))invalid(at,"Citation must link to an assigned requirement or criterion");
            if(c.hasNonNull("requirementRef"))citedRequirements.add(c.path("requirementRef").asText());
            JsonNode quote=c.path("quote");if(!quote.isTextual()||quote.asText().isBlank()||quote.asText().length()>2000)invalid(at+"/quote","Citation quote is required and bounded");
            if(c.has("materialRef")){
                JsonNode r=c.path("materialRef");String key=r.path("kind").asText()+":"+r.path("id").asText()+":"+r.path("version").asText()+":"+r.path("digest").asText();JsonNode content=materials.get(key);
                if(content==null||!containsText(content,quote.asText()))invalid(at,"Citation material or exact quote is outside the authorized snapshot");
            }else{
                String key=evidenceKey(c);if(!baselineEvidence.contains(key))invalid(at,"Source citation is not exact evidence assigned to this chapter");
            }
        }
        for(JsonNode response:payload.path("responses"))if("RESPONDED".equals(response.path("status").asText())&&!citedRequirements.contains(response.path("requirementRef").asText()))invalid("/responses","A responded requirement needs an exact assigned citation");
    }

    private static String evidenceKey(JsonNode ref){return ref.path("sourceId").asText()+"|"+ref.path("version").asText()+"|"+ref.path("blockId").asText()+"|"+ref.path("quote").asText();}
    private static boolean containsText(JsonNode node,String quote){if(node.isTextual())return node.asText().contains(quote);if(node.isContainerNode()){var fields=node.elements();while(fields.hasNext())if(containsText(fields.next(),quote))return true;}return false;}

    public void validate(String skillId, ObjectNode payload, ObjectNode input) {
        if (!SKILLS.contains(skillId)) invalid("/skillId", "Unsupported analysis skill");
        if (payload == null || input == null) invalid("/", "Payload and input must be objects");
        only(input, Set.of("schemaVersion", "blocks", "readBlockIds", "taskGroupId", "skillId", "shardIndex", "shardCount", "_biddingTargetId"), "/input");
        if (!"1".equals(input.path("schemaVersion").asText())) invalid("/input/schemaVersion", "Expected schema version 1");
        if (!input.path("blocks").isArray() || !input.path("readBlockIds").isArray()) invalid("/input/blocks", "Blocks and read receipts are required");
        Map<String, JsonNode> blocks = new LinkedHashMap<>();
        for (int i = 0; i < input.path("blocks").size(); i++) {
            JsonNode block = input.path("blocks").get(i);
            only(block, Set.of("id", "sourceId", "version", "text", "locator"), "/input/blocks/" + i);
            String id = text(block, "id", "/input/blocks/" + i, 128);
            text(block, "sourceId", "/input/blocks/" + i, 128);
            if (!block.path("version").canConvertToLong() || block.path("version").asLong() < 1) invalid("/input/blocks/" + i + "/version", "Expected positive source version");
            text(block, "text", "/input/blocks/" + i, 100_000);
            if (blocks.putIfAbsent(id, block) != null) invalid("/input/blocks/" + i + "/id", "Duplicate block id");
        }
        Set<String> assigned = blocks.keySet();
        Set<String> read = stringSet(input.path("readBlockIds"), "/input/readBlockIds");
        if (!assigned.containsAll(read)) invalid("/input/readBlockIds", "Read receipt is outside this task's assignment");
        only(payload, rootKeys(skillId), "");
        if (!"1".equals(payload.path("schemaVersion").asText())) invalid("/schemaVersion", "Expected schema version 1");
        if (!payload.path("coverage").isObject()) invalid("/coverage", "Coverage is required");
        only(payload.path("coverage"), Set.of("processedBlockIds", "unprocessedBlockIds"), "/coverage");
        Set<String> processed = stringSet(payload.path("coverage").path("processedBlockIds"), "/coverage/processedBlockIds");
        Set<String> unprocessed = stringSet(payload.path("coverage").path("unprocessedBlockIds"), "/coverage/unprocessedBlockIds");
        if (!Collections.disjoint(processed, unprocessed) || !assigned.equals(union(processed, unprocessed)))
            fail("COVERAGE_INCOMPLETE", "/coverage", "Coverage must account for every assigned block exactly once");
        if (!read.containsAll(processed)) fail("BLOCK_NOT_READ", "/coverage/processedBlockIds", "A processed block has no server read receipt");
        if (!payload.path("warnings").isArray()) invalid("/warnings", "Warnings must be an array");
        for (int i = 0; i < payload.path("warnings").size(); i++) text(payload.path("warnings").get(i), "/warnings/" + i, 1000);
        switch (skillId) {
            case "bidding-tender-profile" -> validateProfile(payload, blocks);
            case "bidding-elimination-analysis" -> validateItems(payload.path("items"), blocks, "/items", true);
            case "bidding-requirement-analysis" -> validateItems(payload.path("requirements"), blocks, "/requirements", false);
            case "bidding-scoring-analysis" -> validateScoring(payload, blocks);
            default -> throw new IllegalStateException();
        }
    }

    /** Rejects model supplied approval state and unknown fields in outline candidates. */
    public void validateOutline(ObjectNode payload) {
        if(payload==null || !payload.path("schemaVersion").isTextual() || !"1".equals(payload.path("schemaVersion").asText()) || !payload.path("chapters").isArray())
            throw BiddingAccess.error(422,"OUTLINE_SCHEMA","Outline schema is invalid");
        only(payload,Set.of("schemaVersion","chapters","unmappedItems","warnings"),"");
        for(String required:List.of("schemaVersion","chapters","unmappedItems","warnings"))
            if(!payload.has(required)) throw BiddingAccess.error(422,"OUTLINE_SCHEMA","Missing outline field: "+required);
        if(payload.path("chapters").isEmpty()) throw BiddingAccess.error(422,"OUTLINE_SCHEMA","Outline must contain at least one chapter");
        if(!payload.path("unmappedItems").isArray()||!payload.path("warnings").isArray())
            throw BiddingAccess.error(422,"OUTLINE_SCHEMA","unmappedItems and warnings must be arrays");
        for(int i=0;i<payload.path("unmappedItems").size();i++) if(!payload.path("unmappedItems").get(i).isObject())
            throw BiddingAccess.error(422,"OUTLINE_SCHEMA","/unmappedItems/"+i+" must be an object");
        for(int i=0;i<payload.path("warnings").size();i++) if(!payload.path("warnings").get(i).isTextual())
            throw BiddingAccess.error(422,"OUTLINE_SCHEMA","/warnings/"+i+" must be a string");
        for(int i=0;i<payload.path("chapters").size();i++) {
            JsonNode chapter=payload.path("chapters").get(i); String at="/chapters/"+i;
            if(!chapter.isObject()) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+" must be an object");
            only(chapter,Set.of("id","parentId","order","title","instructions","mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs"),at);
            for(String required:List.of("id","parentId","order","title","instructions","mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs"))
                if(!chapter.has(required)) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+" is missing "+required);
            JsonNode id=chapter.path("id"),parent=chapter.path("parentId"),order=chapter.path("order"),title=chapter.path("title"),instructions=chapter.path("instructions");
            if(!id.isTextual()||id.asText().isBlank()||id.asText().length()>128) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/id must be a nonempty string up to 128 characters");
            if(!parent.isNull()&&(!parent.isTextual()||parent.asText().isBlank()||parent.asText().length()>128)) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/parentId must be null or a nonempty string up to 128 characters");
            if(!order.isIntegralNumber()||order.asInt()<0) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/order must be a nonnegative integer");
            if(!title.isTextual()||title.asText().isBlank()||title.asText().length()>500) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/title must be a nonempty string up to 500 characters");
            if(!instructions.isTextual()||instructions.asText().length()>4000) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/instructions must be a string up to 4000 characters");
            for(String field:List.of("mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs"))
                if(!chapter.path(field).isArray()) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/"+field+" must be an array");
            for(String field:List.of("mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs")) {
                Set<String> ids=new java.util.HashSet<>(); JsonNode values=chapter.path(field);
                for(int j=0;j<values.size();j++) { JsonNode value=values.get(j); if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>128||!ids.add(value.asText())) throw BiddingAccess.error(422,"OUTLINE_SCHEMA",at+"/"+field+"/"+j+" must be a unique nonempty string up to 128 characters"); }
            }
        }
    }

    private static Set<String> rootKeys(String skill) {
        Set<String> keys = new HashSet<>(Set.of("schemaVersion", "coverage", "warnings"));
        keys.add(switch (skill) {
            case "bidding-tender-profile" -> "basicInfo";
            case "bidding-elimination-analysis" -> "items";
            case "bidding-requirement-analysis" -> "requirements";
            case "bidding-scoring-analysis" -> "criteria";
            default -> "";
        });
        if (skill.equals("bidding-tender-profile")) keys.addAll(Set.of("deadlines", "deliveryConditions", "mandatoryOutline", "formatRequirements", "unknowns"));
        if (skill.equals("bidding-scoring-analysis")) keys.add("totalChecks");
        return keys;
    }

    private void validateProfile(JsonNode p, Map<String, JsonNode> blocks) {
        JsonNode basic = requiredObject(p, "basicInfo", "");
        only(basic, Set.of("project", "tenderer", "lot"), "/basicInfo");
        for (String field : List.of("project", "tenderer", "lot")) nullableText(basic.get(field), "/basicInfo/" + field, 300);
        validateUnknowns(p.path("unknowns"), "/unknowns");
        for (String field : List.of("project", "tenderer", "lot")) if (basic.path(field).isNull()
                && !hasUnknown(p.path("unknowns"), field)) invalid("/unknowns", "Missing basicInfo values need an unknown reason: " + field);
        validateFacts(p.path("deadlines"), blocks, "/deadlines");
        validateFacts(p.path("deliveryConditions"), blocks, "/deliveryConditions");
        validateFacts(p.path("mandatoryOutline"), blocks, "/mandatoryOutline");
        validateFacts(p.path("formatRequirements"), blocks, "/formatRequirements");
    }

    private void validateFacts(JsonNode facts, Map<String, JsonNode> blocks, String path) {
        if (!facts.isArray()) invalid(path, "Expected an array");
        for (int i = 0; i < facts.size(); i++) {
            String at = path + "/" + i; JsonNode fact = facts.get(i);
            only(fact, Set.of("name", "value", "reason", "evidenceRefs"), at);
            text(fact, "name", at, 300); nullableText(fact.get("value"), at + "/value", 1000);
            nullableText(fact.get("reason"), at + "/reason", 1000);
            if (fact.path("value").isNull() && fact.path("reason").asText().isBlank()) invalid(at + "/reason", "Unknown values need a reason");
            validateEvidence(fact.path("evidenceRefs"), blocks, at + "/evidenceRefs");
        }
    }

    private void validateItems(JsonNode items, Map<String, JsonNode> blocks, String path, boolean elimination) {
        if (!items.isArray()) invalid(path, "Expected an array");
        for (int i = 0; i < items.size(); i++) {
            String at = path + "/" + i; JsonNode item = items.get(i);
            Set<String> fields = elimination ? Set.of("id", "text", "scope", "trigger", "evidenceRefs", "unknowns")
                    : Set.of("id", "text", "category", "constraints", "acceptance", "evidenceRefs", "unknowns");
            only(item, fields, at); text(item, "id", at, 128); text(item, "text", at, 4000);
            if (elimination) { text(item, "scope", at, 1000); nullableText(item.get("trigger"), at + "/trigger", 1000); }
            else {
                if (!Set.of("TECHNICAL", "COMMERCIAL").contains(item.path("category").asText())) invalid(at + "/category", "Invalid category");
                stringArray(item.path("constraints"), at + "/constraints", 64, 1000);
                nullableText(item.get("acceptance"), at + "/acceptance", 2000);
            }
            validateEvidence(item.path("evidenceRefs"), blocks, at + "/evidenceRefs");
            validateUnknowns(item.path("unknowns"), at + "/unknowns");
        }
    }

    private void validateScoring(JsonNode p, Map<String, JsonNode> blocks) {
        JsonNode criteria = p.path("criteria");
        if (!criteria.isArray()) invalid("/criteria", "Expected an array");
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (int i = 0; i < criteria.size(); i++) {
            String at = "/criteria/" + i; JsonNode criterion = criteria.get(i);
            only(criterion, Set.of("id", "parentId", "title", "score", "unit", "rule", "requiredProof", "evidenceRefs"), at);
            String id = text(criterion, "id", at, 128); nullableText(criterion.get("parentId"), at + "/parentId", 128);
            if (byId.putIfAbsent(id, criterion) != null) invalid(at + "/id", "Criterion IDs must be unique");
            text(criterion, "title", at, 500); nullableText(criterion.get("unit"), at + "/unit", 100);
            nullableText(criterion.get("rule"), at + "/rule", 2000); nullableText(criterion.get("requiredProof"), at + "/requiredProof", 1000);
            JsonNode score = criterion.get("score");
            if (score != null && !score.isNull()) {
                if (!score.isTextual()) invalid(at + "/score", "Score must be a decimal string or null");
                try { if (new BigDecimal(score.asText()).abs().compareTo(new BigDecimal("1000000000")) > 0) throw new NumberFormatException(); }
                catch (NumberFormatException e) { invalid(at + "/score", "Score must be a finite decimal"); }
            }
            validateEvidence(criterion.path("evidenceRefs"), blocks, at + "/evidenceRefs");
        }
        validateCriterionHierarchy(byId);
        JsonNode checks = p.path("totalChecks");
        if (!checks.isArray()) invalid("/totalChecks", "Expected an array");
        for (int i = 0; i < checks.size(); i++) {
            String at = "/totalChecks/" + i; JsonNode check = checks.get(i);
            only(check, Set.of("name", "criterionIds", "statedTotal", "calculatedTotal", "difference", "evidenceRefs"), at);
            text(check, "name", at, 300);
            JsonNode selected = check.path("criterionIds");
            if (!selected.isArray() || selected.isEmpty()) invalid(at + "/criterionIds", "At least one applicable criterion ID is required");
            Set<String> selectedIds = new LinkedHashSet<>();
            for (int j = 0; j < selected.size(); j++) {
                String id = text(selected.get(j), at + "/criterionIds/" + j, 128);
                if (!selectedIds.add(id)) fail("TOTAL_CHECK_CRITERION_DUPLICATE", at + "/criterionIds/" + j, "Criterion ID is repeated");
                if (!byId.containsKey(id)) fail("TOTAL_CHECK_CRITERION_UNKNOWN", at + "/criterionIds/" + j, "Criterion ID is not declared in criteria");
            }
            for (String ancestor : selectedIds) for (String descendant : selectedIds)
                if (!ancestor.equals(descendant) && isAncestor(ancestor, descendant, byId))
                    fail("TOTAL_CHECK_CRITERIA_OVERLAP", at + "/criterionIds", "A parent and its descendant cannot both contribute to one total");
            for (String field : List.of("statedTotal", "calculatedTotal", "difference")) decimalOrNull(check.get(field), at + "/" + field);
            BigDecimal stated = decimal(check.get("statedTotal"));
            BigDecimal calculated = decimal(check.get("calculatedTotal"));
            BigDecimal difference = decimal(check.get("difference"));
            if (stated != null && calculated != null) {
                BigDecimal expectedDifference = stated.subtract(calculated);
                if (difference == null || difference.compareTo(expectedDifference) != 0)
                    fail("TOTAL_CHECK_MISMATCH", at + "/difference", "Difference must equal statedTotal minus calculatedTotal");
            } else if (difference != null) {
                fail("TOTAL_CHECK_MISMATCH", at + "/difference", "Difference must be unknown when either total is unknown");
            }
            if (calculated != null) {
                BigDecimal applicable = applicableCriteriaTotal(selectedIds, byId);
                if (applicable == null)
                    fail("TOTAL_CHECK_CRITERIA_UNKNOWN", at + "/calculatedTotal", "Calculated total cannot be verified while an applicable criterion score is unknown");
                if (calculated.compareTo(applicable) != 0)
                    fail("TOTAL_CHECK_CRITERIA_MISMATCH", at + "/calculatedTotal", "Calculated total does not equal the sum of its declared applicable criteria");
            } else if (applicableCriteriaTotal(selectedIds, byId) != null) {
                fail("TOTAL_CHECK_CALCULATED_REQUIRED", at + "/calculatedTotal", "Calculated total is required when every applicable criterion score is known");
            }
            validateEvidence(check.path("evidenceRefs"), blocks, at + "/evidenceRefs");
        }
    }

    private void validateCriterionHierarchy(Map<String, JsonNode> byId) {
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            JsonNode parent = entry.getValue().path("parentId");
            if (parent.isTextual() && !byId.containsKey(parent.asText()))
                invalid("/criteria", "Unknown parent criterion ID: " + parent.asText());
            Set<String> path = new HashSet<>(); String current = entry.getKey();
            while (current != null) {
                if (!path.add(current)) invalid("/criteria", "Criterion hierarchy contains a cycle");
                JsonNode node = byId.get(current);
                JsonNode parentId = node == null ? null : node.path("parentId");
                current = parentId != null && parentId.isTextual() ? parentId.asText() : null;
            }
        }
    }

    private boolean isAncestor(String ancestor, String descendant, Map<String, JsonNode> byId) {
        JsonNode parentId = byId.get(descendant).path("parentId");
        while (parentId.isTextual()) {
            if (ancestor.equals(parentId.asText())) return true;
            parentId = byId.get(parentId.asText()).path("parentId");
        }
        return false;
    }

    private BigDecimal applicableCriteriaTotal(Set<String> selectedIds, Map<String, JsonNode> byId) {
        if (selectedIds.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (String id : selectedIds) {
            BigDecimal score = decimal(byId.get(id).get("score"));
            if (score == null) return null;
            sum = sum.add(score);
        }
        return sum;
    }

    private BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException e) { return null; }
    }

    private void validateEvidence(JsonNode refs, Map<String, JsonNode> blocks, String path) {
        if (!refs.isArray() || refs.isEmpty()) invalid(path, "At least one evidence reference is required");
        for (int i = 0; i < refs.size(); i++) {
            String at = path + "/" + i; JsonNode ref = refs.get(i);
            only(ref, Set.of("sourceId", "version", "blockId", "quote"), at);
            String source = text(ref, "sourceId", at, 128), blockId = text(ref, "blockId", at, 128), quote = text(ref, "quote", at, 2000);
            if (!ref.path("version").canConvertToLong() || ref.path("version").asLong() < 1) invalid(at + "/version", "Expected positive source version");
            JsonNode block = blocks.get(blockId);
            if (block == null || !source.equals(block.path("sourceId").asText()) || ref.path("version").asLong() != block.path("version").asLong())
                fail("EVIDENCE_OUT_OF_SCOPE", at, "Evidence is outside the assigned source blocks");
            if (!block.path("text").asText().contains(quote)) fail("EVIDENCE_QUOTE_INVALID", at + "/quote", "Quote must be an exact substring of the source block");
        }
    }

    private void validateUnknowns(JsonNode unknowns, String path) {
        if (!unknowns.isArray()) invalid(path, "Expected an array");
        for (int i = 0; i < unknowns.size(); i++) {
            String at = path + "/" + i; JsonNode item = unknowns.get(i);
            only(item, Set.of("field", "reason"), at); text(item, "field", at, 300); text(item, "reason", at, 1000);
        }
    }

    private static boolean hasUnknown(JsonNode unknowns, String field) {
        String qualified = "basicInfo." + field;
        for (JsonNode item : unknowns) {
            String candidate = item.path("field").asText();
            if (field.equals(candidate) || qualified.equals(candidate)) return true;
        }
        return false;
    }

    private static JsonNode requiredObject(JsonNode node, String field, String path) { JsonNode value = node.get(field); if (value == null || !value.isObject()) invalid(path + "/" + field, "Expected an object"); return value; }
    private static String text(JsonNode object, String field, String path, int max) { JsonNode v = object.get(field); return text(v, path + "/" + field, max); }
    private static String text(JsonNode value, String path, int max) { if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().codePointCount(0, value.asText().length()) > max) invalid(path, "Expected non-empty text within the length limit"); return value.asText(); }
    private static void nullableText(JsonNode value, String path, int max) { if (value == null) invalid(path, "Field is required"); if (!value.isNull()) text(value, path, max); }
    private static void decimalOrNull(JsonNode value, String path) { if (value == null) invalid(path, "Field is required"); if (!value.isNull()) { if (!value.isTextual()) invalid(path, "Decimal must be text or null"); try { new BigDecimal(value.asText()); } catch (NumberFormatException e) { invalid(path, "Invalid decimal"); } } }
    private static Set<String> stringSet(JsonNode values, String path) { if (!values.isArray()) invalid(path, "Expected an array"); Set<String> result = new LinkedHashSet<>(); for (int i=0;i<values.size();i++) { String item=text(values.get(i),path+"/"+i,128); if(!result.add(item)) invalid(path+"/"+i,"Duplicate value"); } return result; }
    private static void stringArray(JsonNode values, String path, int maxItems, int maxLength) { if(!values.isArray() || values.size()>maxItems) invalid(path,"Expected bounded array"); for(int i=0;i<values.size();i++) text(values.get(i),path+"/"+i,maxLength); }
    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> all=new HashSet<>(a); all.addAll(b); return all; }
    private static void only(JsonNode node, Set<String> allowed, String path) { if(node==null || !node.isObject()) invalid(path.isEmpty()?"/":path,"Expected an object"); node.fieldNames().forEachRemaining(name->{ if(!allowed.contains(name)) invalid(path+"/"+name,"Unknown field"); }); }
    private static void invalid(String path, String reason) { fail("OUTPUT_SCHEMA_INVALID", path.isEmpty()?"/":path, reason); }
    private static void fail(String code, String path, String reason) { throw new BiddingApiException(422, code, path + ": " + reason); }
}
