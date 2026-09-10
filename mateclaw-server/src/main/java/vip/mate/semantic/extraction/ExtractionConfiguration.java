package vip.mate.semantic.extraction;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.*;
import vip.mate.semantic.source.SourceApplicationService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.semantic.application.extraction.*;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class ExtractionConfiguration {
    public static final class Feature {
        private volatile boolean enabled;
        public Feature(boolean enabled){this.enabled=enabled;}
        public boolean enabled(){return enabled;}
        public void setEnabled(boolean enabled){this.enabled=enabled;}
    }
    @Bean Feature extractionFeature(@Value("${mateclaw.semantic.extraction.enabled:false}") boolean enabled){return new Feature(enabled);}
    @Bean JdbcExtractionRepository extractionRepository(JdbcTemplate jdbc,PlatformTransactionManager tx){return new JdbcExtractionRepository(jdbc,tx);}
    @Bean MateClawAccessAdapter extractionAccess(SemanticAccessService access,GraphApplicationService graphs,JdbcTemplate jdbc){return new MateClawAccessAdapter(access,graphs,jdbc);}
    @Bean MateClawSourceAdapter extractionSources(JdbcTemplate jdbc,GraphApplicationService graphs,MateClawAccessAdapter access,PlatformTransactionManager tx){return new MateClawSourceAdapter(jdbc,graphs,access,tx);}
    @Bean MateClawModelAdapter extractionModel(ObjectProvider<ModelConfigService> models,ObjectProvider<ProviderChatModelFactory> factory,vip.mate.semantic.owl.OwlAssertionAdapter assertions){return new MateClawModelAdapter(models,factory,assertions);}
    @Bean MateClawContextAdapter extractionContext(GraphApplicationService graphs,SemanticDomainMapper domain,MateClawModelAdapter models,JdbcTemplate jdbc){return new MateClawContextAdapter(graphs,domain,models,jdbc);}
    @Bean MateClawSubmissionAdapter extractionSubmission(SourceApplicationService sources,StatementApplicationService statements,MateClawAccessAdapter access,GraphApplicationService graphs,MateClawContextAdapter context,PlatformTransactionManager tx){return new MateClawSubmissionAdapter(sources,statements,access,graphs,context,tx);}
    @Bean ExtractionCoordinator extractionCoordinator(MateClawSourceAdapter sources,MateClawAccessAdapter access,MateClawContextAdapter context,JdbcExtractionRepository repo,MateClawModelAdapter model,Feature feature,vip.mate.semantic.core.fact.AssertionValidationPort assertions){return new ExtractionCoordinator(sources,access,context,repo,model,Clock.systemUTC(),feature::enabled,assertions);}
    @Bean SuggestionSubmissionService extractionSuggestionSubmission(MateClawAccessAdapter access,MateClawContextAdapter context,JdbcExtractionRepository repo,MateClawSubmissionAdapter submission,vip.mate.semantic.core.fact.AssertionValidationPort assertions){return new SuggestionSubmissionService(access,context,repo,submission,assertions);}
    @Bean ExtractionScheduler extractionScheduler(JdbcExtractionRepository repo,ExtractionCoordinator coordinator,Feature feature,@Value("${mateclaw.semantic.extraction.scheduler-enabled:true}")boolean scheduling){return new ExtractionScheduler(repo,coordinator,feature,scheduling);}
}
