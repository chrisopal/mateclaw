package vip.mate.semantic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.mate.semantic.core.ontology.OntologyDocumentPort;
import vip.mate.semantic.ontology.OwlRevisionDocumentMapper;
import vip.mate.semantic.owl.OwlDocumentAdapter;

@Configuration(proxyBeanMethods = false)
public class SemanticOwlConfiguration {
    @Bean
    vip.mate.semantic.owl.OwlAssertionAdapter ontologyAssertions() { return new vip.mate.semantic.owl.OwlAssertionAdapter(); }

    @Bean
    OntologyDocumentPort ontologyDocuments() {
        return new OwlDocumentAdapter();
    }

    @Bean
    OwlRevisionDocumentMapper owlRevisionDocuments(ObjectMapper json, OntologyDocumentPort documents) {
        return new OwlRevisionDocumentMapper(json, documents);
    }
}
