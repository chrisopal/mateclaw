package vip.mate.semantic.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.semantic.source.repository.SourceGovernanceReadRepository;

class SourceGovernanceReadServiceTest {
    private JdbcTemplate jdbc;
    private SourceGovernanceReadService reader;

    @BeforeEach
    void setUp() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:governance_read_"
                                        + UUID.randomUUID()
                                        + ";DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE mate_semantic_source_governance(graph_id VARCHAR, source_id VARCHAR, source_kind VARCHAR, state VARCHAR)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_graph(id VARCHAR PRIMARY KEY,workspace_id BIGINT,kb_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY,workspace_id BIGINT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY,kb_id BIGINT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_evidence(id VARCHAR PRIMARY KEY,graph_id VARCHAR,snapshot_id VARCHAR)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_source_snapshot(id VARCHAR PRIMARY KEY,graph_id VARCHAR,source_kind VARCHAR,source_id VARCHAR)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_snapshot_exclusion(graph_id VARCHAR,snapshot_id VARCHAR)");
        jdbc.update("INSERT INTO mate_semantic_graph VALUES('graph',10,1),('other',20,2)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,0),(2,20,0)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(11,1,0),(12,2,0)");
        jdbc.update(
                "INSERT INTO mate_semantic_source_snapshot VALUES('snapshot','graph','WIKI_RAW','11')");
        jdbc.update("INSERT INTO mate_semantic_evidence VALUES('evidence','graph','snapshot')");
        reader = new SourceGovernanceReadService(new SourceGovernanceReadRepository(jdbc));
    }

    @Test
    void withdrawalsIncludeOtherSourceKinds() {
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('graph','11','EXTERNAL','WITHDRAWN')");
        assertTrue(reader.isWithdrawn("graph", "11"));
    }

    @Test
    void graphSourceAndStateRemainExact() {
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('graph','11','WIKI_RAW','ACTIVE')");
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('other','11','WIKI_RAW','WITHDRAWN')");
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('graph','12','WIKI_RAW','WITHDRAWN')");
        assertFalse(reader.isWithdrawn("graph", "11"));
        assertFalse(reader.isWithdrawn("missing", "11"));
    }

    @Test
    void emptyGraphStillReadsPersistedWithdrawal() {
        jdbc.update("INSERT INTO mate_semantic_source_governance VALUES('','11',NULL,'WITHDRAWN')");
        assertTrue(reader.isWithdrawn("", "11"));
        assertFalse(reader.isWithdrawn("", "12"));
    }

    @Test
    void semanticDisabledStillProvidesReadBeans() {
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('graph','11','EXTERNAL','WITHDRAWN')");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "disabled",
                                    java.util.Map.of("mateclaw.semantic.enabled", "false")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(
                    SourceGovernanceReadRepository.class, SourceGovernanceReadService.class);
            context.refresh();
            assertTrue(
                    context.getBean(SourceGovernanceReadService.class).isWithdrawn("graph", "11"));
        }
    }

    @Test
    void evidenceFactsRequireExactWorkspaceGraphAndKb() {
        assertEquals(
                java.util.Optional.of("11"),
                reader.availableEvidenceSource("10", "graph", "1", "evidence"));
        for (String[] wrong :
                new String[][] {
                    {"20", "graph", "1", "evidence"},
                    {"10", "other", "1", "evidence"},
                    {"10", "graph", "2", "evidence"},
                    {"10", "graph", "1", "missing"},
                    {"bad", "graph", "1", "evidence"},
                    {"10", "graph", "0", "evidence"}
                })
            assertTrue(
                    reader.availableEvidenceSource(wrong[0], wrong[1], wrong[2], wrong[3])
                            .isEmpty());
        jdbc.update("UPDATE mate_wiki_knowledge_base SET workspace_id=20 WHERE id=1");
        assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
    }

    @Test
    void evidenceFactsRejectSnapshotMismatchUnsupportedKindAndForeignRaw() {
        jdbc.update("UPDATE mate_semantic_source_snapshot SET graph_id='other'");
        assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
        jdbc.update("UPDATE mate_semantic_source_snapshot SET graph_id='graph'");
        for (String kind : new String[] {"OTHER", "wiki_raw"}) {
            jdbc.update("UPDATE mate_semantic_source_snapshot SET source_kind=?", kind);
            assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
        }
        jdbc.update("UPDATE mate_semantic_source_snapshot SET source_kind='WIKI_RAW'");
        for (String source : new String[] {"12", "999", "11x"}) {
            jdbc.update("UPDATE mate_semantic_source_snapshot SET source_id=?", source);
            assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
        }
    }

    @Test
    void evidenceFactsRejectWithdrawalAndExclusionWithoutChangingStoredEvidence() {
        jdbc.update(
                "INSERT INTO mate_semantic_source_governance VALUES('graph','11','WIKI_RAW','WITHDRAWN')");
        assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
        jdbc.update("DELETE FROM mate_semantic_source_governance");
        jdbc.update("INSERT INTO mate_semantic_snapshot_exclusion VALUES('graph','snapshot')");
        assertTrue(reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
        assertEquals(
                "snapshot",
                jdbc.queryForObject(
                        "SELECT snapshot_id FROM mate_semantic_evidence WHERE id='evidence'",
                        String.class));
        assertEquals(
                "11",
                jdbc.queryForObject(
                        "SELECT source_id FROM mate_semantic_source_snapshot WHERE id='snapshot'",
                        String.class));
    }

    @Test
    void evidenceFactsRejectDeletedOrNullDeletedKbAndRaw() {
        for (String table : new String[] {"mate_wiki_knowledge_base", "mate_wiki_raw_material"}) {
            int id = table.equals("mate_wiki_knowledge_base") ? 1 : 11;
            for (Integer deleted : new Integer[] {1, null}) {
                jdbc.update("UPDATE " + table + " SET deleted=? WHERE id=?", deleted, id);
                assertTrue(
                        reader.availableEvidenceSource("10", "graph", "1", "evidence").isEmpty());
            }
            jdbc.update("UPDATE " + table + " SET deleted=0 WHERE id=?", id);
        }
        assertEquals(
                java.util.Optional.of("11"),
                reader.availableEvidenceSource("10", "graph", "1", "evidence"));
    }

    @Test
    void semanticDisabledStillReadsPersistedEvidenceFacts() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "disabled",
                                    java.util.Map.of("mateclaw.semantic.enabled", "false")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(
                    SourceGovernanceReadRepository.class, SourceGovernanceReadService.class);
            context.refresh();
            assertEquals(
                    java.util.Optional.of("11"),
                    context.getBean(SourceGovernanceReadService.class)
                            .availableEvidenceSource("10", "graph", "1", "evidence"));
            assertFalse(context.containsBean("semanticQueryService"));
        }
    }
}
