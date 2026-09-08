package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.manifest.SkillManifestParser;
import vip.mate.skill.runtime.SkillFrontmatterParser;

class OntologyBuilderSkillTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "semantic_ontology_copy_revision",
            "semantic_ontology_sources",
            "semantic_ontology_list",
            "semantic_ontology_get",
            "semantic_ontology_create_draft",
            "semantic_ontology_save_draft",
            "semantic_ontology_validate",
            "semantic_ontology_prepare_publish",
            "semantic_ontology_read_source");

    @Test
    void shipsAParserCompatibleSkillWithTheBoundedOntologyWorkflow() throws IOException {
        String content;
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("skills/ontology-builder/SKILL.md")) {
            assertNotNull(stream, "ontology-builder must be shipped as a classpath skill");
            content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        SkillManifest manifest = new SkillManifestParser(new SkillFrontmatterParser()).parse(content);

        assertNotNull(manifest);
        assertEquals("ontology-builder", manifest.getId());
        assertEquals(EXPECTED_TOOLS, Set.copyOf(manifest.getAllowedTools()));
        assertTrue(content.contains("semantic_ontology_sources()"));
        assertTrue(content.contains("从资料生成本体"));
        assertTrue(content.contains("建立领域模型"));
        assertTrue(content.contains("definitionFormatVersion"));
        assertTrue(content.contains("expectedDraftVersion"));
        assertTrue(content.contains("sha256:<digest>"));
        assertTrue(content.contains("exact supporting quote"));
        assertTrue(content.contains("semantic_ontology_prepare_publish"));
        assertFalse(content.contains("semantic_ontology_publish"),
                "the skill must not invent an autonomous publish tool");
        assertTrue(content.contains("结构和规则校验通过，不证明业务语义"));
    }
}
