package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

class SemanticGovernanceIntegrationTest extends SemanticHttpFixture {
    @MockitoSpyBean JdbcTemplate jdbcSpy;

    @Test
    void sourceWithdrawalAppendsAnImmutableGovernanceEvent() throws Exception {
        Fixture fixture = graph("设备😀额定380V");
        String operation = "withdraw-event-" + UUID.randomUUID();

        JsonNode result = call(
                "POST",
                "/graphs/" + fixture.graph + "/sources/withdraw",
                "owner",
                workspace,
                Map.of(
                        "sourceKind", "WIKI_RAW",
                        "sourceRef", fixture.raw,
                        "reason", "superseded by a reviewed source",
                        "operationId", operation),
                200);

        assertEquals("WITHDRAWN", result.path("state").asText());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_governance_event WHERE graph_id=?",
                        Integer.class,
                        fixture.graph));
        assertEquals(
                "SOURCE",
                jdbc.queryForObject(
                        "SELECT resource_kind FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        String.class,
                        fixture.graph,
                        operation));
        assertEquals(
                fixture.raw,
                jdbc.queryForObject(
                        "SELECT resource_id FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        String.class,
                        fixture.graph,
                        operation));
        assertEquals(
                "SOURCE_WITHDRAW",
                jdbc.queryForObject(
                        "SELECT action FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        String.class,
                        fixture.graph,
                        operation));
        assertEquals(
                "superseded by a reviewed source",
                jdbc.queryForObject(
                        "SELECT reason FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        String.class,
                        fixture.graph,
                        operation));
        assertEquals(
                result,
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT result_json FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                                String.class,
                                fixture.graph,
                                operation)));
        assertTrue(
                jdbc.queryForObject(
                                "SELECT resource_version FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                                Integer.class,
                                fixture.graph,
                                operation)
                        > 0);

        JsonNode readback = call(
                "GET",
                "/graphs/" + fixture.graph + "/governance?resourceKind=SOURCE",
                "admin",
                workspace,
                null,
                200);
        assertEquals(1, readback.path("total").asInt());
        assertEquals(operation, readback.path("items").get(0).path("operationId").asText());
        call(
                "GET",
                "/graphs/" + fixture.graph + "/governance",
                "viewer",
                workspace,
                null,
                403);

        String exclusionOperation = "exclude-event-" + UUID.randomUUID();
        call(
                "POST",
                "/graphs/" + fixture.graph + "/snapshots/" + fixture.snapshot + "/exclude",
                "owner",
                workspace,
                Map.of("reason", "snapshot is superseded", "operationId", exclusionOperation),
                200);
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_governance_event WHERE graph_id=?",
                        Integer.class,
                        fixture.graph));
        assertEquals(
                "SNAPSHOT_EXCLUDE",
                jdbc.queryForObject(
                        "SELECT action FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        String.class,
                        fixture.graph,
                        exclusionOperation));
    }

    @Test
    void governanceInsertFailureRollsBackSourceMutation() throws Exception {
        Fixture fixture = graph("设备😀额定380V");
        long beforeVersion = graphVersion(fixture.graph);
        String operation = "withdraw-rollback-" + UUID.randomUUID();
        doThrow(new IllegalStateException("governance insert failed"))
                .when(jdbcSpy)
                .update(startsWith("INSERT INTO mate_semantic_governance_event"), any(Object[].class));

        call(
                "POST",
                "/graphs/" + fixture.graph + "/sources/withdraw",
                "owner",
                workspace,
                Map.of(
                        "sourceKind", "WIKI_RAW",
                        "sourceRef", fixture.raw,
                        "reason", "must roll back",
                        "operationId", operation),
                500);

        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=?",
                        Integer.class,
                        fixture.graph,
                        fixture.raw));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_governance_event WHERE graph_id=? AND operation_id=?",
                        Integer.class,
                        fixture.graph,
                        operation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_mutation_command WHERE graph_id=? AND operation_id=?",
                        Integer.class,
                        fixture.graph,
                        operation));
        assertEquals(beforeVersion, graphVersion(fixture.graph));
    }

    private long graphVersion(String graph) {
        return jdbc.queryForObject(
                "SELECT mutation_version FROM mate_semantic_graph WHERE id=?",
                Long.class,
                graph);
    }

    private Fixture graph(String text) throws Exception {
        String kb = kb();
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision = publish(ontology, saved.path("draftVersion").asLong(), op());
        JsonNode binding = call(
                "PUT",
                "/knowledge-bases/" + kb + "/binding",
                "owner",
                workspace,
                Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()),
                200);
        String graph = binding.path("graphId").asText();
        String raw = raw(kb, text);
        JsonNode imported = call(
                "POST",
                "/graphs/" + graph + "/imports",
                "member",
                workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", op()),
                200);
        return new Fixture(graph, raw, imported.path("snapshotId").asText());
    }

    private String kb() {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id),
                "Semantic governance KB",
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
                "INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id),
                Long.valueOf(kb),
                "governance source",
                "text",
                text,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "completed",
                now,
                now);
        return id;
    }

    private String op() {
        return "governance-" + UUID.randomUUID();
    }

    private record Fixture(String graph, String raw, String snapshot) {}
}
