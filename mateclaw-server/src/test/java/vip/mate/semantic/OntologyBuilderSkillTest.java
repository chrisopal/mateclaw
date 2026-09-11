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
            "semantic_modeling_create_task", "semantic_modeling_get_task",
            "semantic_modeling_submit_proposal", "semantic_modeling_sources",
            "semantic_modeling_read_source", "semantic_ontology_list",
            "semantic_ontology_get", "semantic_ontology_validate", "semantic_ontology_prepare_publish");

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
        var registered=java.util.Arrays.stream(vip.mate.semantic.authoring.OntologyAuthoringTool.class.getMethods())
                .filter(method->method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class))
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        assertTrue(registered.containsAll(EXPECTED_TOOLS), "every skill dependency must be an actual registered tool");
        assertFalse(manifest.getAllowedTools().contains("semantic_ontology_save_draft"), "standard modeling must not route through complete OWL writes");
        assertTrue(content.contains("USER_STATEMENT"));
        assertTrue(content.contains("$clientId"));
        assertTrue(content.contains("expectedDraftVersion"));
        assertTrue(content.contains("sourceDigest"));
        assertTrue(content.contains("remaining") || content.contains("剩余范围"));
        assertTrue(content.contains("semantic_ontology_prepare_publish"));
        assertFalse(content.contains("semantic_ontology_publish"), "the skill must not invent an autonomous publish tool");
        assertTrue(content.contains("NOT_RUN 不得宣称推理通过"));
    }
}
