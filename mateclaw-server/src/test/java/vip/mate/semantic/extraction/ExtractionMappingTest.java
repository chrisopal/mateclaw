package vip.mate.semantic.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.owl.OwlAssertionAdapter;

class ExtractionMappingTest {
    private static final String SUBJECT = "urn:mateclaw:extraction:subject";
    private static final String TARGET = "urn:mateclaw:extraction:target";
    private static final String PROPERTY = "urn:test:hasPart";

    @Test
    void mapsTemporaryObjectMentionAndKeepsAssertionAuthority() {
        var request = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT,
                Set.of("urn:test:Equipment"), "machine", null,
                "ObjectPropertyAssertion(<" + PROPERTY + "> <" + SUBJECT + "> <" + TARGET + ">)",
                TARGET, Set.of("urn:test:Part"), "cover", null, "UNKNOWN", null, null,
                List.of(new ExtractionDtos.Quote(0, 7, "machine")));

        var raw = ExtractionMapping.raw(request, new OwlAssertionAdapter());

        assertEquals(AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY, raw.assertion().kind());
        assertEquals(SUBJECT, raw.subject().temporaryRef());
        assertEquals(TARGET, raw.target().temporaryRef());
        assertEquals(PROPERTY, raw.assertion().predicateIri().orElseThrow());
    }

    @Test
    void rejectsMentionFieldsThatDoNotMatchParsedAxiom() {
        var request = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT,
                Set.of(), "machine", null,
                "ObjectPropertyAssertion(<" + PROPERTY + "> <urn:test:other> <" + TARGET + ">)",
                TARGET, Set.of(), "cover", "12", "UNKNOWN", null, null, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> ExtractionMapping.raw(request, new OwlAssertionAdapter()));
    }
    @Test
    void mappedTargetAndHumanDecisionSurvivePersistenceWithTemporaryMentions() {
        var request = resolvedRequest(new IdentityResolution("EXISTING", "Business number EQ-17 in source"),
                new IdentityResolution("NEW", "Different part number despite same name"));
        var adapter = new OwlAssertionAdapter();
        var raw = ExtractionMapping.raw(request, adapter);
        var codec = new ExtractionJson();
        raw = codec.read(codec.write(raw), RawSuggestion.class);
        var scope = new GraphScope(new WorkspaceId("1"), new KnowledgeBaseId("2"), new GraphId("3"));
        var subject = new EntityId("11");
        var target = new EntityId("12");
        var mappedAssertion = adapter.remapIndividuals(raw.assertion().functionalSyntax(),
                Map.of(SUBJECT, "urn:business:1", TARGET, "urn:business:2"));
        var mapped = new StatementContent(scope, new OntologyRevisionId("13"), subject,
                Optional.of(PredicateRef.relation(PROPERTY)), mappedAssertion, raw.validity(), Set.of());
        var suggestion = new Suggestion("suggestion", "task", "attempt", raw, mapped, List.of(), 2, SuggestionStatus.OPEN);
        var view = ExtractionMapping.view(suggestion, null, null,
                Map.of(target, new Entity(target, scope, "urn:business:2", Set.of(), "cover")));
        assertEquals("12", view.targetEntityId());
        assertEquals(TARGET, view.targetIri());
        assertEquals(SUBJECT, view.subjectIri());
        assertEquals(request.subjectResolution(), view.subjectResolution());
        assertEquals(request.targetResolution(), view.targetResolution());
    }

    @Test
    void rejectsInvalidOrUnjustifiedIdentityDecisions() {
        for (var resolution : List.of(new IdentityResolution("AUTO", "name matched"),
                new IdentityResolution("EXISTING", " "), new IdentityResolution("NEW", "x".repeat(1001)),
                new IdentityResolution("UNRESOLVED", "uncertain"))) {
            assertThrows(IllegalArgumentException.class, () -> ExtractionMapping.raw(
                    resolvedRequest(resolution, null), new OwlAssertionAdapter()));
        }
    }

    @Test
    void unresolvedChoicePersistsWithoutCreatingAnEntitySelection() {
        var decision = new IdentityResolution("UNRESOLVED", "Two machines share this name");
        var request = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT, Set.of(), "machine", null,
                "ClassAssertion(<urn:test:Equipment> <" + SUBJECT + ">)",
                null, Set.of(), null, null, "UNKNOWN", null, null, List.of(), decision, null);
        var raw = ExtractionMapping.raw(request, new OwlAssertionAdapter());
        assertEquals(decision, raw.subjectResolution());
        var missingId = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT, Set.of(), "machine", null,
                request.assertionText(), null, Set.of(), null, null, "UNKNOWN", null, null, List.of(),
                new IdentityResolution("NEW", "Distinct machine"), null);
        assertThrows(IllegalArgumentException.class,
                () -> ExtractionMapping.raw(missingId, new OwlAssertionAdapter()));
    }

    private ExtractionDtos.EditRequest resolvedRequest(IdentityResolution subject, IdentityResolution target) {
        return new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT, Set.of(), "machine", "11",
                "ObjectPropertyAssertion(<" + PROPERTY + "> <" + SUBJECT + "> <" + TARGET + ">)",
                TARGET, Set.of(), "cover", "12", "UNKNOWN", null, null, List.of(), subject, target);
    }

}
