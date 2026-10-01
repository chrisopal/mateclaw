package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.support.SemanticHttpFixture;

@Import({
    PresalesAccess.class,
    PresalesService.class,
    PresalesController.class,
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesIntegrationTest extends SemanticHttpFixture {
    @org.springframework.beans.factory.annotation.Autowired
    vip.mate.agent.repository.AgentMapper employeeMapper;

    private JsonNode api(
            String method, String path, String role, String scope, Object body, int status)
            throws Exception {
        var r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .contentType("application/json");
        if (role != null) r.header("Authorization", tokens.get(role));
        if (scope != null) r.header("X-Workspace-Id", scope);
        if (body != null) r.content(json.writeValueAsString(body));
        var response = mvc.perform(r).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return response.getContentAsString().isBlank()
                ? json.nullNode()
                : json.readTree(response.getContentAsString()).path("data");
    }

    private JsonNode project() throws Exception {
        return api(
                "POST",
                "/projects",
                "member",
                workspace,
                Map.of(
                        "name",
                        "智能制造",
                        "customer",
                        "样例客户",
                        "expectedVersion",
                        0,
                        "operationId",
                        UUID.randomUUID().toString()),
                200);
    }

    private JsonNode cmd(JsonNode p, String action, Map<String, ?> payload, String role, int status)
            throws Exception {
        return api(
                "POST",
                "/projects/" + p.path("id").asText() + "/commands",
                role,
                workspace,
                Map.of(
                        "expectedVersion",
                        p.path("version").asInt(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "action",
                        action,
                        "payload",
                        payload),
                status);
    }

    @Test
    void clarificationRequiresTraceableAnswerAndScopedReferences() throws Exception {
        var p = project();
        cmd(
                p,
                "SAVE_CLARIFICATION",
                Map.of("question", "Which lines?", "status", "ANSWERED", "answer", "Two"),
                "member",
                400);
        cmd(
                p,
                "SAVE_CLARIFICATION",
                Map.of("question", "Which lines?", "requirementId", "foreign-requirement"),
                "member",
                404);
        cmd(
                p,
                "SAVE_CLARIFICATION",
                Map.of("question", "Which lines?", "ownerId", "999999999"),
                "member",
                400);
        p = cmd(p, "SAVE_REQUIREMENT", Map.of("title", "Line scope"), "member", 200);
        String requirementId = p.path("requirements").get(0).path("id").asText();
        p =
                cmd(
                        p,
                        "SAVE_CLARIFICATION",
                        Map.of(
                                "question",
                                "Which lines?",
                                "impact",
                                "Scope",
                                "requirementId",
                                requirementId),
                        "member",
                        200);
        String questionId = p.path("clarifications").get(0).path("id").asText();
        p =
                cmd(
                        p,
                        "SAVE_CLARIFICATION",
                        Map.of(
                                "id",
                                questionId,
                                "question",
                                "Which lines?",
                                "requirementId",
                                requirementId,
                                "status",
                                "ANSWERED",
                                "answer",
                                "Two",
                                "answerSourceId",
                                "Customer meeting 2026-09-18, section 2",
                                "answeredBy",
                                "forged"),
                        "member",
                        200);
        var answered = p.path("clarifications").get(0);
        assertFalse(answered.path("answeredAt").asText().isBlank());
        assertNotEquals("forged", answered.path("answeredBy").asText());
        assertEquals(
                "UNCONFIRMED",
                p.path("requirements").get(0).path("customerConfirmationStatus").asText());
        var read = api("GET", "/projects/" + p.path("id").asText(), "member", workspace, null, 200);
        assertEquals(answered, read.path("clarifications").get(0));
        p =
                cmd(
                        p,
                        "SAVE_CLARIFICATION",
                        Map.of(
                                "id",
                                questionId,
                                "question",
                                "Which lines?",
                                "status",
                                "OPEN",
                                "answeredBy",
                                "forged",
                                "answeredAt",
                                "yesterday"),
                        "member",
                        200);
        assertFalse(p.path("clarifications").get(0).has("answeredAt"));
        assertFalse(p.path("clarifications").get(0).has("answeredBy"));
    }

    @Test
    void authenticatedPersistenceIdempotencyAndConflict() throws Exception {
        var body =
                Map.of(
                        "name",
                        "One",
                        "customer",
                        "Customer",
                        "expectedVersion",
                        0,
                        "operationId",
                        "create-once");
        api("POST", "/projects", "viewer", workspace, body, 403);
        api("POST", "/projects", null, workspace, body, 401);
        var p = api("POST", "/projects", "member", workspace, body, 200);
        assertEquals(p, api("POST", "/projects", "member", workspace, body, 200));
        api("GET", "/projects/" + p.path("id").asText(), "owner", otherWorkspace, null, 404);
        var command =
                Map.of(
                        "expectedVersion",
                        1,
                        "operationId",
                        "edit-once",
                        "action",
                        "UPDATE_PROJECT",
                        "payload",
                        Map.of("name", "Updated"));
        var changed =
                api(
                        "POST",
                        "/projects/" + p.path("id").asText() + "/commands",
                        "member",
                        workspace,
                        command,
                        200);
        assertEquals(
                changed,
                api(
                        "POST",
                        "/projects/" + p.path("id").asText() + "/commands",
                        "member",
                        workspace,
                        command,
                        200));
        cmd(p, "ARCHIVE", Map.of(), "member", 409);
        assertEquals(
                changed,
                api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 200));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                        Integer.class,
                        p.path("id").asText()));
        var archived = cmd(changed, "ARCHIVE", Map.of(), "member", 200);
        cmd(archived, "UPDATE_PROJECT", Map.of("name", "No"), "member", 409);
    }

    @Test
    void g1RequiresAcceptedFactsAndReleasePublishesExactBytes() throws Exception {
        Fixture f = graph("设备😀额定380V");
        var kb = new vip.mate.wiki.model.WikiKnowledgeBaseEntity();
        kb.setId(Long.valueOf(f.kb));
        kb.setWorkspaceId(Long.valueOf(workspace));
        when(wikiKnowledgeBases.getById(Long.valueOf(f.kb))).thenReturn(kb);
        when(wikiKnowledgeBases.listByWorkspace(Long.valueOf(workspace))).thenReturn(List.of(kb));
        assertEquals(
                f.graph,
                api("GET", "/sources", "viewer", workspace, null, 200)
                        .get(0)
                        .path("graphId")
                        .asText());
        var e =
                call(
                        "POST",
                        "/graphs/" + f.graph + "/snapshots/" + f.snapshot + "/evidence",
                        "member",
                        workspace,
                        Map.of(
                                "operationId",
                                "evidence",
                                "startCodePoint",
                                5,
                                "endCodePoint",
                                9,
                                "exactQuote",
                                "380V"),
                        200);
        String iri =
                jdbc.queryForObject(
                        "SELECT iri FROM mate_semantic_entity WHERE id=?", String.class, f.entity);
        var fact =
                call(
                        "POST",
                        "/graphs/" + f.graph + "/statements",
                        "member",
                        workspace,
                        Map.of(
                                "operationId",
                                "fact",
                                "subjectId",
                                f.entity,
                                "assertionText",
                                "DataPropertyAssertion(<urn:test:voltage> <"
                                        + iri
                                        + "> \"380\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                                "validityKind",
                                "INTERVAL",
                                "evidenceIds",
                                List.of(e.path("id").asText())),
                        200);
        var p =
                cmd(
                        project(),
                        "BIND_MATERIAL",
                        Map.of("kbId", f.kb, "graphId", f.graph, "role", "PROJECT"),
                        "member",
                        200);
        p =
                cmd(
                        p,
                        "SAVE_REQUIREMENT",
                        Map.of(
                                "title",
                                "380V",
                                "scope",
                                "IN",
                                "graphId",
                                f.graph,
                                "statementId",
                                fact.path("id").asText(),
                                "statementRevision",
                                1),
                        "member",
                        200);
        cmd(p, "APPROVE_BASELINE", Map.of("reason", "checked"), "member", 403);
        cmd(p, "APPROVE_BASELINE", Map.of("reason", "checked"), "owner", 409);
        var accepted =
                call(
                        "POST",
                        "/graphs/"
                                + f.graph
                                + "/statements/"
                                + fact.path("id").asText()
                                + "/review",
                        "owner",
                        workspace,
                        Map.of(
                                "expectedRevision",
                                1,
                                "action",
                                "ACCEPT",
                                "reason",
                                "verified",
                                "operationId",
                                "accept"),
                        200);
        var req = p.path("requirements").get(0);
        p =
                cmd(
                        p,
                        "SAVE_REQUIREMENT",
                        Map.of(
                                "id",
                                req.path("id").asText(),
                                "title",
                                "380V",
                                "scope",
                                "IN",
                                "graphId",
                                f.graph,
                                "statementId",
                                fact.path("id").asText(),
                                "statementRevision",
                                accepted.path("revision").asInt()),
                        "member",
                        200);
        jdbc.update(
                "UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?",
                "new source",
                f.raw);
        cmd(p, "APPROVE_BASELINE", Map.of("reason", "stale source"), "owner", 409);
        jdbc.update(
                "UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?",
                "设备😀额定380V",
                f.raw);
        p = cmd(p, "APPROVE_BASELINE", Map.of("reason", "internal baseline"), "owner", 200);
        var baseline = p.path("baselines").get(0);
        assertEquals(
                fact.path("id").asText(),
                api(
                                "GET",
                                "/projects/" + p.path("id").asText() + "/statements",
                                "viewer",
                                workspace,
                                null,
                                200)
                        .get(0)
                        .path("id")
                        .asText());
        assertEquals(
                f.snapshot,
                baseline.path("references")
                        .get(0)
                        .path("sources")
                        .get(0)
                        .path("snapshotId")
                        .asText());
        p =
                cmd(
                        p,
                        "SAVE_SOLUTION",
                        Map.of(
                                "title",
                                "Solution",
                                "baselineId",
                                baseline.path("id").asText(),
                                "sections",
                                List.of(
                                        Map.of(
                                                "title",
                                                "Scope",
                                                "text",
                                                "380V",
                                                "requirementRefs",
                                                List.of(req.path("id").asText()))),
                                "requirementResponses",
                                List.of(
                                        Map.of(
                                                "requirementId",
                                                req.path("id").asText(),
                                                "status",
                                                "FULL"))),
                        "member",
                        200);
        String solution = p.path("solutions").get(0).path("id").asText();
        cmd(p, "CREATE_RELEASE", Map.of("solutionId", solution), "member", 409);
        p =
                cmd(
                        p,
                        "SAVE_REVIEW",
                        Map.of(
                                "solutionId",
                                solution,
                                "summary",
                                "Independently inspected",
                                "issues",
                                List.of()),
                        "owner",
                        200);
        p = cmd(p, "CREATE_RELEASE", Map.of("solutionId", solution), "member", 200);
        String release = p.path("releases").get(0).path("id").asText();
        api(
                "GET",
                "/projects/"
                        + p.path("id").asText()
                        + "/releases/"
                        + release
                        + "/files/solution.md",
                "viewer",
                workspace,
                null,
                403);
        String original =
                jdbc.queryForObject(
                        "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND"
                                + " filename='solution.md'",
                        String.class,
                        release);
        jdbc.update(
                "UPDATE mate_presales_artifact SET content_base64=? WHERE release_id=? AND"
                        + " filename='solution.md'",
                Base64.getEncoder()
                        .encodeToString(
                                "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                release);
        cmd(p, "APPROVE_RELEASE", Map.of("releaseId", release, "reason", "tampered"), "owner", 409);
        jdbc.update(
                "UPDATE mate_presales_artifact SET content_base64=? WHERE release_id=? AND"
                        + " filename='solution.md'",
                original,
                release);
        p =
                cmd(
                        p,
                        "APPROVE_RELEASE",
                        Map.of("releaseId", release, "reason", "approved exact bytes"),
                        "owner",
                        200);
        p =
                cmd(
                        p,
                        "SAVE_CLARIFICATION",
                        Map.of(
                                "question",
                                "Before publication",
                                "impact",
                                "Confirm boundary",
                                "sourceRefs",
                                List.of(f.raw)),
                        "member",
                        200);
        p = cmd(p, "PUBLISH_RELEASE", Map.of("releaseId", release), "owner", 200);
        var frozenHandoff =
                api(
                        "GET",
                        "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                        "viewer",
                        workspace,
                        null,
                        200);
        assertEquals(release, frozenHandoff.path("releaseId").asText());
        assertTrue(frozenHandoff.path("historicalClarificationsAvailable").asBoolean());
        assertEquals(1, frozenHandoff.path("clarifications").size());
        assertEquals(
                "Before publication",
                frozenHandoff.path("clarifications").get(0).path("question").asText());
        p =
                cmd(
                        p,
                        "SAVE_CLARIFICATION",
                        Map.of("question", "New after publication", "impact", "Scope"),
                        "member",
                        200);
        assertEquals(
                1,
                api(
                                "GET",
                                "/projects/"
                                        + p.path("id").asText()
                                        + "/releases/"
                                        + release
                                        + "/handoff",
                                "viewer",
                                workspace,
                                null,
                                200)
                        .path("clarifications")
                        .size());
        assertEquals(
                original,
                jdbc.queryForObject(
                        "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND"
                                + " filename='solution.md'",
                        String.class,
                        release));
        assertFalse(p.toString().contains("content_base64"));
        assertEquals(
                1,
                api(
                                "GET",
                                "/projects/" + p.path("id").asText() + "/handoff",
                                "viewer",
                                workspace,
                                null,
                                200)
                        .path("schemaVersion")
                        .asInt());
        assertEquals(100, p.path("solutions").get(0).path("coverage").path("percentage").asInt());
        // Historical reads and downloads must follow current employee KB authority.
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setName("history-source-" + UUID.randomUUID());
        employee.setWorkspaceId(Long.valueOf(workspace));
        employee.setEnabled(true);
        employee.setDeleted(0);
        employee.setWikiDisabled(false);
        employeeMapper.insert(employee);
        String employeeId = employee.getId().toString();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("agentId", employeeId);
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                employee.getId(),
                Long.valueOf(f.kb));
        api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 200);
        String persistedProject =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        p.path("id").asText());
        int receipts =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=?",
                        Integer.class,
                        workspace);
        int revisions =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                        Integer.class,
                        p.path("id").asText());
        for (String revoke :
                List.of(
                        "UPDATE mate_agent SET wiki_disabled=TRUE WHERE id=?",
                        "UPDATE mate_agent SET enabled=FALSE WHERE id=?",
                        "UPDATE mate_agent SET deleted=1 WHERE id=?",
                        "UPDATE mate_agent_wiki_kb SET enabled=FALSE WHERE agent_id=?")) {
            jdbc.update(revoke, employee.getId());
            for (String suffix :
                    List.of(
                            "",
                            "/handoff",
                            "/releases/" + release + "/handoff",
                            "/releases/" + release + "/files/solution.md")) {
                api(
                        "GET",
                        "/projects/" + p.path("id").asText() + suffix,
                        "viewer",
                        workspace,
                        null,
                        403);
            }
            assertEquals(
                    persistedProject,
                    jdbc.queryForObject(
                            "SELECT body_json FROM mate_presales_project WHERE id=?",
                            String.class,
                            p.path("id").asText()));
            assertEquals(
                    receipts,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=?",
                            Integer.class,
                            workspace));
            assertEquals(
                    revisions,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                            Integer.class,
                            p.path("id").asText()));
            assertEquals(
                    original,
                    jdbc.queryForObject(
                            "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND filename='solution.md'",
                            String.class,
                            release));
            jdbc.update(
                    "UPDATE mate_agent SET wiki_disabled=FALSE,enabled=TRUE,deleted=0 WHERE id=?",
                    employee.getId());
            jdbc.update(
                    "UPDATE mate_agent_wiki_kb SET enabled=TRUE WHERE agent_id=?",
                    employee.getId());
            api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 200);
            assertEquals(
                    frozenHandoff,
                    api(
                            "GET",
                            "/projects/"
                                    + p.path("id").asText()
                                    + "/releases/"
                                    + release
                                    + "/handoff",
                            "viewer",
                            workspace,
                            null,
                            200));
            assertPublishedDownload(p, release, original);
        }
        // A published snapshot remains an authorization input after current references disappear.
        var archived = p.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) archived).remove("agentId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) archived).putArray("materials");
        for (var oldBaseline : archived.path("baselines")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) oldBaseline).putArray("references");
        }
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                archived.toString(),
                p.path("id").asText());
        assertEquals(
                frozenHandoff,
                api(
                        "GET",
                        "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                        "viewer",
                        workspace,
                        null,
                        200));
        int archivedReceipts =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=?",
                        Integer.class,
                        workspace);
        int archivedRevisions =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                        Integer.class,
                        p.path("id").asText());
        for (boolean revokeRaw : List.of(true, false)) {
            String sql =
                    revokeRaw
                            ? "UPDATE mate_wiki_raw_material SET deleted=? WHERE id=?"
                            : "UPDATE mate_wiki_knowledge_base SET deleted=? WHERE id=?";
            String id = revokeRaw ? f.raw : f.kb;
            jdbc.update(sql, 1, id);
            for (String candidateStatus : List.of("PENDING", "APPROVED")) {
                var candidate = archived.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("releases").get(0))
                        .put("status", candidateStatus);
                jdbc.update(
                        "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                        candidate.toString(),
                        p.path("id").asText());
                api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 403);
            }
            jdbc.update(
                    "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                    archived.toString(),
                    p.path("id").asText());
            for (String suffix :
                    List.of(
                            "",
                            "/handoff",
                            "/releases/" + release + "/handoff",
                            "/releases/" + release + "/files/solution.md")) {
                api(
                        "GET",
                        "/projects/" + p.path("id").asText() + suffix,
                        "viewer",
                        workspace,
                        null,
                        403);
            }
            assertEquals(
                    archived.toString(),
                    jdbc.queryForObject(
                            "SELECT body_json FROM mate_presales_project WHERE id=?",
                            String.class,
                            p.path("id").asText()));
            assertEquals(
                    archivedReceipts,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=?",
                            Integer.class,
                            workspace));
            assertEquals(
                    archivedRevisions,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                            Integer.class,
                            p.path("id").asText()));
            assertEquals(
                    original,
                    jdbc.queryForObject(
                            "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND filename='solution.md'",
                            String.class,
                            release));
            jdbc.update(sql, 0, id);
            assertEquals(
                    frozenHandoff,
                    api(
                            "GET",
                            "/projects/"
                                    + p.path("id").asText()
                                    + "/releases/"
                                    + release
                                    + "/handoff",
                            "viewer",
                            workspace,
                            null,
                            200));
        }
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());

        var scalarOnly = archived.deepCopy();
        var scalarSnapshot =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        scalarOnly.path("releases").get(0).path("handoffSnapshot");
        ((com.fasterxml.jackson.databind.node.ObjectNode) scalarSnapshot.path("baseline"))
                .putArray("references");
        scalarSnapshot.putArray("sourceRefs").add(f.raw);
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                scalarOnly.toString(),
                p.path("id").asText());
        api(
                "GET",
                "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                "viewer",
                workspace,
                null,
                200);
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?", f.raw);
        api(
                "GET",
                "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                "viewer",
                workspace,
                null,
                403);
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=0 WHERE id=?", f.raw);
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());

        String materialId = p.path("materials").get(0).path("id").asText();
        int unbindVersion = p.path("version").asInt();
        String unbindOperation = UUID.randomUUID().toString();
        String projectId = p.path("id").asText();
        var unbindRequest =
                Map.of(
                        "expectedVersion",
                        unbindVersion,
                        "operationId",
                        unbindOperation,
                        "action",
                        "UNBIND_MATERIAL",
                        "payload",
                        Map.of("id", materialId));
        p =
                api(
                        "POST",
                        "/projects/" + projectId + "/commands",
                        "member",
                        workspace,
                        unbindRequest,
                        200);
        assertTrue(p.path("sourceAccessRestricted").asBoolean());
        assertTrue(p.path("releases").isArray() && p.path("releases").isEmpty());
        assertTrue(p.path("baselines").isArray() && p.path("baselines").isEmpty());
        assertEquals(
                p,
                api(
                        "POST",
                        "/projects/" + projectId + "/commands",
                        "member",
                        workspace,
                        unbindRequest,
                        200));
        p = cmd(p, "UPDATE_PROJECT", Map.of("goal", "Repair binding"), "member", 200);
        assertTrue(p.path("sourceAccessRestricted").asBoolean());
        assertTrue(p.path("releases").isArray() && p.path("releases").isEmpty());
        for (String suffix :
                List.of(
                        "",
                        "/releases/" + release + "/handoff",
                        "/releases/" + release + "/files/solution.md")) {
            api("GET", "/projects/" + projectId + suffix, "viewer", workspace, null, 403);
        }
        // Binding repair remains possible, with no rerender of the published release.
        p =
                cmd(
                        p,
                        "BIND_MATERIAL",
                        Map.of("kbId", f.kb, "graphId", f.graph, "role", "PROJECT"),
                        "member",
                        200);
        assertEquals(
                frozenHandoff,
                api(
                        "GET",
                        "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                        "viewer",
                        workspace,
                        null,
                        200));
        assertEquals(
                original,
                jdbc.queryForObject(
                        "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND filename='solution.md'",
                        String.class,
                        release));
        assertPublishedDownload(p, release, original);
        call(
                "POST",
                "/graphs/" + f.graph + "/sources/withdraw",
                "owner",
                workspace,
                Map.of(
                        "sourceKind",
                        "WIKI_RAW",
                        "sourceRef",
                        f.raw,
                        "reason",
                        "withdrawn",
                        "operationId",
                        "withdraw"),
                200);
        api(
                "GET",
                "/projects/"
                        + p.path("id").asText()
                        + "/releases/"
                        + release
                        + "/files/solution.md",
                "viewer",
                workspace,
                null,
                403);
        // The live project no longer carries source references; withdrawal is enforced by the
        // release.
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                archived.toString(),
                p.path("id").asText());
        for (String suffix :
                List.of(
                        "",
                        "/releases/" + release + "/handoff",
                        "/releases/" + release + "/files/solution.md")) {
            api(
                    "GET",
                    "/projects/" + p.path("id").asText() + suffix,
                    "viewer",
                    workspace,
                    null,
                    403);
        }
        assertEquals(
                original,
                jdbc.queryForObject(
                        "SELECT content_base64 FROM mate_presales_artifact WHERE release_id=? AND filename='solution.md'",
                        String.class,
                        release));
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                scalarOnly.toString(),
                p.path("id").asText());
        api(
                "GET",
                "/projects/" + p.path("id").asText() + "/releases/" + release + "/handoff",
                "viewer",
                workspace,
                null,
                403);
    }

    private void assertPublishedDownload(JsonNode project, String release, String original)
            throws Exception {
        var download =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(
                                                "/api/v1/presales/projects/"
                                                        + project.path("id").asText()
                                                        + "/releases/"
                                                        + release
                                                        + "/files/solution.md")
                                        .header("Authorization", tokens.get("viewer"))
                                        .header("X-Workspace-Id", workspace))
                        .andReturn()
                        .getResponse();
        assertEquals(200, download.getStatus());
        assertArrayEquals(Base64.getDecoder().decode(original), download.getContentAsByteArray());
    }

    @org.springframework.beans.factory.annotation.Autowired
    vip.mate.semantic.config.SemanticProperties semanticProperties;

    @Test
    void replayUsesCurrentEmployeeAuthorityWithoutChangingTheStoredReceipt() throws Exception {
        var p = (com.fasterxml.jackson.databind.node.ObjectNode) project();
        String allowedKb = kb(),
                otherKb = kb(),
                source = raw(allowedKb, "historical private source");
        var first = historyEmployee(allowedKb);
        var second = historyEmployee(otherKb);
        p.put("agentId", first);
        p.withArray("tasks")
                .addObject()
                .putObject("contextSnapshot")
                .putArray("sources")
                .addObject()
                .put("sourceRef", source)
                .put("kbId", allowedKb)
                .put("text", "historical private source");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
        String operation = UUID.randomUUID().toString(), projectId = p.path("id").asText();
        var request =
                Map.of(
                        "expectedVersion",
                        p.path("version").asInt(),
                        "operationId",
                        operation,
                        "action",
                        "UPDATE_PROJECT",
                        "payload",
                        Map.of("goal", "Recorded goal"));
        var originalResponse =
                api(
                        "POST",
                        "/projects/" + projectId + "/commands",
                        "member",
                        workspace,
                        request,
                        200);
        String storedReceipt =
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE operation_id=?",
                        String.class,
                        operation);
        var current = (com.fasterxml.jackson.databind.node.ObjectNode) originalResponse.deepCopy();
        current.put("agentId", second);
        current.putArray("tasks");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                current.toString(),
                projectId);
        var restricted =
                api(
                        "POST",
                        "/projects/" + projectId + "/commands",
                        "member",
                        workspace,
                        request,
                        200);
        assertTrue(restricted.path("sourceAccessRestricted").asBoolean());
        assertTrue(restricted.path("tasks").isArray() && restricted.path("tasks").isEmpty());
        assertFalse(restricted.toString().contains("historical private source"));
        assertEquals(
                storedReceipt,
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE operation_id=?",
                        String.class,
                        operation));
        assertEquals(
                current.toString(),
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        projectId));
        current.put("agentId", first);
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                current.toString(),
                projectId);
        assertEquals(
                originalResponse,
                api(
                        "POST",
                        "/projects/" + projectId + "/commands",
                        "member",
                        workspace,
                        request,
                        200));
        assertEquals(
                storedReceipt,
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE operation_id=?",
                        String.class,
                        operation));
    }

    private String historyEmployee(String kb) {
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setName("history-" + UUID.randomUUID());
        employee.setWorkspaceId(Long.valueOf(workspace));
        employee.setEnabled(true);
        employee.setDeleted(0);
        employee.setWikiDisabled(false);
        employeeMapper.insert(employee);
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                employee.getId(),
                Long.valueOf(kb));
        return employee.getId().toString();
    }

    @Test
    void historicalSourcesUseActualKbOwnershipEvenWithoutCurrentMaterialBindings()
            throws Exception {
        var p = (com.fasterxml.jackson.databind.node.ObjectNode) project();
        String allowedKb = kb();
        String deniedKb = kb();
        String deniedRaw = raw(deniedKb, "private historical source");
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setName("historical-source-" + UUID.randomUUID());
        employee.setWorkspaceId(Long.valueOf(workspace));
        employee.setEnabled(true);
        employee.setDeleted(0);
        employee.setWikiDisabled(false);
        employeeMapper.insert(employee);
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                employee.getId(),
                Long.valueOf(allowedKb));
        for (String collection : List.of("tasks", "baselines")) {
            p.putArray("tasks");
            p.putArray("baselines");
            p.put("agentId", employee.getId().toString());
            var item = p.withArray(collection).addObject();
            var source =
                    collection.equals("tasks")
                            ? item.putObject("contextSnapshot").putArray("sources").addObject()
                            : item.putArray("references")
                                    .addObject()
                                    .putArray("sources")
                                    .addObject();
            source.put("sourceRef", deniedRaw)
                    .put("kbId", allowedKb)
                    .put("text", "private historical source");
            jdbc.update(
                    "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                    p.toString(),
                    p.path("id").asText());
            api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 403);
            // A restored grant permits the original snapshot without rewriting historical data.
            jdbc.update(
                    "UPDATE mate_agent_wiki_kb SET kb_id=? WHERE agent_id=?",
                    deniedKb,
                    employee.getId());
            assertEquals(
                    p,
                    api(
                            "GET",
                            "/projects/" + p.path("id").asText(),
                            "viewer",
                            workspace,
                            null,
                            200));
            jdbc.update(
                    "UPDATE mate_agent_wiki_kb SET kb_id=? WHERE agent_id=?",
                    allowedKb,
                    employee.getId());
            p.remove("agentId");
            jdbc.update(
                    "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                    p.toString(),
                    p.path("id").asText());
            assertEquals(
                    p,
                    api(
                            "GET",
                            "/projects/" + p.path("id").asText(),
                            "viewer",
                            workspace,
                            null,
                            200));
        }
    }

    @Test
    void semanticDisabledStillAllowsProjectWorkAndRejectsGates() throws Exception {
        semanticProperties.setEnabled(false);
        try {
            var p = project();
            assertTrue(
                    api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 200)
                            .path("id")
                            .isTextual());
            cmd(p, "APPROVE_BASELINE", Map.of("reason", "gate"), "owner", 409);
        } finally {
            semanticProperties.setEnabled(true);
        }
    }

    @Test
    void concurrentCommandsCannotLoseAnUpdate() throws Exception {
        var p = project();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                int n = i;
                jobs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    var r =
                                            org.springframework.test.web.servlet.request
                                                    .MockMvcRequestBuilders.post(
                                                            "/api/v1/presales/projects/"
                                                                    + p.path("id").asText()
                                                                    + "/commands")
                                                    .header("Authorization", tokens.get("member"))
                                                    .header("X-Workspace-Id", workspace)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsString(
                                                                    Map.of(
                                                                            "expectedVersion",
                                                                            1,
                                                                            "operationId",
                                                                            "concurrent-" + n,
                                                                            "action",
                                                                            "UPDATE_PROJECT",
                                                                            "payload",
                                                                            Map.of(
                                                                                    "name",
                                                                                    "Name " + n))));
                                    return mvc.perform(r).andReturn().getResponse().getStatus();
                                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var job : jobs) statuses.add(job.get(10, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(
                    2,
                    api("GET", "/projects/" + p.path("id").asText(), "viewer", workspace, null, 200)
                            .path("version")
                            .asInt());
        } finally {
            pool.shutdownNow();
        }
    }

    private Fixture graph(String text) throws Exception {
        String kb = kb();
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision =
                publish(
                        ontology,
                        saved.path("draftVersion").asLong(),
                        "publish-" + UUID.randomUUID());
        JsonNode binding =
                call(
                        "PUT",
                        "/knowledge-bases/" + kb + "/binding",
                        "owner",
                        workspace,
                        Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()),
                        200);
        JsonNode entity =
                call(
                        "POST",
                        "/graphs/" + binding.path("graphId").asText() + "/entities",
                        "member",
                        workspace,
                        Map.of(
                                "assertedTypes",
                                List.of("urn:test:Equipment"),
                                "displayName",
                                "P-101"),
                        200);
        String raw = raw(kb, text);
        String importOperation = "import-" + UUID.randomUUID();
        JsonNode imported =
                call(
                        "POST",
                        "/graphs/" + binding.path("graphId").asText() + "/imports",
                        "member",
                        workspace,
                        Map.of(
                                "sourceKind",
                                "WIKI_RAW",
                                "sourceRef",
                                raw,
                                "operationId",
                                importOperation),
                        200);
        return new Fixture(
                kb,
                binding.path("graphId").asText(),
                entity.path("id").asText(),
                raw,
                imported.path("snapshotId").asText(),
                imported.path("id").asText(),
                importOperation);
    }

    private String kb() {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO"
                        + " mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id),
                "Semantic KB",
                "",
                "active",
                0,
                0,
                Long.valueOf(workspace),
                now,
                now);
        return id;
    }

    private String raw(String kb, String text) {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO"
                        + " mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id),
                Long.valueOf(kb),
                "repair note",
                "text",
                text,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "completed",
                now,
                now);
        return id;
    }

    private record Fixture(
            String kb,
            String graph,
            String entity,
            String raw,
            String snapshot,
            String importJob,
            String importOperation) {}
}
