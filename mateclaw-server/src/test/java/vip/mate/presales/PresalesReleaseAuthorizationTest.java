package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.query.SemanticQueryDtos.EvidenceResult;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.StatementDtos.StatementView;

class PresalesReleaseAuthorizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SemanticProperties semantic = new SemanticProperties();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GraphApplicationService graphs = mock(GraphApplicationService.class);
    private final StatementApplicationService statements = mock(StatementApplicationService.class);
    private final SemanticQueryService queries = mock(SemanticQueryService.class);
    private final PresalesSourceAuthorization sources = mock(PresalesSourceAuthorization.class);
    private final GraphRow graph = new GraphRow();
    private PresalesReleaseAuthorization authorization;

    @BeforeEach
    void setup() {
        semantic.setEnabled(true);
        beans.registerSingleton("graphs", graphs);
        beans.registerSingleton("statements", statements);
        beans.registerSingleton("queries", queries);
        authorization =
                new PresalesReleaseAuthorization(
                        semantic,
                        beans.getBeanProvider(GraphApplicationService.class),
                        beans.getBeanProvider(StatementApplicationService.class),
                        beans.getBeanProvider(SemanticQueryService.class),
                        sources,
                        new PresalesSolutionPolicy(json));
        graph.setOntologyRevisionId("ontology");
        when(graphs.requireGraph("workspace", "graph", true)).thenReturn(graph);
        var fact = mock(StatementView.class);
        when(fact.id()).thenReturn("statement");
        when(fact.revision()).thenReturn(1);
        when(statements.trusted("workspace", "graph")).thenReturn(List.of(fact));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "graphs", "statements"})
    void availabilityIsRecheckedAfterConstructionBeforeAnyBusinessRead(String missing) {
        authorization.requireEnabled();
        if (missing.equals("disabled")) semantic.setEnabled(false);
        else beans.destroySingleton(missing);
        var error = assertThrows(PresalesRejected.class, authorization::requireEnabled);
        assertEquals(409, error.status());
        assertEquals("SEMANTIC_DISABLED", error.code());
        verifyNoInteractions(graphs, statements, queries, sources);
    }

    @Test
    void missingQueryProviderDoesNotChangeAvailabilityForEvidenceFreeHistoricalRelease()
            throws Exception {
        beans.destroySingleton("queries");
        var p = project();
        assertEquals("review", authorization.reviewId("workspace", p, solution(p)));
    }

    @Test
    void ontologyChangesAreReadAgainBeforeTrustOrIndependentReview() throws Exception {
        var p = project();
        assertEquals("review", authorization.reviewId("workspace", p, solution(p)));
        graph.setOntologyRevisionId("changed");
        p.putArray("reviews");
        var error =
                assertThrows(
                        PresalesRejected.class,
                        () -> authorization.reviewId("workspace", p, solution(p)));
        assertEquals("BASELINE_STALE", error.code());
        assertEquals("Graph ontology changed after baseline approval", error.getMessage());
        verify(graphs, times(2)).requireGraph("workspace", "graph", true);
        verify(statements, times(1)).trusted("workspace", "graph");
        verifyNoInteractions(queries, sources);
    }

    @Test
    void withdrawnFactCannotReuseAnEarlierSuccessfulReview() throws Exception {
        var p = project();
        assertEquals("review", authorization.reviewId("workspace", p, solution(p)));
        when(statements.trusted("workspace", "graph")).thenReturn(List.of());
        var error =
                assertThrows(
                        PresalesRejected.class,
                        () -> authorization.reviewId("workspace", p, solution(p)));
        assertEquals("BASELINE_STALE", error.code());
        assertEquals("Semantic fact changed or support withdrawn", error.getMessage());
        verify(statements, times(2)).trusted("workspace", "graph");
        verifyNoInteractions(queries, sources);
    }

    @Test
    void sourceDenialStopsOrderedEvidenceReadsAndKeepsOriginalDenial() throws Exception {
        var p = project();
        ((ObjectNode) p.path("baselines").get(0).path("references").get(0))
                .putArray("evidenceIds")
                .add("first")
                .add("second");
        var evidence = mock(EvidenceResult.class);
        when(evidence.sourceRef()).thenReturn("raw-first");
        when(evidence.textDigest()).thenReturn("digest-first");
        when(queries.evidence("workspace", "graph", "first")).thenReturn(evidence);
        var denied = new PresalesSourceAuthorization.Denied(403, "SOURCE_ACCESS_DENIED", "revoked");
        doThrow(denied).when(sources).currentSource("workspace", "raw-first", "digest-first", true);
        var before = p.deepCopy();
        assertSame(
                denied,
                assertThrows(
                        PresalesSourceAuthorization.Denied.class,
                        () -> authorization.reviewId("workspace", p, solution(p))));
        var order = inOrder(graphs, statements, queries, sources);
        order.verify(graphs).requireGraph("workspace", "graph", true);
        order.verify(statements).trusted("workspace", "graph");
        order.verify(queries).evidence("workspace", "graph", "first");
        order.verify(sources).currentSource("workspace", "raw-first", "digest-first", true);
        order.verifyNoMoreInteractions();
        assertEquals(before, p);
    }

    @Test
    void unknownFitIsSkippedButKnownFitEvidenceIsRecheckedBeforeHumanReview() throws Exception {
        var p = project();
        var s = solution(p);
        s.putArray("fitGapRefs").add("unknown");
        p.withArray("fitGaps").addObject().put("id", "unknown").put("status", "UNKNOWN");
        assertEquals("review", authorization.reviewId("workspace", p, s));
        verifyNoInteractions(queries, sources);
        p.withArray("fitGaps")
                .addObject()
                .put("id", "known")
                .put("status", "FIT")
                .put("graphId", "graph")
                .putArray("evidenceIds")
                .add("fit-evidence");
        s.withArray("fitGapRefs").add("known");
        p.putArray("reviews");
        var unavailable = new IllegalStateException("evidence unavailable");
        when(queries.evidence("workspace", "graph", "fit-evidence")).thenThrow(unavailable);
        assertSame(
                unavailable,
                assertThrows(
                        IllegalStateException.class,
                        () -> authorization.reviewId("workspace", p, s)));
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                json.readTree(
                        """
                {"requirements":[{"id":"requirement","version":1}],
                 "materials":[{"graphId":"graph"}],"fitGaps":[],
                 "baselines":[{"id":"baseline","references":[{"requirementId":"requirement",
                  "requirementVersion":1,"scope":"OUT","graphId":"graph","ontologyRevisionId":"ontology",
                  "statementId":"statement","statementRevision":1,"evidenceIds":[]}]}],
                 "solutions":[{"id":"solution","authorId":"writer","provisional":false,"baselineId":"baseline"}],
                 "reviews":[{"id":"review","kind":"HUMAN_REVIEW","authorId":"reviewer","solutionId":"solution","issues":[]}]}
                """);
    }

    private ObjectNode solution(ObjectNode p) {
        return (ObjectNode) p.path("solutions").get(0);
    }
}
