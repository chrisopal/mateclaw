package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.ontology.OntologyApplicationService;
import vip.mate.semantic.web.OntologyController;

class SemanticConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(DefaultConfiguration.class);

    @EnableConfigurationProperties(SemanticProperties.class)
    @Import({OntologyController.class, OntologyApplicationService.class})
    static class DefaultConfiguration {}

    @Test
    void missingPropertyKeepsModuleDisabled() {
        assertDisabled(context);
    }

    @Test
    void oldUnprefixedPropertyDoesNotEnableModule() {
        assertDisabled(context.withPropertyValues("semantic.enabled=true"));
    }

    private void assertDisabled(ApplicationContextRunner runner) {
        runner.run(
                application -> {
                    assertNull(application.getStartupFailure());
                    assertFalse(application.getBean(SemanticProperties.class).isEnabled());
                    assertTrue(application.getBeansOfType(OntologyController.class).isEmpty());
                    assertTrue(
                            application.getBeansOfType(OntologyApplicationService.class).isEmpty());
                });
    }
}
