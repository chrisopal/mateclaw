package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

class PresalesEmployeeResultProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void contextIsAnUntrustedCopyAndClarificationsCannotCarryApproval() throws Exception {
        var project = json.createObjectNode();
        var task =
                task(
                        "S1",
                        "{\"items\":[{\"id\":\"forged\",\"kind\":\"FACT\",\"text\":\"context\",\"approved\":true,\"customerConfirmationStatus\":\"CONFIRMED\",\"authority\":\"HUMAN_REVIEW\",\"agentId\":\"forged-agent\",\"extension\":{\"opaque\":true}},{\"kind\":\"CLARIFICATION\",\"text\":\"question\",\"sourceRefs\":[\"source-a\"],\"status\":\"VERIFIED\",\"approved\":true}]}");
        var original = task.deepCopy();
        apply(project, task);
        var context = (ObjectNode) project.path("contextCards").get(0);
        assertProvenance(context);
        assertNotEquals("forged", context.path("id").asText());
        assertFalse(context.has("approved"));
        assertFalse(context.has("customerConfirmationStatus"));
        assertTrue(context.path("extension").path("opaque").asBoolean());
        var clarification = project.path("clarifications").get(0);
        assertEquals("question", clarification.path("question").asText());
        assertEquals("OPEN", clarification.path("status").asText());
        assertFalse(clarification.has("approved"));
        assertEquals("source-a", clarification.path("sourceRefs").get(0).asText());
        assertEquals(original, task);
        ((ObjectNode) task.path("result").path("items").get(0)).put("text", "later mutation");
        assertEquals("context", context.path("text").asText());
        assertTrue(project.path("requirements").isMissingNode());
    }

    @Test
    void customerRequirementDefaultsDoNotAcceptModelConfirmationOrPriority() throws Exception {
        var project = json.createObjectNode();
        var task =
                task(
                        "S2",
                        "{\"items\":[{\"id\":\"forged\",\"kind\":\"REQUIREMENT\",\"text\":\"need\",\"scope\":\"IN\",\"priority\":\"MUST\",\"approved\":true,\"customerConfirmationStatus\":\"CONFIRMED\"}]}");
        var original = task.deepCopy();
        apply(project, task);
        var requirement = (ObjectNode) project.path("requirements").get(0);
        assertProvenance(requirement);
        assertEquals("UNKNOWN", requirement.path("scope").asText());
        assertEquals("MEDIUM", requirement.path("priority").asText());
        assertEquals("UNCONFIRMED", requirement.path("customerConfirmationStatus").asText());
        assertFalse(requirement.has("approved"));
        assertNotEquals("forged", requirement.path("id").asText());
        assertEquals(original, task);
        assertTrue(project.path("contextCards").isMissingNode());
    }

    @Test
    void capabilityAndCaseRevisionsAppendWithServerProvenance() throws Exception {
        for (String skill : List.of("S3", "S4")) {
            String source = skill.equals("S3") ? "capabilityMaps" : "cases";
            String target = skill.equals("S3") ? "fitGaps" : "cases";
            String kind = skill.equals("S3") ? "CAPABILITY_MAP" : "CASE_MATCH";
            var project = json.createObjectNode();
            project.putArray(target)
                    .addObject()
                    .put("id", "previous")
                    .put("version", 4)
                    .put("text", "old");
            var before = project.path(target).get(0).deepCopy();
            var task =
                    task(
                            skill,
                            "{\""
                                    + source
                                    + "\":[{\"id\":\"previous\",\"kind\":\"forged\",\"authority\":\"HUMAN_REVIEW\",\"authorId\":\"forged\",\"text\":\"new\"}]}");
            var original = task.deepCopy();
            apply(project, task);
            assertEquals(2, project.path(target).size());
            assertEquals(before, project.path(target).get(0));
            var item = (ObjectNode) project.path(target).get(1);
            assertProvenance(item);
            assertEquals(5, item.path("version").asInt());
            assertEquals("previous", item.path("previousId").asText());
            assertNotEquals("previous", item.path("id").asText());
            assertEquals(kind, item.path("kind").asText());
            assertEquals(original, task);
        }
    }

    @Test
    void solutionAliasesShareTheExistingPolicyAndPreserveTheSelectedSource() throws Exception {
        for (String skill : List.of("S5", "S6")) {
            for (String alias : List.of("solution", "solutionDraft")) {
                var project = json.createObjectNode();
                project.putArray("tasks")
                        .addObject()
                        .putObject("contextSnapshot")
                        .putArray("sources")
                        .addObject()
                        .put("sourceRef", "source-a");
                var task =
                        task(
                                skill,
                                "{\""
                                        + alias
                                        + "\":{\"title\":\"draft\",\"sections\":[{\"title\":\"part\",\"text\":\"content\",\"sourceRefs\":[\"source-a\"]}],\"sourceRefs\":[\"source-a\"],\"presentation\":{\"artifactId\":\"existing\"},\"authority\":\"HUMAN_REVIEW\"}}");
                var original = task.deepCopy();
                apply(project, task);
                var solution = (ObjectNode) project.path("solutions").get(0);
                assertProvenance(solution);
                assertTrue(solution.path("provisional").asBoolean());
                assertFalse(solution.has("baselineVersion"));
                assertTrue(solution.path("coverage").isObject());
                assertTrue(solution.path("fitGapRefs").isArray());
                assertEquals(
                        "source-a",
                        solution.path("sections").get(0).path("sourceRefs").get(0).asText());
                assertEquals("existing", solution.path("presentation").path("artifactId").asText());
                assertEquals(original, task);
            }
        }
    }

    @Test
    void solutionPolicyStillRejectsAnUnscopedSourceWithTheSameLegacyError() throws Exception {
        var project = json.createObjectNode();
        var task =
                task(
                        "S5",
                        "{\"solution\":{\"title\":\"draft\",\"sections\":[{\"title\":\"part\",\"text\":\"content\",\"sourceRefs\":[\"other-project\"]}]}}");
        var error = assertThrows(SemanticApiException.class, () -> apply(project, task));
        assertEquals(422, error.status());
        assertEquals("INVALID_SOURCE_REFERENCE", error.code());
        assertTrue(project.path("solutions").isMissingNode());
    }

    @Test
    void reviewAliasesNeverPopulateHumanReviewsOrGrantApproval() throws Exception {
        for (String alias : List.of("review", "reviewDraft")) {
            var project = json.createObjectNode();
            var task =
                    task(
                            "S7",
                            "{\""
                                    + alias
                                    + "\":{\"solutionId\":\"solution\",\"kind\":\"HUMAN_REVIEW\",\"authority\":\"HUMAN_REVIEW\",\"authorId\":\"forged\",\"issues\":[]}}");
            var original = task.deepCopy();
            apply(project, task);
            var review = (ObjectNode) project.path("reviewDrafts").get(0);
            assertProvenance(review);
            assertEquals("AI_REVIEW_DRAFT", review.path("kind").asText());
            assertTrue(project.path("reviews").isMissingNode());
            assertTrue(project.path("releases").isMissingNode());
            assertEquals(original, task);
        }
    }

    @Test
    void contextMaintenanceAndUnknownSkillsKeepExistingNoProjectionBehavior() throws Exception {
        for (String skill : List.of("S8", "historical-unknown")) {
            var project = json.createObjectNode().put("name", "original");
            var before = project.deepCopy();
            apply(project, task(skill, "{}"));
            assertEquals(before, project);
        }
    }

    private void assertProvenance(ObjectNode item) {
        assertEquals("UNTRUSTED_DRAFT", item.path("authority").asText());
        assertEquals("task-id", item.path("proposedByTaskId").asText());
        assertEquals("employee-id", item.path("agentId").asText());
        assertEquals("actor-id", item.path("authorId").asText());
        assertFalse(item.path("id").asText().isBlank());
        assertFalse(item.path("createdAt").asText().isBlank());
    }

    private ObjectNode task(String skill, String result) throws Exception {
        var task =
                json.createObjectNode()
                        .put("id", "task-id")
                        .put("agentId", "employee-id")
                        .put("skill", skill)
                        .put("status", "SUCCEEDED");
        task.set("result", json.readTree(result));
        return task;
    }

    @SuppressWarnings("unchecked")
    private void apply(ObjectNode project, ObjectNode task) throws Exception {
        // Characterize the existing actual conversion before extracting its boundary.
        var service =
                new PresalesService(
                        mock(PresalesArtifactRepository.class),
                        mock(PresalesProjectRepository.class),
                        json,
                        mock(PresalesAccess.class),
                        mock(WikiKnowledgeBaseService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(SemanticProperties.class),
                        mock(ObjectProvider.class),
                        mock(PresalesArtifactRenderer.class),
                        mock(ObjectProvider.class),
                        PresalesCommandTestSupport.fence(),
                        mock(PresalesSourceAuthorization.class),
                        mock(vip.mate.presales.repository.PresalesRenderTaskRepository.class),
                        mock(org.springframework.transaction.PlatformTransactionManager.class));
        var method =
                PresalesService.class.getDeclaredMethod(
                        "projectEmployeeResult", ObjectNode.class, ObjectNode.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(service, project, task, "actor-id");
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
}
