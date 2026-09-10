package vip.mate.semantic.reasoning;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.owl.HermitReasoningWorker;

/** Wires the bounded HermiT child-process worker without changing existing context services. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SemanticReasoningProperties.class)
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class SemanticReasoningConfiguration {
    @Bean
    ReasoningPort semanticReasoningWorker(SemanticReasoningProperties properties) {
        if (!properties.isEnabled()) {
            return request -> throwDisabled();
        }
        return new HermitReasoningWorker(
                Duration.ofSeconds(properties.getTimeoutSeconds()),
                properties.getMemoryMb(),
                properties.getMaxConcurrency(),
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                System.getProperty("java.class.path"));
    }

    private static vip.mate.semantic.core.reasoning.ReasoningResult throwDisabled() {
        throw new IllegalStateException("semantic reasoning is disabled");
    }
}
