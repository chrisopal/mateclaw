package vip.mate.presales;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vip.mate.semantic.web.SemanticApiException;
import java.util.*;
/** Validates untrusted employee output before draft persistence. */
public final class PresalesModelAdapter {
    static void validate(ObjectNode result,ObjectNode context){
        if(result.path("schemaVersion").asInt()!=1 || !result.path("needsHumanReview").asBoolean()
                || !result.path("items").isArray() || result.path("items").size()>100
                || !result.path("unknowns").isArray() || !result.path("assumptions").isArray())throw error(422,"MODEL_FORMAT");
        Set<String> ids=new HashSet<>(); context.path("sources").forEach(s -> ids.add(s.path("sourceRef").asText()));
        for(var item:result.path("items")){
            if(item.has("kind") && !Set.of("CLARIFICATION","WORK_ITEM").contains(item.path("kind").asText()))throw error(422,"MODEL_FORMAT");
            if(!item.isObject() || item.path("title").asText().isBlank() || item.path("text").asText().isBlank()
                    || !Set.of("CUSTOMER_SOURCE","PRODUCT_SOURCE","INTERNAL_JUDGMENT","ASSUMPTION","AI_SUGGESTION").contains(item.path("originKind").asText())
                    || !item.path("sourceRefs").isArray())throw error(422,"MODEL_FORMAT");
            for(var ref:item.path("sourceRefs"))if(!ref.isTextual()||!ids.contains(ref.asText()))throw error(422,"INVALID_SOURCE_REFERENCE");
            if(Set.of("CUSTOMER_SOURCE","PRODUCT_SOURCE").contains(item.path("originKind").asText()) && item.path("sourceRefs").isEmpty())throw error(422,"EVIDENCE_REQUIRED");
        }
        if(result.path("approved").asBoolean()||result.path("published").asBoolean())throw error(422,"MODEL_AUTHORITY_REJECTED");
    }
    static SemanticApiException error(int status,String code){return new SemanticApiException(status,code,code);}
}
