package vip.mate.presales;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.web.SemanticApiException;
import static org.junit.jupiter.api.Assertions.*;
class PresalesModelAdapterTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode context() throws Exception{return (ObjectNode)json.readTree("{\"sources\":[{\"sourceRef\":\"raw-1\"}]}");}
 ObjectNode result() throws Exception{return (ObjectNode)json.readTree("{\"schemaVersion\":1,\"needsHumanReview\":true,\"items\":[{\"title\":\"预算\",\"text\":\"预算待确认\",\"originKind\":\"ASSUMPTION\",\"sourceRefs\":[]}],\"unknowns\":[\"预算未知\"],\"assumptions\":[]}");}
 @Test void unsupportedSourceCannotBecomeCustomerRequirement() throws Exception{var r=result();((ObjectNode)r.path("items").get(0)).put("originKind","CUSTOMER_SOURCE");assertEquals("EVIDENCE_REQUIRED",assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,context())).code());}
 @Test void sourceOutsideTaskRejected() throws Exception{var r=result();((ObjectNode)r.path("items").get(0)).putArray("sourceRefs").add("other-project");assertEquals("INVALID_SOURCE_REFERENCE",assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,context())).code());}
 @Test void modelCannotApproveOwnOutput() throws Exception{var r=result();r.put("approved",true);assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,context()));}
 @Test void explicitAssumptionIsOnlyDraft() throws Exception{assertDoesNotThrow(()->PresalesModelAdapter.validate(result(),context()));}
}
