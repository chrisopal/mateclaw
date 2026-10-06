package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.support.SemanticHttpFixture;

@Import({
    PresalesAccess.class,
    PresalesService.class,
    vip.mate.presales.repository.PresalesRenderTaskRepository.class,
    vip.mate.presales.repository.PresalesProjectRepository.class,
    vip.mate.presales.repository.PresalesArtifactRepository.class,
    PresalesSourceAuthorization.class,
    vip.mate.wiki.service.WikiSourceReadService.class,
    vip.mate.wiki.repository.WikiSourceReadRepository.class,
    vip.mate.semantic.source.SourceGovernanceReadService.class,
    vip.mate.semantic.source.repository.SourceGovernanceReadRepository.class,
    PresalesController.class,
    PresalesSourceQueryService.class,
    PresalesProjectQueryService.class,
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesProjectListingContractTest extends SemanticHttpFixture {
    private com.fasterxml.jackson.databind.node.ObjectNode row(
            String id, String name, String customer, String status, String owner, String stage)
            throws Exception {
        var p =
                json.createObjectNode()
                        .put("id", id)
                        .put("workspaceId", workspace)
                        .put("version", 1)
                        .put("name", name)
                        .put("customer", customer)
                        .put("status", status)
                        .put("ownerId", owner);
        for (String collection :
                java.util.List.of(
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
                        "contextCards")) p.putArray(collection);
        switch (stage) {
            case "RELEASE" -> p.withArray("releases").addObject().put("id", "release");
            case "SOLUTION" -> p.withArray("solutions").addObject().put("version", 7);
            case "BASELINED" -> p.withArray("baselines").addObject().put("id", "baseline");
            case "REQUIREMENTS" -> p.withArray("requirements").addObject().put("id", "requirement");
            default -> {}
        }
        p.putObject("legacyExtension").put("value", "原始扩展 Ω");
        jdbc.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                id,
                workspace,
                1,
                name,
                status,
                json.writeValueAsString(p));
        return p;
    }

    private com.fasterxml.jackson.databind.JsonNode listing(
            String query, String role, String scope, int status) throws Exception {
        backfill();
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/presales/projects" + query)
                                        .header("Authorization", tokens.get(role))
                                        .header("X-Workspace-Id", scope))
                        .andReturn()
                        .getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void filtersBeforePagingAndKeepsOriginalOrderAndStringIds() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String first = "9007199254740993001",
                second = "9007199254740993002",
                third = "9007199254740993003";
        row(first, "A " + suffix, "Customer Ω", "ACTIVE", "owner-A", "SOLUTION");
        row(second, "B " + suffix, "Needle Customer", "ACTIVE", "owner-A", "BASELINED");
        row(third, "C " + suffix, "Customer Ω", "ARCHIVED", "owner-B", "RELEASE");
        var page = listing("?q=" + suffix + "&page=2&pageSize=1", "viewer", workspace, 200);
        assertEquals(3, page.path("total").asInt());
        assertEquals(2, page.path("page").asInt());
        assertEquals(1, page.path("pageSize").asInt());
        assertEquals(second, page.path("items").get(0).path("id").asText());
        assertTrue(page.path("items").get(0).path("id").isTextual());
        var filtered =
                listing(
                        "?q=" + suffix + "&status=ACTIVE&ownerId=owner-A&stage=BASELINED",
                        "viewer",
                        workspace,
                        200);
        assertEquals(1, filtered.path("total").asInt());
        assertEquals(second, filtered.path("items").get(0).path("id").asText());
        var customer = listing("?q=needle&ownerId=owner-A", "viewer", workspace, 200);
        assertEquals(1, customer.path("total").asInt());
        assertEquals(second, customer.path("items").get(0).path("id").asText());
        var empty = listing("?q=" + suffix + "&page=5&pageSize=1", "viewer", workspace, 200);
        assertEquals(3, empty.path("total").asInt());
        assertTrue(empty.path("items").isEmpty());
        assertEquals(
                "ARCHIVED",
                listing("?q=" + suffix + "&stage=ARCHIVED", "viewer", workspace, 200)
                        .path("items")
                        .get(0)
                        .path("stage")
                        .asText());
    }

    @Test
    void stripsOnlyOriginalSourceCollectionsAndNeverRewritesHistoricalBody() throws Exception {
        String id = UUID.randomUUID().toString();
        var p = row(id, "Summary " + id, "Original", "ACTIVE", "owner", "SOLUTION");
        p.withArray("clarifications").addObject().put("status", "ANSWERED");
        p.withArray("clarifications").addObject().put("status", "OPEN");
        p.withArray("clarifications").addObject();
        p.withArray("solutions").addObject().put("version", 3);
        p.withArray("materials").addObject().put("text", "restricted material body");
        String original = json.writeValueAsString(p);
        jdbc.update("UPDATE mate_presales_project SET body_json=? WHERE id=?", original, id);
        var item = listing("?q=" + id, "viewer", workspace, 200).path("items").get(0);
        for (String collection :
                java.util.List.of(
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
                        "contextCards")) assertFalse(item.has(collection), collection);
        assertEquals(2, item.path("openClarificationCount").asInt());
        assertEquals(3, item.path("latestSolutionVersion").asInt());
        assertEquals(p.path("legacyExtension"), item.path("legacyExtension"));
        assertEquals("SOLUTION", item.path("stage").asText());
        assertEquals(
                original,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        id));
    }

    @Test
    void paginationErrorsAndWorkspaceAccessStayAtExistingEntry() throws Exception {
        for (String query : java.util.List.of("?page=0", "?pageSize=0", "?pageSize=101"))
            assertEquals(
                    "INVALID_REQUEST",
                    listing(query, "viewer", workspace, 400).path("code").asText());
        listing("?page=0", "member", otherWorkspace, 403);
        String id = UUID.randomUUID().toString();
        row(id, "Private " + id, "Private", "ACTIVE", "owner", "DISCOVERY");
        assertEquals(0, listing("?q=" + id, "owner", otherWorkspace, 200).path("total").asInt());
    }

    @Test
    void bodyFiltersRemainIndependentFromDatabaseOrderingColumns() throws Exception {
        String id = UUID.randomUUID().toString();
        var p = row(id, "A column", "Customer", "ACTIVE", "owner ", "DISCOVERY");
        p.put("name", "Body needle").put("status", "HISTORICAL");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                json.writeValueAsString(p),
                id);
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/presales/projects")
                        .param("q", "body needle")
                        .param("status", "HISTORICAL")
                        .param("ownerId", "owner ")
                        .header("Authorization", tokens.get("viewer"))
                        .header("X-Workspace-Id", workspace);
        backfill();
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var data = json.readTree(response.getContentAsString()).path("data");
        assertEquals(1, data.path("total").asLong());
        assertEquals(id, data.path("items").get(0).path("id").asText());
        assertEquals("Body needle", data.path("items").get(0).path("name").asText());
        var mismatch =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/presales/projects")
                        .param("q", "body needle")
                        .param("ownerId", "owner")
                        .header("Authorization", tokens.get("viewer"))
                        .header("X-Workspace-Id", workspace);
        var rejected = mvc.perform(mismatch).andReturn().getResponse();
        assertEquals(200, rejected.getStatus());
        assertEquals(
                0,
                json.readTree(rejected.getContentAsString()).path("data").path("total").asLong());
    }

    private void backfill() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            new db.migration.h2.V218__backfill_presales_listing_projection()
                    .migrate(
                            new org.flywaydb.core.api.migration.Context() {
                                public java.sql.Connection getConnection() {
                                    return connection;
                                }

                                public org.flywaydb.core.api.configuration.Configuration
                                        getConfiguration() {
                                    return null;
                                }
                            });
        }
    }
}
