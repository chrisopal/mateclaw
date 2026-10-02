package vip.mate.semantic.source;

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
}
