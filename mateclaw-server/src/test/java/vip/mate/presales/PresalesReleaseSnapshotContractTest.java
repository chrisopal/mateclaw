package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.StatementView;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

/** Characterizes candidate assembly through the service; HTTP authority is covered separately. */
class PresalesReleaseSnapshotContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesArtifactRepository artifacts = mock(PresalesArtifactRepository.class);
    private final PresalesArtifactRenderer renderer = mock(PresalesArtifactRenderer.class);
    private final PresalesProjectRepository projects = mock(PresalesProjectRepository.class);
    private final GraphApplicationService graphService = mock(GraphApplicationService.class);
    private final StatementApplicationService statementService =
            mock(StatementApplicationService.class);
    private final StatementView statement = mock(StatementView.class);
    private final SemanticQueryService queryService = mock(SemanticQueryService.class);
    private final PresalesSourceAuthorization sourceAuthorization =
            mock(PresalesSourceAuthorization.class);
    private final PresalesService service = service();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4294967297",
                "-4294967295",
                "1.5",
                "1.0",
                "\"1.5\"",
                "\"1e0\"",
                "null",
                "true",
                "{}",
                "[]",
                "0"
            })
    void publicationRequiresExactRequirementAndStatementRevisions(String raw) throws Exception {
        for (String target : List.of("current", "requirement", "statement")) {
            var p = project();
            var ref = (ObjectNode) p.path("baselines").get(0).path("references").get(0);
            if (target.equals("current")) {
                ((ObjectNode) p.path("requirements").get(0)).set("version", json.readTree(raw));
            } else {
                ref.set(
                        target.equals("requirement") ? "requirementVersion" : "statementRevision",
                        json.readTree(raw));
            }
            reject(
                    p,
                    409,
                    "BASELINE_STALE",
                    target.equals("statement")
                            ? "Semantic fact changed or support withdrawn"
                            : "Requirement changed after baseline approval");
        }
    }

    @Test
    void twoMissingRequirementRevisionsCannotAuthorizeRelease() throws Exception {
        var p = project();
        ((ObjectNode) p.path("requirements").get(0)).remove("version");
        ((ObjectNode) p.path("baselines").get(0).path("references").get(0))
                .remove("requirementVersion");
        reject(p, 409, "BASELINE_STALE", "Requirement changed after baseline approval");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4294967297",
                "-4294967295",
                "1.5",
                "1.0",
                "\"1.5\"",
                "\"1e0\"",
                "null",
                "true",
                "{}",
                "[]",
                "0"
            })
    void baselineApprovalRequiresExactTrustedStatementRevision(String raw) throws Exception {
        var p = baselineProject();
        ((ObjectNode) p.path("requirements").get(0)).set("statementRevision", json.readTree(raw));
        var before = p.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p));
        assertEquals(409, error.status());
        assertEquals("SEMANTIC_REVIEW_REQUIRED", error.code());
        assertEquals(before, p);
        verifyNoInteractions(renderer, artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "\"1\"", "\"01\"", "\"+1\"", "\" 1 \""})
    void exactHistoricalIntegerTextStillMatchesAtTrustBoundaries(String raw) throws Exception {
        var p = baselineProject();
        ((ObjectNode) p.path("requirements").get(0)).set("statementRevision", json.readTree(raw));
        ((ObjectNode) p.path("requirements").get(0)).set("version", json.readTree(raw));
        var baseline = approveBaseline(p);
        assertEquals(1, baseline.path("references").get(0).path("statementRevision").intValue());
        assertEquals(1, baseline.path("references").get(0).path("requirementVersion").intValue());
        var releaseProject = project();
        ((ObjectNode) releaseProject.path("requirements").get(0))
                .set("version", json.readTree(raw));
        var ref = (ObjectNode) releaseProject.path("baselines").get(0).path("references").get(0);
        ref.set("requirementVersion", json.readTree(raw));
        ref.set("statementRevision", json.readTree(raw));
        assertEquals("PENDING", create(releaseProject).path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "requirements",
                "clarification-status",
                "clarification-answer",
                "clarification-source",
                "scope",
                "graph-blank",
                "graph-unbound",
                "ontology"
            })
    void baselineRejectionsPreserveOrderAndLeaveProjectUnchanged(String defect) throws Exception {
        var p = baselineProject();
        var req = (ObjectNode) p.path("requirements").get(0);
        String code;
        int status = 409;
        switch (defect) {
            case "requirements" -> {
                p.putArray("requirements");
                p.withArray("clarifications").addObject().put("status", "OPEN");
                code = "INVALID_REQUEST";
                status = 400;
            }
            case "clarification-status", "clarification-answer", "clarification-source" -> {
                var c =
                        p.withArray("clarifications")
                                .addObject()
                                .put("status", "ANSWERED")
                                .put("answer", "Answer")
                                .put("answerSourceId", "source");
                if (defect.equals("clarification-status")) c.put("status", "OPEN");
                if (defect.equals("clarification-answer")) c.put("answer", "  ");
                if (defect.equals("clarification-source")) c.remove("answerSourceId");
                req.put("scope", "UNKNOWN");
                code = "OPEN_CLARIFICATIONS";
            }
            case "scope" -> {
                req.put("scope", "UNKNOWN").put("graphId", "");
                code = "SCOPE_UNRESOLVED";
            }
            case "graph-blank", "graph-unbound" -> {
                req.put("graphId", defect.equals("graph-blank") ? "" : "other");
                code = "INVALID_REQUEST";
                status = 400;
            }
            default -> {
                ((ObjectNode) p.path("materials").get(0)).put("ontologyRevisionId", "old");
                req.put("statementId", "unknown");
                code = "BINDING_STALE";
            }
        }
        var before = p.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p));
        assertEquals(status, error.status());
        assertEquals(code, error.code());
        assertEquals(before, p);
        verifyNoInteractions(
                statementService, queryService, sourceAuthorization, artifacts, renderer);
        if (!defect.equals("ontology")) verifyNoInteractions(graphService);
    }

    @Test
    void everyBindingOfTheRequirementGraphMustHaveCurrentOntology() throws Exception {
        var p = baselineProject();
        p.withArray("materials")
                .addObject()
                .put("graphId", "graph")
                .put("ontologyRevisionId", "old");
        var before = p.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p));
        assertEquals("BINDING_STALE", error.code());
        assertEquals(before, p);
        verifyNoInteractions(statementService, queryService, sourceAuthorization);
    }

    @Test
    void invalidSecondRequirementDoesNotLeavePartialApproval() throws Exception {
        var p = baselineProject();
        p.withArray("requirements").add(p.path("requirements").get(0).deepCopy());
        ((ObjectNode) p.path("requirements").get(1)).put("id", "second").put("scope", "UNKNOWN");
        var before = p.deepCopy();
        var value = json.createObjectNode().put("reason", "Checked");
        var input = value.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p, value));
        assertEquals("SCOPE_UNRESOLVED", error.code());
        assertEquals(before, p);
        assertEquals(input, value);
        verify(statementService).trusted("9007199254740993002", "graph");
    }

    @Test
    void lateSourceDenialPreservesOriginalErrorAndDoesNotFreezePartialReferences()
            throws Exception {
        var p = baselineProject();
        when(statement.evidenceIds()).thenReturn(List.of("e1", "e2"));
        for (String id : List.of("e1", "e2"))
            when(queryService.evidence("9007199254740993002", "graph", id))
                    .thenReturn(evidence(id));
        doThrow(new PresalesSourceAuthorization.Denied(403, "ACCESS_REVOKED", "Source denied"))
                .when(sourceAuthorization)
                .currentSource("9007199254740993002", "raw-e2", "digest-e2", true);
        var before = p.deepCopy();
        var value = json.createObjectNode().put("reason", "Checked");
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p, value));
        assertEquals(403, error.status());
        assertEquals("ACCESS_REVOKED", error.code());
        assertEquals("Source denied", error.getMessage());
        assertEquals(before, p);
        assertEquals(json.createObjectNode().put("reason", "Checked"), value);
        var order = inOrder(queryService, sourceAuthorization);
        order.verify(queryService).evidence("9007199254740993002", "graph", "e1");
        order.verify(sourceAuthorization)
                .currentSource("9007199254740993002", "raw-e1", "digest-e1", true);
        order.verify(queryService).evidence("9007199254740993002", "graph", "e2");
        order.verify(sourceAuthorization)
                .currentSource("9007199254740993002", "raw-e2", "digest-e2", true);
    }

    @Test
    void approvedReferenceKeepsWireOrderAndDoesNotUpgradeCustomerConfirmation() throws Exception {
        var p = baselineProject();
        when(statement.ontologyRevisionId()).thenReturn("ontology");
        when(statement.evidenceIds()).thenReturn(List.of("e2", "e1"));
        for (String id : List.of("e2", "e1"))
            when(queryService.evidence("9007199254740993002", "graph", id))
                    .thenReturn(evidence(id));
        var baseline = approveBaseline(p);
        var ref = baseline.path("references").get(0);
        assertEquals(
                """
            {"requirementId":"requirement","requirementVersion":1,"scope":"OUT","graphId":"graph","statementId":"statement","statementRevision":1,"ontologyRevisionId":"ontology","evidenceIds":["e2","e1"],"assertion":null,"sources":[{"id":"e2","snapshotId":"snapshot","sourceKind":"WIKI","sourceRef":"raw-e2","sourceTitle":"Title","exactQuote":"Quote","startCodePoint":0,"endCodePoint":5,"textDigest":"digest-e2"},{"id":"e1","snapshotId":"snapshot","sourceKind":"WIKI","sourceRef":"raw-e1","sourceTitle":"Title","exactQuote":"Quote","startCodePoint":0,"endCodePoint":5,"textDigest":"digest-e1"}]}
            """
                        .strip(),
                json.writeValueAsString(ref));
        assertEquals("reviewer", baseline.path("approvedBy").asText());
        assertEquals("UNCONFIRMED", baseline.path("customerConfirmationStatus").asText());
        assertEquals(1, baseline.path("projectVersion").intValue());
        assertEquals(baseline, p.path("baselines").get(1));
    }

    private vip.mate.semantic.query.SemanticQueryDtos.EvidenceResult evidence(String id) {
        return new vip.mate.semantic.query.SemanticQueryDtos.EvidenceResult(
                id, "snapshot", "WIKI", "raw-" + id, "Title", "Quote", 0, 5, "digest-" + id);
    }

    private ObjectNode baselineProject() throws Exception {
        var p = project().put("version", 1);
        p.putArray("clarifications");
        ((ObjectNode) p.path("requirements").get(0))
                .put("scope", "OUT")
                .put("graphId", "graph")
                .put("statementId", "statement")
                .put("statementRevision", 1);
        ((ObjectNode) p.path("materials").get(0)).put("ontologyRevisionId", "ontology");
        return p;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4294967297",
                "-4294967295",
                "1.5",
                "1.0",
                "\"1.5\"",
                "\"1e0\"",
                "null",
                "true",
                "{}",
                "[]",
                "0"
            })
    void baselineCannotFreezeMalformedRequirementRevision(String raw) throws Exception {
        var p = baselineProject();
        ((ObjectNode) p.path("requirements").get(0)).set("version", json.readTree(raw));
        var before = p.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> approveBaseline(p));
        assertEquals(409, error.status());
        assertEquals("BASELINE_STALE", error.code());
        assertEquals(before, p);
        verifyNoInteractions(renderer, artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "2147483648"})
    void baselineCapturesExactHistoricalProjectRevisionText(String version) throws Exception {
        var p = baselineProject().put("version", version);
        var baseline = approveBaseline(p);
        assertEquals(Long.parseLong(version), baseline.path("projectVersion").longValue());
    }

    private ObjectNode approveBaseline(ObjectNode p) throws Exception {
        return approveBaseline(p, json.createObjectNode().put("reason", "Checked"));
    }

    private ObjectNode approveBaseline(ObjectNode p, ObjectNode value) throws Exception {
        var method =
                PresalesService.class.getDeclaredMethod(
                        "baseline", String.class, ObjectNode.class, ObjectNode.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(service, "9007199254740993002", p, value, "reviewer");
            return value;
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }

    @Test
    void candidateKeepsWireIdentitySelectedOrderAndRiskProjection() throws Exception {
        var p = project();
        var release = create(p);
        var snapshot = (ObjectNode) release.path("handoffSnapshot");
        assertEquals(1, snapshot.path("schemaVersion").asInt());
        assertEquals("9007199254740993001", snapshot.path("engagementId").asText());
        assertEquals(snapshot.path("engagementId"), snapshot.path("caseRef"));
        assertEquals("9007199254740993002", snapshot.path("workspaceId").asText());
        assertEquals("WORKSPACE_REAUTHORIZE_ON_READ", snapshot.path("accessPolicy").asText());
        assertEquals("UNCONFIRMED", snapshot.path("customerConfirmationStatus").asText());
        assertTrue(snapshot.path("historicalClarificationsAvailable").asBoolean());
        assertEquals(p.path("baselines").get(0), snapshot.path("baseline"));
        assertEquals(p.path("solutions").get(0), snapshot.path("solution"));
        assertEquals(p.path("materials"), snapshot.path("materials"));
        assertEquals(p.path("clarifications"), snapshot.path("clarifications"));
        assertEquals(p.path("baselines").get(0).path("references"), snapshot.path("sourceRefs"));
        var expectedRelease = release.deepCopy();
        expectedRelease.remove("handoffSnapshot");
        assertEquals(expectedRelease, snapshot.path("release"));
        assertEquals("PENDING", snapshot.path("release").path("status").asText());
        assertEquals("review", snapshot.path("release").path("reviewId").asText());
        assertEquals(
                "solution.md",
                snapshot.path("release").path("files").get(0).path("filename").asText());
        assertEquals(
                List.of("gap", "fit", "unknown", "config", "extend", "partner"),
                json.convertValue(snapshot.path("fitGaps").findValues("id"), List.class));
        assertEquals(
                List.of("gap", "unknown", "extend", "partner", "partial", "unhandled"),
                json.convertValue(snapshot.path("risksAndUnknowns").findValues("id"), List.class));
        var fields = new ArrayList<String>();
        snapshot.fieldNames().forEachRemaining(fields::add);
        assertEquals(
                List.of(
                        "schemaVersion",
                        "engagementId",
                        "caseRef",
                        "workspaceId",
                        "historicalClarificationsAvailable",
                        "customerConfirmationStatus",
                        "accessPolicy",
                        "baseline",
                        "solution",
                        "release",
                        "fitGaps",
                        "clarifications",
                        "sourceRefs",
                        "materials",
                        "risksAndUnknowns"),
                fields);
    }

    @Test
    void candidateDoesNotShareMutableNodesWithProjectOrRelease() throws Exception {
        var p = project();
        var release = create(p);
        var snapshot = (ObjectNode) release.path("handoffSnapshot");
        var before = snapshot.deepCopy();
        for (String collection :
                List.of("baselines", "solutions", "materials", "clarifications", "fitGaps"))
            ((ObjectNode) p.path(collection).get(0)).put("changed", true);
        ((ObjectNode) p.path("baselines").get(0).path("references").get(0).path("sources").get(0))
                .put("sourceRef", "changed");
        release.put("status", "APPROVED");
        ((ObjectNode) release.path("files").get(0)).put("sha256", "changed");
        assertEquals(before, snapshot);

        var projectBefore = p.deepCopy();
        ((ObjectNode) snapshot.path("sourceRefs").get(0).path("sources").get(0))
                .put("sourceRef", "snapshot only");
        assertEquals(
                "raw",
                snapshot.path("baseline")
                        .path("references")
                        .get(0)
                        .path("sources")
                        .get(0)
                        .path("sourceRef")
                        .asText());
        ((ObjectNode) snapshot.path("baseline")).put("changed", "snapshot only");
        ((ObjectNode) snapshot.path("solution")).put("title", "snapshot only");
        ((ObjectNode) snapshot.path("materials").get(0)).put("kbId", "snapshot only");
        ((ObjectNode) snapshot.path("clarifications").get(0)).put("question", "snapshot only");
        ((ObjectNode) snapshot.path("fitGaps").get(0)).put("status", "FIT");
        assertEquals("GAP", snapshot.path("risksAndUnknowns").get(0).path("status").asText());
        // The project owns the release snapshot itself; only its source collections stay unchanged.
        for (String collection :
                List.of("baselines", "solutions", "materials", "clarifications", "fitGaps"))
            assertEquals(projectBefore.path(collection), p.path(collection));
    }

    @Test
    void missingIndependentReviewStillStopsBeforeRenderingOrStorage() throws Exception {
        var p = project();
        p.putArray("reviews");
        var error = assertThrows(SemanticApiException.class, () -> create(p));
        assertEquals(409, error.status());
        assertEquals("INDEPENDENT_REVIEW_REQUIRED", error.code());
        verifyNoInteractions(renderer, artifacts);
        assertTrue(p.path("releases").isEmpty());
    }

    @Test
    void ineligibleReviewsNeverAuthorizeRelease() throws Exception {
        for (String defect :
                List.of("same-author", "different-solution", "draft-authority", "not-human")) {
            var p = project();
            var review = (ObjectNode) p.path("reviews").get(0);
            switch (defect) {
                case "same-author" -> review.put("authorId", "writer");
                case "different-solution" -> review.put("solutionId", "another");
                case "draft-authority" -> review.put("authority", "UNTRUSTED_DRAFT");
                case "not-human" -> review.put("kind", "AI_REVIEW");
            }
            reject(
                    p,
                    409,
                    "INDEPENDENT_REVIEW_REQUIRED",
                    "A separate reviewer must inspect the exact solution and resolve blockers");
        }
    }

    @Test
    void lastEligibleReviewDeterminesBothDecisionAndRecordedIdentity() throws Exception {
        var p = project();
        var first = (ObjectNode) p.path("reviews").get(0);
        first.withArray("issues").addObject().put("severity", "BLOCKER").put("status", "OPEN");
        var accepted = first.deepCopy().put("id", "last-eligible");
        accepted.putArray("issues")
                .addObject()
                .put("severity", "BLOCKER")
                .put("status", "RESOLVED");
        p.withArray("reviews").add(accepted);
        var ignored =
                first.deepCopy().put("id", "ignored-tail").put("authority", "UNTRUSTED_DRAFT");
        p.withArray("reviews").add(ignored);
        var release = create(p);
        assertEquals("last-eligible", release.path("reviewId").asText());
        assertEquals(
                "last-eligible",
                release.path("handoffSnapshot").path("release").path("reviewId").asText());
    }

    @Test
    void laterBlockerOverridesEarlierCleanReviewAndAcceptedIsNotResolved() throws Exception {
        for (String status : List.of("OPEN", "ACCEPTED")) {
            var p = project();
            var review =
                    ((ObjectNode) p.path("reviews").get(0)).deepCopy().put("id", "later-review");
            review.withArray("issues").addObject().put("severity", "BLOCKER").put("status", status);
            p.withArray("reviews").add(review);
            reject(
                    p,
                    409,
                    "INDEPENDENT_REVIEW_REQUIRED",
                    "A separate reviewer must inspect the exact solution and resolve blockers");
        }
    }

    @Test
    void openWarningsAndLegacyHumanReviewWithoutAuthorityRemainEligible() throws Exception {
        var p = project();
        var review = (ObjectNode) p.path("reviews").get(0);
        assertFalse(review.has("authority"));
        review.withArray("issues").addObject().put("severity", "WARNING").put("status", "OPEN");
        assertEquals("review", create(p).path("reviewId").asText());
    }

    @Test
    void provisionalAndCoverageFailuresKeepPrecedenceOverStaleBaseline() throws Exception {
        var p = project();
        var solution = (ObjectNode) p.path("solutions").get(0);
        solution.remove("provisional");
        solution.put("baselineId", "missing");
        reject(p, 409, "BASELINE_REQUIRED", "Provisional solutions cannot be released");
        solution.put("provisional", false);
        reject(p, 404, "NOT_FOUND", "baselines item not found");
        solution.put("baselineId", "baseline");
        ((ObjectNode) p.path("baselines").get(0).path("references").get(0)).put("scope", "IN");
        p.withArray("baselines").addObject().put("id", "later");
        reject(
                p,
                409,
                "REQUIREMENTS_UNHANDLED",
                "Every in-scope requirement needs an explicit linked response before release");
    }

    @Test
    void baselineFreshnessAndRequirementCountKeepExistingErrors() throws Exception {
        var p = project();
        p.withArray("baselines").addObject().put("id", "later");
        reject(p, 409, "BASELINE_STALE", "Solution uses an older baseline");
        p = project();
        p.withArray("requirements").addObject().put("id", "added");
        reject(p, 409, "BASELINE_STALE", "Requirements added after baseline approval");
        p = project();
        ((ObjectNode) p.path("requirements").get(0)).put("version", 2);
        reject(p, 409, "BASELINE_STALE", "Requirement changed after baseline approval");
    }

    @Test
    void externalChecksStayInterleavedWithEachRequirementAndPrecedeReview() throws Exception {
        var p = project();
        var refs = ((ObjectNode) p.path("baselines").get(0)).withArray("references");
        ((ObjectNode) refs.get(0)).put("graphId", "not-bound");
        refs.add(
                ((ObjectNode) refs.get(0))
                        .deepCopy()
                        .put("requirementId", "later")
                        .put("requirementVersion", 1));
        p.withArray("requirements").addObject().put("id", "later").put("version", 2);
        p.putArray("reviews");
        reject(p, 400, "INVALID_REQUEST", "Evidence graph must be bound to this project");
        p = project();
        ((ObjectNode) p.path("solutions").get(0)).withArray("fitGapRefs").add("missing-fit");
        p.putArray("reviews");
        reject(p, 404, "NOT_FOUND", "fitGaps item not found");
    }

    @Test
    void candidatePreservesRendererInputManifestOrderAndReleaseRebinding() throws Exception {
        var p = project();
        var files = new LinkedHashMap<String, byte[]>();
        files.put("solution.docx", new byte[] {0, -1, 2});
        files.put("solution.md", new byte[] {4, 5});
        when(renderer.render(any())).thenReturn(files);
        var release = create(p);
        var document = ArgumentCaptor.forClass(PresalesArtifactRenderer.Document.class);
        var provisionalId = ArgumentCaptor.forClass(String.class);
        var order = inOrder(renderer, artifacts);
        order.verify(renderer).render(document.capture());
        assertEquals(
                new PresalesArtifactRenderer.Document(
                        "Plan",
                        "solution",
                        false,
                        List.of(new PresalesArtifactRenderer.Section("Scope", "Frozen content")),
                        ""),
                document.getValue());
        int index = 0;
        for (var entry : files.entrySet()) {
            String digest = PresalesArtifactRenderer.digest(entry.getValue());
            order.verify(artifacts)
                    .insert(
                            eq(p.path("id").asText()),
                            provisionalId.capture(),
                            eq(entry.getKey()),
                            eq(digest),
                            eq(Base64.getEncoder().encodeToString(entry.getValue())));
            var item = release.path("files").get(index++);
            assertEquals(entry.getKey(), item.path("filename").asText());
            assertEquals(digest, item.path("sha256").asText());
            assertEquals(entry.getValue().length, item.path("size").asInt());
        }
        assertEquals(files.size(), release.path("files").size());
        assertEquals(1, provisionalId.getAllValues().stream().distinct().count());
        assertNotEquals(provisionalId.getValue(), release.path("id").asText());
        order.verify(artifacts)
                .reassignRelease(
                        p.path("id").asText(),
                        provisionalId.getValue(),
                        release.path("id").asText());
        order.verifyNoMoreInteractions();
    }

    @Test
    void candidateUsesStoredPresentationBeforeAnyInsert() throws Exception {
        var p = project();
        byte[] ppt = new byte[] {0, -1, 37};
        String digest = PresalesArtifactRenderer.digest(ppt);
        ((ObjectNode) p.path("solutions").get(0))
                .putObject("presentation")
                .put("artifactId", "stored-presentation")
                .put("sha256", digest);
        when(renderer.renderWithoutSlides(any())).thenReturn(Map.of("solution.md", new byte[] {8}));
        when(artifacts.findForUpdate(p.path("id").asText(), "stored-presentation", "solution.pptx"))
                .thenReturn(
                        List.of(
                                new PresalesArtifactRepository.StoredArtifact(
                                        digest, Base64.getEncoder().encodeToString(ppt))));
        var release = create(p);
        var order = inOrder(renderer, artifacts);
        order.verify(artifacts)
                .findForUpdate(p.path("id").asText(), "stored-presentation", "solution.pptx");
        order.verify(renderer).renderWithoutSlides(any());
        order.verify(artifacts)
                .insert(
                        eq(p.path("id").asText()),
                        anyString(),
                        eq("solution.md"),
                        anyString(),
                        anyString());
        order.verify(artifacts)
                .insert(
                        eq(p.path("id").asText()),
                        anyString(),
                        eq("solution.pptx"),
                        eq(digest),
                        eq(Base64.getEncoder().encodeToString(ppt)));
        order.verify(artifacts)
                .reassignRelease(
                        eq(p.path("id").asText()), anyString(), eq(release.path("id").asText()));
        order.verifyNoMoreInteractions();
        verify(renderer, never()).render(any());
        assertEquals(
                List.of("solution.md", "solution.pptx"),
                release.path("files").findValuesAsText("filename"));
        assertEquals(digest, release.path("files").get(1).path("sha256").asText());
        assertEquals(ppt.length, release.path("files").get(1).path("size").asInt());
    }

    @Test
    void corruptPresentationFailsBeforeCandidateWrites() throws Exception {
        var p = project();
        ((ObjectNode) p.path("solutions").get(0))
                .putObject("presentation")
                .put("artifactId", "stored-presentation")
                .put("sha256", "original-digest");
        var before = p.deepCopy();
        when(renderer.renderWithoutSlides(any())).thenReturn(Map.of("solution.md", new byte[] {8}));
        when(artifacts.findForUpdate(p.path("id").asText(), "stored-presentation", "solution.pptx"))
                .thenReturn(
                        List.of(new PresalesArtifactRepository.StoredArtifact("wrong", "AA==")));
        var error = assertThrows(SemanticApiException.class, () -> create(p));
        assertEquals(409, error.status());
        assertEquals("ARTIFACT_DIGEST_MISMATCH", error.code());
        assertEquals(before, p);
        verify(artifacts, never())
                .insert(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(artifacts, never()).reassignRelease(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankTextualPresentationIdKeepsExistingCandidateBranch(String artifactId)
            throws Exception {
        var p = project();
        ((ObjectNode) p.path("solutions").get(0))
                .putObject("presentation")
                .put("artifactId", artifactId);
        when(renderer.renderWithoutSlides(any())).thenReturn(Map.of("solution.md", new byte[] {8}));
        var release = create(p);
        assertEquals(List.of("solution.md"), release.path("files").findValuesAsText("filename"));
        verify(renderer).renderWithoutSlides(any());
        verify(renderer, never()).render(any());
        verify(artifacts, never()).find(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"solution.pptx", "slide-1.png", "quality-report.json"})
    void draftPresentationRoutesStoredFormatsWithoutRendering(String filename) throws Exception {
        var p = project();
        byte[] bytes = new byte[] {-1, 0, 3, 9};
        String digest = PresalesArtifactRenderer.digest(bytes);
        var presentation =
                ((ObjectNode) p.path("solutions").get(0))
                        .putObject("presentation")
                        .put("artifactId", "stored-presentation")
                        .put("sha256", digest)
                        .put("qualityReportSha256", digest);
        presentation
                .putArray("slides")
                .addObject()
                .put("filename", "slide-1.png")
                .put("sha256", digest);
        when(projects.findBody("scope", p.path("id").asText(), false))
                .thenReturn(Optional.of(p.toString()));
        when(artifacts.find(p.path("id").asText(), "stored-presentation", filename))
                .thenReturn(
                        List.of(
                                new PresalesArtifactRepository.StoredArtifact(
                                        digest, Base64.getEncoder().encodeToString(bytes))));
        assertArrayEquals(
                bytes, service.draftArtifact("scope", p.path("id").asText(), "solution", filename));
        verifyNoInteractions(renderer);
        verify(artifacts).find(p.path("id").asText(), "stored-presentation", filename);
        verifyNoMoreInteractions(artifacts);
    }

    @Test
    void draftOtherFormatsKeepDraftDocumentAndUnknownFormatError() throws Exception {
        var p = project();
        ((ObjectNode) p.path("solutions").get(0))
                .putObject("presentation")
                .put("artifactId", "stored-presentation");
        when(projects.findBody("scope", p.path("id").asText(), false))
                .thenReturn(Optional.of(p.toString()));
        byte[] bytes = new byte[] {1, 2};
        when(renderer.render(any())).thenReturn(Map.of("solution.md", bytes));
        assertArrayEquals(
                bytes,
                service.draftArtifact("scope", p.path("id").asText(), "solution", "solution.md"));
        var document = ArgumentCaptor.forClass(PresalesArtifactRenderer.Document.class);
        verify(renderer).render(document.capture());
        assertEquals(
                new PresalesArtifactRenderer.Document(
                        "Plan",
                        "solution",
                        true,
                        List.of(new PresalesArtifactRenderer.Section("Scope", "Frozen content")),
                        "UNAPPROVED DRAFT — internal review only"),
                document.getValue());
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () ->
                                service.draftArtifact(
                                        "scope", p.path("id").asText(), "solution", "unknown.zip"));
        assertEquals(404, error.status());
        assertEquals("NOT_FOUND", error.code());
        assertEquals("Unknown artifact format", error.getMessage());
        verifyNoInteractions(artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "preview", "download", "handoff", "exact-handoff"})
    void sourceDenialPrecedesAllArtifactWork(String action) throws Exception {
        var p = project();
        String id = p.path("id").asText();
        when(projects.findBody("scope", id, false)).thenReturn(Optional.of(p.toString()));
        doThrow(new PresalesSourceAuthorization.Denied(403, "SOURCE_UNAVAILABLE", "withdrawn"))
                .when(sourceAuthorization)
                .authorizeMaterials(eq("scope"), any());
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () -> {
                            switch (action) {
                                case "draft" ->
                                        service.draftArtifact(
                                                "scope", id, "solution", "solution.md");
                                case "preview" ->
                                        service.preview("scope", id, "release", "solution.md");
                                case "download" ->
                                        service.artifact("scope", id, "release", "solution.md");
                                case "handoff" -> service.handoff("scope", id);
                                default -> service.handoff("scope", id, "release");
                            }
                        });
        assertEquals(403, error.status());
        assertEquals("SOURCE_UNAVAILABLE", error.code());
        verifyNoInteractions(renderer, artifacts);
    }

    private void reject(ObjectNode p, int status, String code, String message) throws Exception {
        var before = p.deepCopy();
        var error = assertThrows(SemanticApiException.class, () -> create(p));
        assertEquals(status, error.status());
        assertEquals(code, error.code());
        assertEquals(message, error.getMessage());
        assertEquals(before, p);
        verifyNoInteractions(renderer, artifacts);
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                json.readTree(
                        """
                {"id":"9007199254740993001","requirements":[{"id":"requirement","version":1}],"releases":[],
                 "baselines":[{"id":"baseline","references":[{"requirementId":"requirement","requirementVersion":1,
                    "scope":"OUT","graphId":"graph","ontologyRevisionId":"ontology","statementId":"statement",
                    "statementRevision":1,"evidenceIds":[],"sources":[{"sourceRef":"raw"}]}]}],
                 "materials":[{"id":"material","kbId":"kb","graphId":"graph"}],
                 "clarifications":[{"id":"question","question":"Before publication","sourceRefs":["raw"]}],
                 "fitGaps":[{"id":"fit","status":"FIT","graphId":"graph","evidenceIds":["evidence"]},
                            {"id":"config","status":"CONFIG","graphId":"graph","evidenceIds":["evidence"]},
                            {"id":"extend","status":"EXTEND","graphId":"graph","evidenceIds":["evidence"]},
                            {"id":"partner","status":"PARTNER","graphId":"graph","evidenceIds":["evidence"]},
                            {"id":"gap","status":"GAP","graphId":"graph","evidenceIds":["evidence"]},
                            {"id":"unknown","status":"UNKNOWN"},{"id":"unused","status":"GAP"}],
                 "solutions":[{"id":"solution","title":"Plan","authorId":"writer","baselineId":"baseline",
                    "provisional":false,"sections":[{"title":"Scope","text":"Frozen content"}],
                    "fitGapRefs":["gap","fit","unknown","config","extend","partner"],
                    "coverage":{"responses":[{"id":"full","status":"FULL"},
                        {"id":"partial","status":"PARTIAL"},{"id":"unhandled","status":"UNHANDLED"}]}}],
                 "reviews":[{"id":"review","kind":"HUMAN_REVIEW","authorId":"reviewer","solutionId":"solution","issues":[]}]}
                """);
    }

    private ObjectNode create(ObjectNode p) throws Exception {
        var release = json.createObjectNode().put("solutionId", "solution");
        var method =
                PresalesService.class.getDeclaredMethod(
                        "createRelease",
                        String.class,
                        ObjectNode.class,
                        ObjectNode.class,
                        String.class,
                        List.class);
        method.setAccessible(true);
        try {
            var gate =
                    PresalesService.class.getDeclaredMethod(
                            "releaseGate", String.class, ObjectNode.class, ObjectNode.class);
            gate.setAccessible(true);
            var solution = (ObjectNode) p.path("solutions").get(0);
            release.put(
                    "reviewId", (String) gate.invoke(service, "9007199254740993002", p, solution));
            var boundary = new PresalesArtifacts(artifacts, renderer);
            List<PresalesArtifacts.RenderedFile> files;
            try {
                files = boundary.render(boundary.capture(p.path("id").asText(), solution));
            } catch (PresalesRejected rejection) {
                throw new SemanticApiException(
                        rejection.status(), rejection.code(), rejection.getMessage());
            }
            method.invoke(service, "9007199254740993002", p, release, "writer", files);
            return release;
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private PresalesService service() {
        var semantic = new SemanticProperties();
        semantic.setEnabled(true);
        ObjectProvider<GraphApplicationService> graphs = mock(ObjectProvider.class);
        var graph = new GraphRow();
        graph.setOntologyRevisionId("ontology");
        when(graphService.requireGraph("9007199254740993002", "graph", true)).thenReturn(graph);
        when(graphs.getIfAvailable()).thenReturn(graphService);
        when(graphs.getObject()).thenReturn(graphService);
        ObjectProvider<StatementApplicationService> statements = mock(ObjectProvider.class);
        when(statement.id()).thenReturn("statement");
        when(statement.revision()).thenReturn(1);
        when(statementService.trusted("9007199254740993002", "graph"))
                .thenReturn(List.of(statement));
        when(statements.getIfAvailable()).thenReturn(statementService);
        when(statements.getObject()).thenReturn(statementService);
        ObjectProvider<SemanticQueryService> queries = mock(ObjectProvider.class);
        when(queries.getObject()).thenReturn(queryService);
        when(renderer.render(any()))
                .thenReturn(Map.of("solution.md", "frozen".getBytes(StandardCharsets.UTF_8)));
        return new PresalesService(
                artifacts,
                projects,
                json,
                mock(PresalesAccess.class),
                mock(WikiKnowledgeBaseService.class),
                graphs,
                statements,
                semantic,
                queries,
                renderer,
                mock(ObjectProvider.class),
                PresalesCommandTestSupport.fence(),
                sourceAuthorization,
                mock(vip.mate.presales.repository.PresalesRenderTaskRepository.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }
}
