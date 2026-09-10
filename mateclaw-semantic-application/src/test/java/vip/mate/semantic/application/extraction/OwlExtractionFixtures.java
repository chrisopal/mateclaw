package vip.mate.semantic.application.extraction;

import java.util.*;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.core.validation.ValidationReport;

/** Application tests isolate governance from the separately tested standard OWL adapter. */
final class OwlExtractionFixtures {
    static final String SUBJECT="urn:test:machine", TYPE="urn:test:Equipment", PROPERTY="urn:test:voltage";
    static final AssertionValidationPort VALID=(o,c,e)->new ValidationReport(List.of());
    static OntologyRevision ontology() {
        String text="Ontology(<urn:test:ontology> Declaration(Class(<urn:test:Equipment>)) Declaration(DataProperty(<urn:test:voltage>)))";
        var document=OntologyDocument.fromText("6","5","urn:test:ontology",Optional.empty(),OntologyDocumentSyntax.FUNCTIONAL,text,LockedImport.digest(List.of()));
        return new OntologyRevision(new OntologyRevisionId("5"),new OntologyId("6"),1,
            new ParsedOntologyDocument(document,"urn:test:ontology",Optional.empty(),List.of(),List.of(),List.of(),List.of()),new BusinessPolicySet("v1",List.of()));
    }
    static AssertionPayload assertion() {
        return AssertionPayload.dataPropertyAssertion("DataPropertyAssertion(<urn:test:voltage> <urn:test:machine> \"220\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
            SUBJECT,PROPERTY,new AssertionPayload.LiteralValue("220","http://www.w3.org/2001/XMLSchema#decimal"),false,Set.of(SUBJECT,PROPERTY,"http://www.w3.org/2001/XMLSchema#decimal"));
    }
}
