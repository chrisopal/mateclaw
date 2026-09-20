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
 @Test void capabilityMapRequiresScopedRequirementAndEvidence() throws Exception {
  var c=(ObjectNode)json.readTree("{\"skill\":\"S3\",\"sources\":[{\"sourceRef\":\"raw-1\"}],\"requirements\":[{\"id\":\"req-1\"}]}" );
  var r=(ObjectNode)json.readTree("{\"schemaVersion\":1,\"needsHumanReview\":true,\"capabilityMaps\":[{\"requirementId\":\"req-1\",\"status\":\"FIT\",\"productVersion\":\"v1\",\"reason\":\"supported\",\"sourceRefs\":[\"raw-1\"]}],\"unknowns\":[],\"assumptions\":[]}");
  assertDoesNotThrow(()->PresalesModelAdapter.validate(r,c,"S3"));
  ((ObjectNode)r.withArray("capabilityMaps").get(0)).putArray("sourceRefs").add("foreign");
  assertEquals("INVALID_SOURCE_REFERENCE",assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,c,"S3")).code());
 }
 @Test void solutionDraftRequiresBaselineLinkAndScopedSectionReferences() throws Exception {
  var c=(ObjectNode)json.readTree("{\"skill\":\"S5\",\"sources\":[{\"sourceRef\":\"raw-1\"}],\"requirements\":[{\"id\":\"req-1\"}],\"baselines\":[{\"id\":\"base-1\"}]}");
  var r=(ObjectNode)json.readTree("{\"schemaVersion\":1,\"needsHumanReview\":true,\"solution\":{\"title\":\"Draft\",\"baselineId\":\"base-1\",\"sections\":[{\"title\":\"Scope\",\"text\":\"Draft\",\"requirementRefs\":[\"req-1\"],\"sourceRefs\":[\"raw-1\"]}]},\"unknowns\":[],\"assumptions\":[]}");
  assertDoesNotThrow(()->PresalesModelAdapter.validate(r,c,"S5"));
  ((ObjectNode)r.path("solution").path("sections").get(0)).putArray("requirementRefs").add("foreign");
  assertEquals("INVALID_REQUIREMENT_REFERENCE",assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,c,"S5")).code());
 }
 @Test void aiReviewIsDraftAndCannotClaimAuthority() throws Exception {
  var c=(ObjectNode)json.readTree("{\"skill\":\"S7\",\"sources\":[{\"sourceRef\":\"raw-1\"}],\"solutions\":[{\"id\":\"sol-1\"}]}" );
  var r=(ObjectNode)json.readTree("{\"schemaVersion\":1,\"needsHumanReview\":true,\"review\":{\"solutionId\":\"sol-1\",\"summary\":\"Needs review\",\"sourceRefs\":[\"raw-1\"],\"issues\":[{\"severity\":\"BLOCKER\",\"status\":\"OPEN\",\"description\":\"Check scope\",\"sourceRefs\":[\"raw-1\"]}]},\"unknowns\":[],\"assumptions\":[]}");
  assertDoesNotThrow(()->PresalesModelAdapter.validate(r,c,"S7"));
  r.put("published",true);
  assertEquals("MODEL_AUTHORITY_REJECTED",assertThrows(SemanticApiException.class,()->PresalesModelAdapter.validate(r,c,"S7")).code());
 }
 @Test void provisionalSolutionHasNoBaselineVersionRequirement() throws Exception {
  var c=context();c.put("skill","S5");c.putArray("requirements");c.putArray("baselines");
  var r=result();var solution=r.putObject("solution").put("title","Draft").put("baselineId","").put("baselineVersion",0);
  solution.putArray("sourceRefs");var section=solution.putArray("sections").addObject().put("title","Scope").put("text","Unknown budget");
  section.putArray("requirementRefs");section.putArray("sourceRefs");
  assertDoesNotThrow(()->PresalesModelAdapter.validate(r,c));
 }

  @org.junit.jupiter.api.Test
  void presentationCopiesServerSnapshotInsteadOfRegeneratingFacts() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var context = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("{\"targetSolutionId\":\"s1\",\"solutions\":[{\"id\":\"s1\",\"title\":\"original\",\"baselineId\":\"\",\"sections\":[{\"title\":\"scope\",\"text\":\"unknown budget\"}]}]}");
    var result = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("{\"solution\":{\"sourceSolutionId\":\"s1\",\"title\":\"changed\",\"presentation\":{\"slides\":[]}}}");
    PresalesModelAdapter.bindPresentationSource(result, context);
    org.junit.jupiter.api.Assertions.assertEquals(context.path("solutions").get(0).path("sections"), result.path("solution").path("sections"));
    org.junit.jupiter.api.Assertions.assertEquals("original", result.path("solution").path("title").asText());
    ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("solution")).put("sourceSolutionId", "foreign");
    org.junit.jupiter.api.Assertions.assertThrows(vip.mate.semantic.web.SemanticApiException.class, () -> PresalesModelAdapter.bindPresentationSource(result, context));
  }
}
