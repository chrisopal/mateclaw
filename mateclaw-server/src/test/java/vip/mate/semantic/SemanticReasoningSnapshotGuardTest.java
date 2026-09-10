package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;
import vip.mate.semantic.reasoning.SemanticReasoningDtos.Request;
import vip.mate.semantic.reasoning.SemanticReasoningService;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;

/** Verifies that a worker result is rejected when the authorized snapshot moves. */
class SemanticReasoningSnapshotGuardTest extends SemanticHttpFixture {
    @Autowired SemanticReasoningService reasoning;
    @MockitoBean ReasoningPort worker;

    private record Fixture(String graph, Long agent, String actor) {}

    private Fixture fixture() throws Exception {
        String ontology = create();
        draft(ontology);
        call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(1, owlDocument("Ontology(<urn:test:guard> Declaration(Class(<urn:test:Equipment>)))")), 200);
        String revision = publish(ontology, 2, UUID.randomUUID().toString()).path("id").asText();
        String kb = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Guard KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText();
        String actor = jdbc.queryForObject(
                "SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",
                String.class, Long.valueOf(workspace));
        return new Fixture(graph, agentWithKnowledgeBase(kb), actor);
    }

    @Test
    void rejectsWorkerResultWhenGraphMutationChangesDuringExecution() throws Exception {
        Fixture f = fixture();
        doAnswer(invocation -> {
            ReasoningRequest input = invocation.getArgument(0);
            jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1 WHERE id=?", f.graph());
            return new ReasoningResult(ReasoningRequest.SCHEMA_VERSION, input.requestId(), ReasoningStatus.CONSISTENT,
                    input.task().kind(), "controlled-worker", "controlled", "1", 1,
                    List.of(), List.of(), List.of(),
                    new ReasoningResult.Provenance(input.scope().name(), input.task().kind().name(),
                            Instant.now(), "controlled-worker-v1"));
        }).when(worker).reason(any(ReasoningRequest.class));

        SemanticApiException failure = assertThrows(SemanticApiException.class,
                () -> reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                        new Request(null, ReasoningRequest.TaskKind.CONSISTENCY, null, null,
                                Instant.parse("2026-01-01T00:00:00Z"))));
        assertEquals("REASONING_STALE", failure.code());
    }
}
