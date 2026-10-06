package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresalesPresentationPackageTest {
    @TempDir Path root;

    private void fixture() throws Exception {
        for (String name : PresalesPresentationPackage.REQUIRED_FILES) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, name + " original\n");
        }
    }

    @Test
    void jsonRoundtripAndMaterializationPreserveAllOriginalBytes() throws Exception {
        fixture();
        var files = PresalesPresentationPackage.captureFiles(root);
        var pkg =
                PresalesPresentationPackage.create(
                        "41", "original instructions", files, "runtime-A");
        var json = new ObjectMapper();
        var restored =
                json.readValue(json.writeValueAsBytes(pkg), PresalesPresentationPackage.class);
        assertEquals(pkg, restored);
        files.clear();
        assertEquals(118, restored.filesBase64().size());
        assertThrows(UnsupportedOperationException.class, () -> restored.filesBase64().clear());
        Path output = root.resolve("materialized");
        restored.materialize(output);
        restored.verifyMaterialized(output);
        assertEquals(
                "scripts/pptx_animation_presets.json original\n",
                Files.readString(output.resolve("scripts/pptx_animation_presets.json")));
        Files.writeString(output.resolve("scripts/pptx_animation_presets.json"), "changed");
        assertThrows(IllegalArgumentException.class, () -> restored.verifyMaterialized(output));
    }

    @Test
    void nonPythonResourcesAndRuntimeIdentityContributeToDigest() throws Exception {
        fixture();
        var files = PresalesPresentationPackage.captureFiles(root);
        var first = PresalesPresentationPackage.create("41", "instructions", files, "runtime-A");
        files.put(
                "scripts/pptx_animation_presets.json",
                Base64.getEncoder().encodeToString("changed".getBytes()));
        var changed = PresalesPresentationPackage.create("41", "instructions", files, "runtime-A");
        assertNotEquals(first.digest(), changed.digest());
        assertNotEquals(
                first.digest(),
                PresalesPresentationPackage.create(
                                "41", "instructions", first.filesBase64(), "runtime-B")
                        .digest());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PresalesPresentationPackage(
                                first.format(),
                                first.bindingId(),
                                first.instructions(),
                                files,
                                first.runtimeIdentity(),
                                first.digest()));
    }

    @Test
    void missingLicenseResourceAndSymlinkAreRejected() throws Exception {
        fixture();
        Files.delete(root.resolve("LICENSE"));
        assertThrows(Exception.class, () -> PresalesPresentationPackage.captureFiles(root));
        Files.writeString(root.resolve("LICENSE"), "restored");
        Path target = Files.createTempFile("ac22-outside", ".json");
        try {
            Files.delete(root.resolve("scripts/pptx_animation_presets.json"));
            Files.createSymbolicLink(root.resolve("scripts/pptx_animation_presets.json"), target);
            assertThrows(Exception.class, () -> PresalesPresentationPackage.captureFiles(root));
        } finally {
            Files.delete(target);
        }
    }

    @Test
    void unsafeExtraPathsDuplicateAliasesAndOversizeResourcesAreRejected() throws Exception {
        fixture();
        var files = PresalesPresentationPackage.captureFiles(root);
        for (String name :
                new String[] {
                    "../escape",
                    "/absolute",
                    "scripts/../escape",
                    "scripts//alias",
                    "scripts\\alias",
                    "scripts/.env"
                }) {
            var bad = new HashMap<>(files);
            bad.put(name, "YQ==");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PresalesPresentationPackage.create("41", "instructions", bad, "runtime"),
                    name);
        }
        Files.write(
                root.resolve("scripts/pptx_animation_presets.json"),
                new byte[PresalesPresentationPackage.MAX_FILE_BYTES + 1]);
        assertThrows(Exception.class, () -> PresalesPresentationPackage.captureFiles(root));
    }

    @Test
    void resourceCountAndTotalByteLimitsCannotBeBypassedBySmallIndividualFiles() throws Exception {
        fixture();
        var files = PresalesPresentationPackage.captureFiles(root);
        for (int i = 0; i < 513; i++) files.put("scripts/svg_quality/extra-" + i + ".json", "YQ==");
        assertThrows(
                IllegalArgumentException.class,
                () -> PresalesPresentationPackage.create("41", "instructions", files, "runtime"));
        var large = PresalesPresentationPackage.captureFiles(root);
        String encoded =
                Base64.getEncoder()
                        .encodeToString(new byte[PresalesPresentationPackage.MAX_FILE_BYTES]);
        for (int i = 0; i < 5; i++) large.put("scripts/svg_quality/large-" + i + ".json", encoded);
        assertThrows(
                IllegalArgumentException.class,
                () -> PresalesPresentationPackage.create("41", "instructions", large, "runtime"));
    }

    @Test
    void contentUpdatesPreserveOriginalWhileDisabledUnavailableAndReboundSkillsAreRejected()
            throws Exception {
        fixture();
        var pkg =
                PresalesPresentationPackage.create(
                        "41",
                        "original instructions",
                        PresalesPresentationPackage.captureFiles(root),
                        "runtime");
        var skills = org.mockito.Mockito.mock(vip.mate.skill.runtime.SkillRuntimeService.class);
        var bindings =
                org.mockito.Mockito.mock(vip.mate.agent.binding.service.AgentBindingService.class);
        var service =
                new PresalesPresentationService(
                        org.mockito.Mockito.mock(
                                vip.mate.presales.repository.PresalesArtifactRepository.class),
                        new ObjectMapper(),
                        skills,
                        bindings,
                        org.mockito.Mockito.mock(
                                org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "trustedRoot", root.toString());
        org.mockito.Mockito.when(bindings.getBoundSkillIds(7L))
                .thenReturn(java.util.Set.of(41L, 42L));
        var changedContent =
                vip.mate.skill.runtime.model.ResolvedSkill.builder()
                        .id(41L)
                        .name("ppt-master-plus")
                        .content("new content B")
                        .skillDir(root)
                        .enabled(true)
                        .runtimeAvailable(true)
                        .build();
        org.mockito.Mockito.when(skills.findActiveSkill("ppt-master-plus", 1L))
                .thenReturn(changedContent);
        assertDoesNotThrow(() -> service.requireActivePackage("1", "7", pkg));
        assertEquals("original instructions", pkg.instructions());
        for (var revoked :
                java.util.List.of(
                        vip.mate.skill.runtime.model.ResolvedSkill.builder()
                                .id(41L)
                                .name("ppt-master-plus")
                                .skillDir(root)
                                .enabled(false)
                                .runtimeAvailable(true)
                                .build(),
                        vip.mate.skill.runtime.model.ResolvedSkill.builder()
                                .id(41L)
                                .name("ppt-master-plus")
                                .skillDir(root)
                                .enabled(true)
                                .runtimeAvailable(false)
                                .build(),
                        vip.mate.skill.runtime.model.ResolvedSkill.builder()
                                .id(42L)
                                .name("ppt-master-plus")
                                .skillDir(root)
                                .enabled(true)
                                .runtimeAvailable(true)
                                .build())) {
            org.mockito.Mockito.when(skills.findActiveSkill("ppt-master-plus", 1L))
                    .thenReturn(revoked);
            assertThrows(
                    vip.mate.semantic.web.SemanticApiException.class,
                    () -> service.requireActivePackage("1", "7", pkg));
        }
    }

    @Test
    void extraSelectedResourcesAreCapturedButUnrelatedProjectFilesAreExcluded() throws Exception {
        fixture();
        Files.writeString(root.resolve("scripts/svg_quality/added-data.json"), "new dependency");
        Files.createDirectories(root.resolve("projects/customer"));
        Files.writeString(root.resolve("projects/customer/private.txt"), "excluded");
        var files = PresalesPresentationPackage.captureFiles(root);
        assertEquals(119, files.size());
        assertTrue(files.containsKey("scripts/svg_quality/added-data.json"));
        assertFalse(files.containsKey("projects/customer/private.txt"));
    }
}
