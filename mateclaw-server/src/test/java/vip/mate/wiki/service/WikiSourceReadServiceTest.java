package vip.mate.wiki.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.wiki.repository.WikiSourceReadRepository;

class WikiSourceReadServiceTest {
    private JdbcTemplate jdbc;
    private WikiSourceReadService reader;

    @BeforeEach
    void setUp() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:wiki_read_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY, kb_id BIGINT, extracted_text VARCHAR, original_content VARCHAR, deleted INT)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,0)");
        reader = new WikiSourceReadService(new WikiSourceReadRepository(jdbc));
    }

    @Test
    void extractedTextWinsAndStringIdsAreReturned() {
        insert("提取\u00a0内容\n", "original", 0);
        assertEquals(
                new WikiSourceReadService.CurrentSource("11", "1", "提取\u00a0内容\n"),
                reader.readInWorkspace("10", "11").orElseThrow());
    }

    @Test
    void emptyExtractedTextFallsBackToOriginalWithoutTrimming() {
        insert("", " 原文\n", 0);
        assertEquals(" 原文\n", reader.readInWorkspace("10", "11").orElseThrow().text());
    }

    @Test
    void nullExtractedTextFallsBackToOriginal() {
        insert(null, "original", 0);
        assertEquals("original", reader.readInWorkspace("10", "11").orElseThrow().text());
    }

    @Test
    void sqlNullContentBecomesEmptyText() {
        insert(null, null, 0);
        assertEquals("", reader.readInWorkspace("10", "11").orElseThrow().text());
        jdbc.update("UPDATE mate_wiki_raw_material SET extracted_text='' WHERE id=11");
        assertEquals("", reader.readInWorkspace("10", "11").orElseThrow().text());
    }

    @Test
    void whitespaceExtractedTextIsNotReplaced() {
        insert(" \t\n\u3000", "original", 0);
        assertEquals(" \t\n\u3000", reader.readInWorkspace("10", "11").orElseThrow().text());
    }

    @Test
    void missingForeignWorkspaceAndMissingKbAreUnavailable() {
        insert("text", null, 0);
        assertTrue(reader.readInWorkspace("20", "11").isEmpty());
        assertTrue(reader.readInWorkspace("10", "999").isEmpty());
        jdbc.update("DELETE FROM mate_wiki_knowledge_base");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
    }

    @Test
    void nullKbAndNullWorkspaceAreUnavailable() {
        insert("text", null, 0);
        jdbc.update("UPDATE mate_wiki_raw_material SET kb_id=NULL WHERE id=11");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
        jdbc.update("UPDATE mate_wiki_raw_material SET kb_id=1 WHERE id=11");
        jdbc.update("UPDATE mate_wiki_knowledge_base SET workspace_id=NULL WHERE id=1");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
    }

    @Test
    void rawDeletedAndNullFlagsAreUnavailable() {
        insert("text", null, 1);
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=NULL WHERE id=11");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
    }

    @Test
    void kbDeletedAndNullFlagsAreUnavailable() {
        insert("text", null, 0);
        jdbc.update("UPDATE mate_wiki_knowledge_base SET deleted=1 WHERE id=1");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
        jdbc.update("UPDATE mate_wiki_knowledge_base SET deleted=NULL WHERE id=1");
        assertTrue(reader.readInWorkspace("10", "11").isEmpty());
    }

    @Test
    void wikiDisabledStillProvidesCurrentReadBeans() {
        insert("text", null, 0);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "disabled",
                                    java.util.Map.of("mateclaw.wiki.enabled", "false")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(WikiSourceReadRepository.class, WikiSourceReadService.class);
            context.refresh();
            assertEquals(
                    "text",
                    context.getBean(WikiSourceReadService.class)
                            .readInWorkspace("10", "11")
                            .orElseThrow()
                            .text());
        }
    }

    private void insert(String extracted, String original, Integer deleted) {
        jdbc.update(
                "INSERT INTO mate_wiki_raw_material VALUES(11,1,?,?,?)",
                extracted,
                original,
                deleted);
    }
}
