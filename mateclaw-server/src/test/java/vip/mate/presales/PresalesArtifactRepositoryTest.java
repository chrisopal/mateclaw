package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesArtifactRepository.StoredArtifact;

class PresalesArtifactRepositoryTest {
    private JdbcTemplate jdbc;
    private PresalesArtifactRepository artifacts;
    private TransactionTemplate transaction;
    private static final String PROJECT = "9007199254740993001", RELEASE = "9007199254740993002";
    private static final String RAW = "AAH/IgoAfw==\n";

    @BeforeEach
    void database() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:artifact_repository_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        artifacts = new PresalesArtifactRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void close() throws java.sql.SQLException {
        // JdbcTemplate probes warnings in DEBUG after SHUTDOWN has closed H2.
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    void keepsRawStoredValuesAndAllThreeKeyParts() {
        artifacts.insert(PROJECT, RELEASE, "solution.pptx", "raw digest Ω", RAW);
        artifacts.insert("other", RELEASE, "solution.pptx", "private", "other-body");
        artifacts.insert(PROJECT, "other", "solution.pptx", "other-release", "other-release-body");
        artifacts.insert(PROJECT, RELEASE, "solution.md", "other-file", "other-file-body");
        assertEquals(
                List.of(new StoredArtifact("raw digest Ω", RAW)),
                artifacts.find(PROJECT, RELEASE, "solution.pptx"));
        assertEquals(
                List.of(RAW),
                artifacts.find(PROJECT, RELEASE, "solution.pptx").stream()
                        .map(StoredArtifact::contentBase64)
                        .toList());
        assertTrue(artifacts.find("missing", RELEASE, "solution.pptx").isEmpty());
        assertTrue(artifacts.find(PROJECT, "missing", "solution.pptx").isEmpty());
        assertTrue(artifacts.find(PROJECT, RELEASE, "missing").isEmpty());
        assertTrue(
                artifacts.find(PROJECT, RELEASE, "missing").stream()
                        .map(StoredArtifact::contentBase64)
                        .toList()
                        .isEmpty());
    }

    @Test
    void rebindsOnlyOriginalProjectAndReleaseWithoutChangingFiles() {
        artifacts.insert(PROJECT, RELEASE, "solution.pptx", "digest", RAW);
        artifacts.insert(PROJECT, RELEASE, "solution.md", "md-digest", "md-body");
        artifacts.insert("other", RELEASE, "solution.pptx", "private", "private-body");
        artifacts.insert(PROJECT, "other", "solution.pptx", "other", "other-body");
        assertEquals(0, artifacts.reassignRelease(PROJECT, "missing", "stored"));
        assertEquals(2, artifacts.reassignRelease(PROJECT, RELEASE, "stored"));
        assertTrue(artifacts.find(PROJECT, RELEASE, "solution.pptx").isEmpty());
        assertEquals(
                List.of(new StoredArtifact("digest", RAW)),
                artifacts.find(PROJECT, "stored", "solution.pptx"));
        assertEquals(
                List.of("md-body"),
                artifacts.find(PROJECT, "stored", "solution.md").stream()
                        .map(StoredArtifact::contentBase64)
                        .toList());
        assertEquals(
                List.of("private-body"),
                artifacts.find("other", RELEASE, "solution.pptx").stream()
                        .map(StoredArtifact::contentBase64)
                        .toList());
        assertEquals(
                List.of("other-body"),
                artifacts.find(PROJECT, "other", "solution.pptx").stream()
                        .map(StoredArtifact::contentBase64)
                        .toList());
    }

    @Test
    void doesNotCommitEarlierFilesWhenCallerTransactionFails() {
        var failure =
                assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            artifacts.insert(
                                                    PROJECT,
                                                    RELEASE,
                                                    "solution.pptx",
                                                    "digest",
                                                    RAW);
                                            artifacts.insert(
                                                    PROJECT,
                                                    RELEASE,
                                                    "solution.md",
                                                    "md-digest",
                                                    "body");
                                            artifacts.insert(
                                                    PROJECT,
                                                    RELEASE,
                                                    "solution.pptx",
                                                    "duplicate",
                                                    "duplicate");
                                        }));
        assertNotNull(failure.getCause());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_presales_artifact", Integer.class));
    }

    @Test
    void releaseReassignmentRollsBackWithCaller() {
        artifacts.insert(PROJECT, RELEASE, "solution.pptx", "digest", RAW);
        transaction.executeWithoutResult(
                status -> {
                    assertEquals(1, artifacts.reassignRelease(PROJECT, RELEASE, "stored"));
                    assertEquals(
                            List.of(RAW),
                            artifacts.find(PROJECT, "stored", "solution.pptx").stream()
                                    .map(StoredArtifact::contentBase64)
                                    .toList());
                    status.setRollbackOnly();
                });
        assertEquals(
                List.of(new StoredArtifact("digest", RAW)),
                artifacts.find(PROJECT, RELEASE, "solution.pptx"));
        assertTrue(artifacts.find(PROJECT, "stored", "solution.pptx").isEmpty());
    }

    @Test
    void presentationAndStorageStillWireWhenPresalesFeatureIsDisabled() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("mateclaw.presales.enabled=false")
                .withUserConfiguration(
                        PresalesArtifactRepository.class, PresalesPresentationService.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(
                        com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(
                        vip.mate.skill.runtime.SkillRuntimeService.class,
                        () -> mock(vip.mate.skill.runtime.SkillRuntimeService.class))
                .withBean(
                        vip.mate.agent.binding.service.AgentBindingService.class,
                        () -> mock(vip.mate.agent.binding.service.AgentBindingService.class))
                .withBean(
                        org.springframework.transaction.PlatformTransactionManager.class,
                        () ->
                                mock(
                                        org.springframework.transaction.PlatformTransactionManager
                                                .class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertNotNull(context.getBean(PresalesPresentationService.class));
                            assertNotNull(context.getBean(PresalesArtifactRepository.class));
                        });
    }
}
