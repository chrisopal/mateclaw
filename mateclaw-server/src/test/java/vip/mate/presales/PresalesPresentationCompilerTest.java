package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Optional real installed-skill smoke test; no model or network calls. */
class PresalesPresentationCompilerTest {
    @TempDir Path temporary;

    @Test
    @EnabledIfEnvironmentVariable(named = "PRESALES_PPT_SKILL_ROOT", matches = ".+")
    void compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:pptcompiler;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE mate_presales_artifact(project_id VARCHAR,release_id VARCHAR,filename VARCHAR,digest VARCHAR,content_base64 CLOB,PRIMARY KEY(project_id,release_id,filename))");
        var json = new ObjectMapper();
        Path engine = temporary.resolve("installed");
        var originalFiles =
                PresalesPresentationPackage.captureFiles(
                        Path.of(System.getenv("PRESALES_PPT_SKILL_ROOT")));
        PresalesPresentationPackage.create("41", "fixture", originalFiles, "fixture")
                .materialize(engine);
        var skills = mock(vip.mate.skill.runtime.SkillRuntimeService.class);
        var bindings = mock(vip.mate.agent.binding.service.AgentBindingService.class);
        when(skills.findActiveSkill("ppt-master-plus", 1L))
                .thenReturn(
                        vip.mate.skill.runtime.model.ResolvedSkill.builder()
                                .id(41L)
                                .name("ppt-master-plus")
                                .content("Current descriptor is not the frozen source")
                                .skillDir(engine)
                                .enabled(true)
                                .runtimeAvailable(true)
                                .build());
        when(bindings.getBoundSkillIds(7L)).thenReturn(Set.of(41L));
        var service =
                new PresalesPresentationService(
                        new vip.mate.presales.repository.PresalesArtifactRepository(jdbc),
                        json,
                        skills,
                        bindings,
                        new DataSourceTransactionManager(ds));
        ReflectionTestUtils.setField(service, "trustedRoot", engine.toString());
        ReflectionTestUtils.setField(service, "python", System.getenv("PRESALES_PPT_PYTHON"));
        var result = json.createObjectNode();
        var solution = result.putObject("solution").put("title", "Compiler fixture");
        solution.putObject("presentation")
                .putArray("slides")
                .addObject()
                .put("title", "范围")
                .put(
                        "svg",
                        """
   <svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
    <rect x="0" y="0" width="1280" height="720" fill="#FFFFFF"/>
    <rect x="64" y="64" width="8" height="48" fill="#0966D9"/>
    <text x="96" y="101" font-size="36" font-family="Arial" fill="#14243A">范围与待确认事项</text>
    <text x="64" y="200" font-size="26" font-family="Arial" fill="#14243A">两条产线试点；预算与日期待客户确认。</text>
    <text x="64" y="672" font-size="18" font-family="Arial" fill="#526075">未批准草稿</text>
   </svg>
   """);
        var slides =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        solution.path("presentation").path("slides");
        slides.addObject()
                .put("title", "Path and tspan")
                .put(
                        "svg",
                        """
                <svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
                <rect x="0" y="0" width="1280" height="720" fill="#FFFFFF"/>
                <path d="M 80 240 L 340 240 L 210 410 Z" fill="#0966D9"/>
                <text x="64" y="100" font-family="Arial" font-size="36">Original<tspan dx="12">package A</tspan></text>
                <text x="64" y="672" font-family="Arial" font-size="18">未批准草稿</text>
                </svg>
                """);
        slides.addObject()
                .put("title", "Geometry")
                .put(
                        "svg",
                        """
                <svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
                <rect x="0" y="0" width="1280" height="720" fill="#FFFFFF"/>
                <circle cx="160" cy="300" r="60" fill="#0966D9"/>
                <ellipse cx="360" cy="300" rx="80" ry="40" fill="#0966D9"/>
                <line x1="500" y1="250" x2="650" y2="350" stroke="#0966D9" stroke-width="4"/>
                <polygon points="740,250 840,350 700,350" fill="#0966D9"/>
                <text x="64" y="100" font-family="Arial" font-size="36">Editable geometry</text>
                <text x="64" y="672" font-family="Arial" font-size="18">未批准草稿</text>
                </svg>
                """);
        var uncompiled = result.deepCopy();
        var authored = result.deepCopy();
        service.prepare(result, "1", "p", "run");
        assertEquals("ppt-master-plus", solution.path("presentation").path("skill").asText());
        assertFalse(solution.path("presentation").path("slides").get(0).has("svg"));
        var bytes =
                Base64.getDecoder()
                        .decode(
                                jdbc.queryForObject(
                                        "SELECT content_base64 FROM mate_presales_artifact WHERE filename='solution.pptx'",
                                        String.class));
        assertEquals(
                PresalesArtifactRenderer.digest(bytes),
                solution.path("presentation").path("sha256").asText());
        try (var deck =
                new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertEquals(3, deck.getSlides().size());
            assertTrue(deck.getSlides().get(1).getXmlObject().xmlText().contains("package A"));
            assertTrue(
                    deck.getSlides().get(0).getShapes().stream()
                            .anyMatch(
                                    s -> s instanceof org.apache.poi.xslf.usermodel.XSLFTextShape));
        }
        var original = service.capturePackage("1", "7");
        assertTrue(original.instructions().contains(Files.readString(engine.resolve("SKILL.md"))));
        assertFalse(
                original.instructions().contains("Current descriptor is not the frozen source"));
        var restored =
                json.readValue(json.writeValueAsBytes(original), PresalesPresentationPackage.class);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/h2/V222__presales_task_packages.sql"))
                .execute(ds);
        var packages = new vip.mate.presales.repository.PresalesProjectRepository(jdbc);
        var originalTaskPackage =
                PresalesTaskPackage.create(
                        "S6",
                        java.util.Map.of("SKILL.md", PresalesModelAdapter.readSkill("S6")),
                        restored);
        var row =
                new vip.mate.presales.repository.PresalesProjectRepository.TaskPackageRow(
                        "1",
                        "p",
                        "task",
                        "frozen-run",
                        "1",
                        "7",
                        originalTaskPackage.digest(),
                        json.writeValueAsString(originalTaskPackage));
        packages.insertTaskPackage(row);
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> packages.insertTaskPackage(row));
        var storedPackage = packages.findTaskPackage("1", "p", "task", "frozen-run").orElseThrow();
        var persistedOriginal = json.readValue(storedPackage.bodyJson(), PresalesTaskPackage.class);
        assertEquals(originalTaskPackage, persistedOriginal);
        var persistedPresentation = persistedOriginal.presentation();
        Files.writeString(
                engine.resolve("scripts/pptx_animation_presets.json"),
                "\n",
                java.nio.file.StandardOpenOption.APPEND);
        var nonPythonUpdate = service.capturePackage("1", "7");
        assertNotEquals(original.digest(), nonPythonUpdate.digest());
        Files.writeString(
                engine.resolve("SKILL.md"),
                "\nFrozen package B marker\n",
                java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(
                engine.resolve("scripts/svg_to_pptx.py"),
                "\n# Frozen package B marker\n",
                java.nio.file.StandardOpenOption.APPEND);
        var updated = service.capturePackage("1", "7");
        assertNotEquals(original.instructions(), updated.instructions());
        assertNotEquals(original.digest(), updated.digest());
        // If the actual prepare path consults the installation, it now fails rather than
        // accidentally producing a visually identical deck from B.
        Files.writeString(
                engine.resolve("scripts/svg_to_pptx.py"),
                "raise RuntimeError('live B must never run')\n");
        service.prepare(
                uncompiled, "1", "p", "frozen-run", original.digest(), "7", persistedPresentation);
        var manifest = uncompiled.path("solution").path("presentation");
        assertEquals(original.digest(), manifest.path("packageSha256").asText());
        assertEquals(original.runtimeIdentity(), manifest.path("runtimeIdentity").asText());
        assertEquals(
                PresalesArtifactRenderer.digest(original.bytes("scripts/svg_to_pptx.py")),
                manifest.path("engineSha256").asText());
        var frozenBytes =
                Base64.getDecoder()
                        .decode(
                                jdbc.queryForObject(
                                        "SELECT content_base64 FROM mate_presales_artifact WHERE release_id='frozen-run' AND filename='solution.pptx'",
                                        String.class));
        try (var deck =
                new org.apache.poi.xslf.usermodel.XMLSlideShow(
                        new ByteArrayInputStream(frozenBytes))) {
            assertEquals(3, deck.getSlides().size());
            assertTrue(deck.getSlides().get(1).getXmlObject().xmlText().contains("package A"));
            assertTrue(
                    deck.getSlides().get(0).getShapes().stream()
                            .anyMatch(
                                    s -> s instanceof org.apache.poi.xslf.usermodel.XSLFTextShape));
        }
        var wrongRuntime =
                PresalesPresentationPackage.create(
                        original.bindingId(),
                        original.instructions(),
                        original.filesBase64(),
                        "unavailable-runtime");
        var mismatch =
                assertThrows(
                        vip.mate.semantic.web.SemanticApiException.class,
                        () ->
                                service.prepare(
                                        authored.deepCopy(),
                                        "1",
                                        "p",
                                        "wrong-runtime",
                                        wrongRuntime.digest(),
                                        "7",
                                        wrongRuntime));
        assertEquals("PPT_RUNTIME_CHANGED", mismatch.getMessage());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_artifact WHERE release_id='wrong-runtime'",
                        Integer.class));
        Files.writeString(
                engine.resolve("scripts/svg_quality/checker.py"),
                "\ntry:\n    import ac22_deliberately_missing_dependency\nexcept ImportError:\n    ac22_missing_binding = None\n",
                java.nio.file.StandardOpenOption.APPEND);
        var degraded =
                assertThrows(
                        vip.mate.semantic.web.SemanticApiException.class,
                        () -> service.capturePackage("1", "7"));
        assertEquals("PPT_QUALITY_OR_COMPILER_FAILED", degraded.getMessage());
        when(bindings.getBoundSkillIds(7L)).thenReturn(Set.of());
        assertThrows(
                vip.mate.semantic.web.SemanticApiException.class,
                () -> service.requireActivePackage("1", "7", original));
        assertThrows(
                vip.mate.semantic.web.SemanticApiException.class,
                () ->
                        service.prepare(
                                uncompiled, "1", "p", "revoked", original.digest(), "7", original));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_artifact WHERE release_id='revoked'",
                        Integer.class));
    }
}
