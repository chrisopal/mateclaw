package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

class PresalesPresentationPinTest {
    @TempDir Path root;

    @Test
    void engineAndBoundSkillChangesInvalidateQueuedS6Pin() throws Exception {
        Files.writeString(root.resolve("SKILL.md"), "version: 1\n");
        Path scripts = Files.createDirectory(root.resolve("scripts"));
        Path converter = scripts.resolve("svg_to_pptx.py");
        Files.writeString(converter, "print('one')\n");
        var skills = mock(SkillRuntimeService.class);
        var bindings = mock(AgentBindingService.class);
        var active =
                ResolvedSkill.builder()
                        .id(41L)
                        .name("ppt-master-plus")
                        .content("Initial instructions")
                        .skillDir(root)
                        .enabled(true)
                        .runtimeAvailable(true)
                        .build();
        when(skills.findActiveSkill("ppt-master-plus", 1L)).thenReturn(active);
        when(bindings.getBoundSkillIds(7L)).thenReturn(Set.of(41L));
        var service =
                new PresalesPresentationService(
                        mock(vip.mate.presales.repository.PresalesArtifactRepository.class),
                        new ObjectMapper(),
                        skills,
                        bindings,
                        mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "trustedRoot", root.toString());
        String before = service.executionPin("1", "7").digest();

        Files.writeString(converter, "print('two')\n");
        String afterEngineChange = service.executionPin("1", "7").digest();
        assertNotEquals(before, afterEngineChange);

        Files.writeString(root.resolve("SKILL.md"), "version: 2\n");
        assertNotEquals(afterEngineChange, service.executionPin("1", "7").digest());
    }
}
