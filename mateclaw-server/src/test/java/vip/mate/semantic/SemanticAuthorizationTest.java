package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.support.SemanticHttpFixture;

import java.util.Map;

class SemanticAuthorizationTest extends SemanticHttpFixture {
    @Test
    void activePrincipalAndCurrentPermissionRequiredForReplayAndOperations() throws Exception {
        String id = create();
        draft(id);
        save(id, 1);
        String op = "auth-" + java.util.UUID.randomUUID();
        publish(id, 2, op);
        call("GET", "/operations/" + op, "viewer", workspace, null, 403);
        call("GET", "/operations/" + op, "owner", otherWorkspace, null, 404);
        String username = auth.parseToken(tokens.get("admin").substring(7));
        Long userId = auth.findByUsername(username).getId();
        workspaces.updateMemberRole(Long.valueOf(workspace), userId, "viewer");
        call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "admin",
                workspace,
                publishBody(2, op),
                403);
        jdbc.update("update mate_user set enabled=false where id=?", userId);
        call("GET", "/status", "admin", null, null, 401);
        String memberName = auth.parseToken(tokens.get("member").substring(7));
        jdbc.update("update mate_user set deleted=1 where username=?", memberName);
        call("GET", "/status", "member", null, null, 401);
    }

    @Test
    void viewerCannotMutateAndCrossOntologyRevisionIsHidden() throws Exception {
        String id = create();
        draft(id);
        save(id, 1);
        var revision = publish(id, 2, "cross-" + java.util.UUID.randomUUID());
        String other = create();
        call(
                "GET",
                "/ontologies/" + other + "/revisions/" + revision.path("id").asText(),
                "owner",
                workspace,
                null,
                404);
        call(
                "POST",
                "/ontologies/" + other + "/draft",
                "member",
                workspace,
                Map.of("baseRevisionId", revision.path("id").asText()),
                404);
        draft(id);
        call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "viewer",
                workspace,
                saveBody(1, definition()),
                403);
        call("GET", "/ontologies", "global", null, null, 400);
        call("GET", "/ontologies", "owner", "invalid", null, 400);
    }

    @Test
    void viewerCannotCreateAndMissingScopeIsRejected() throws Exception {
        assertEquals(
                "FORBIDDEN",
                call(
                                "POST",
                                "/ontologies",
                                "viewer",
                                workspace,
                                Map.of("name", "x", "description", ""),
                                403)
                        .path("code")
                        .asText());
        assertEquals(
                "WORKSPACE_REQUIRED",
                call("GET", "/ontologies", "owner", null, null, 400).path("code").asText());
    }

    @Test
    void revisionsAreScopedEvenForGlobalAdmin() throws Exception {
        String id = create();
        draft(id);
        save(id, 1);
        var revision = publish(id, 2, "op-" + java.util.UUID.randomUUID());
        call(
                "GET",
                "/ontologies/" + id + "/revisions/" + revision.path("id").asText(),
                "global",
                otherWorkspace,
                null,
                404);
        call("GET", "/ontologies/" + id, "viewer", workspace, null, 200);
        call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "member",
                workspace,
                publishBody(2, "denied"),
                403);
    }
}
