package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.tool.SemanticTool;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.security.SemanticPrincipalResolver;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.repository.ToolMapper;
import vip.mate.i18n.I18nService;
import java.util.List;
import java.util.Set;

@org.springframework.context.annotation.Import(SemanticFeatureFlagTest.ToolMappers.class)
class SemanticFeatureFlagTest extends SemanticHttpFixture {
    @org.springframework.boot.test.context.TestConfiguration
    @org.mybatis.spring.annotation.MapperScan("vip.mate.tool.repository")
    static class ToolMappers {}
    @Autowired ToolMapper realToolMapper;
    @Autowired ApplicationContext application;
    @Autowired SemanticTool tool;

    @Test
    void disabledModuleHasNoDiscoverableToolBean() {
        new ApplicationContextRunner().withUserConfiguration(SemanticTool.class)
                .withBean(SemanticPrincipalResolver.class, () -> mock(SemanticPrincipalResolver.class))
                .withBean(SemanticQueryService.class, () -> mock(SemanticQueryService.class))
                .run(context -> {
                    assertFalse(context.containsBean("semanticTool"));
                    ToolMapper mapper=mock(ToolMapper.class);
                            assertTrue(new ToolRegistry(context,mapper,mock(I18nService.class)).getEnabledTools().isEmpty());
                });
    }

    @Test
    void migratedDisabledRowBlocksRegistryUntilExplicitlyEnabledAndAgentAllowed() {
        Boolean enabled=jdbc.queryForObject("SELECT enabled FROM mate_tool WHERE bean_name='semanticTool' AND deleted=0",Boolean.class);
        assertEquals(Boolean.FALSE,enabled);
        ToolRegistry registry=new ToolRegistry(application,realToolMapper,mock(I18nService.class));
        assertFalse(registry.getEnabledTools().contains(tool));
        assertTrue(registry.getAllToolBeanSetForAdmin().withAllowedToolsOnly(Set.of("semanticTool")).callbacks().size()>0);
        jdbc.update("UPDATE mate_tool SET enabled=TRUE WHERE bean_name='semanticTool'");
        try {
            assertEquals(Boolean.TRUE,jdbc.queryForObject("SELECT enabled FROM mate_tool WHERE bean_name='semanticTool'",Boolean.class));
            assertTrue(registry.getEnabledTools().contains(tool));
            var enabledSet=registry.getEnabledToolSet();
            assertEquals(1,enabledSet.withAllowedToolsOnly(Set.of("semanticTool")).callbacks().size());
            assertTrue(enabledSet.withAllowedToolsOnly(Set.of("unrelatedTool")).callbacks().isEmpty());
        } finally {
            jdbc.update("UPDATE mate_tool SET enabled=FALSE WHERE bean_name='semanticTool'");
        }
    }
}
