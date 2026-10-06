package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesArtifactRepository.StoredArtifact;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/**
 * Locks the actual service read boundary before/after extraction; HTTP authority has separate
 * tests.
 */
class PresalesArtifactReadContractTest {
    private static final String PROJECT = "9007199254740993001", RELEASE = "9007199254740993002";
    private static final String FILE = "solution.pptx";
    private static final byte[] BYTES = {0, -1, 127, 34, 10, 0};
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesArtifactRepository repository = mock(PresalesArtifactRepository.class);
    private final PresalesArtifactRenderer renderer = mock(PresalesArtifactRenderer.class);
    private final PresalesProjectRepository projects = mock(PresalesProjectRepository.class);
    private final PresalesService service = service();

    @Test
    void candidateUsesAllThreeKeysAndBothFrozenAndStoredDigestsWithoutMutatingManifest()
            throws Exception {
        var release = release(digest());
        var before = release.deepCopy();
        when(repository.find(PROJECT, RELEASE, FILE)).thenReturn(List.of(stored(digest())));
        candidate(release);
        assertEquals(before, release);
        verify(repository).find(PROJECT, RELEASE, FILE);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(renderer);
    }

    @Test
    void candidateMissingAndDuplicateRowsFailBeforeDecoding() throws Exception {
        var invalid = new StoredArtifact("ignored", "!");
        for (var rows : List.of(List.<StoredArtifact>of(), List.of(invalid, invalid))) {
            when(repository.find(PROJECT, RELEASE, FILE)).thenReturn(rows);
            var error =
                    assertThrows(SemanticApiException.class, () -> candidate(release(digest())));
            assertError(error, 409, "ARTIFACT_MISSING", "Candidate file missing");
        }
    }

    @Test
    void candidateRejectsEitherStoredDigestOrFrozenManifestMismatch() throws Exception {
        for (boolean storedWrong : List.of(true, false)) {
            when(repository.find(PROJECT, RELEASE, FILE))
                    .thenReturn(List.of(stored(storedWrong ? "wrong" : digest())));
            var release = release(storedWrong ? digest() : "wrong");
            var error = assertThrows(SemanticApiException.class, () -> candidate(release));
            assertError(error, 409, "ARTIFACT_DIGEST_MISMATCH", "Candidate bytes changed");
        }
    }

    @Test
    void emptyCandidateManifestKeepsNoReadBehavior() throws Exception {
        candidate(json.createObjectNode().put("id", RELEASE));
        verifyNoInteractions(repository, renderer);
    }

    @Test
    void presentationPreservesRawBytesAndEmptyExpectedDigestStillChecksStoredDigest()
            throws Exception {
        when(repository.find(PROJECT, RELEASE, FILE)).thenReturn(List.of(stored(digest())));
        assertArrayEquals(BYTES, presentation(digest()));
        byte[] result = presentation("");
        assertArrayEquals(BYTES, result);
        result[0] = 42;
        assertArrayEquals(BYTES, presentation(""));
        verifyNoInteractions(renderer);
    }

    @Test
    void presentationMissingAndDuplicateRowsKeepNotFoundBeforeDecoding() throws Exception {
        var invalid = new StoredArtifact("ignored", "!");
        for (var rows : List.of(List.<StoredArtifact>of(), List.of(invalid, invalid))) {
            when(repository.find(PROJECT, RELEASE, FILE)).thenReturn(rows);
            var error = assertThrows(SemanticApiException.class, () -> presentation(digest()));
            assertError(error, 404, "NOT_FOUND", "Presentation artifact not found");
        }
    }

    @Test
    void presentationRejectsStoredOrExpectedMismatchAndNeverSkipsStoredDigest() throws Exception {
        for (String expected : List.of("wrong", "")) {
            when(repository.find(PROJECT, RELEASE, FILE))
                    .thenReturn(List.of(stored(expected.isEmpty() ? "wrong" : digest())));
            var error = assertThrows(SemanticApiException.class, () -> presentation(expected));
            assertError(
                    error,
                    409,
                    "ARTIFACT_DIGEST_MISMATCH",
                    "Presentation artifact integrity failure");
        }
    }

    @Test
    void invalidBase64RetainsStrictDecodeFailureForCandidateAndPresentation() throws Exception {
        when(repository.find(PROJECT, RELEASE, FILE))
                .thenReturn(List.of(new StoredArtifact("ignored", "!")));
        assertThrows(IllegalArgumentException.class, () -> candidate(release(digest())));
        assertThrows(IllegalArgumentException.class, () -> presentation(""));
    }

    private ObjectNode release(String digest) {
        var release = json.createObjectNode().put("id", RELEASE);
        release.putArray("files").addObject().put("filename", FILE).put("sha256", digest);
        return release;
    }

    private static String digest() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
    }

    private static StoredArtifact stored(String digest) {
        return new StoredArtifact(digest, Base64.getEncoder().encodeToString(BYTES));
    }

    private void candidate(ObjectNode release) throws Exception {
        invoke(
                "verifyArtifacts",
                new Class<?>[] {String.class, ObjectNode.class},
                PROJECT,
                release);
    }

    private byte[] presentation(String expected) throws Exception {
        var project = json.createObjectNode().put("id", PROJECT);
        project.putArray("solutions")
                .addObject()
                .put("id", "solution")
                .putObject("presentation")
                .put("artifactId", RELEASE)
                .put("sha256", expected);
        when(projects.findBody("scope", PROJECT, false))
                .thenReturn(Optional.of(project.toString()));
        return service.draftArtifact("scope", PROJECT, "solution", FILE);
    }

    private Object invoke(String name, Class<?>[] signature, Object... args) throws Exception {
        var method = PresalesService.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    private static void assertError(
            SemanticApiException error, int status, String code, String message) {
        assertEquals(status, error.status());
        assertEquals(code, error.code());
        assertEquals(message, error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private PresalesService service() {
        return new PresalesService(
                repository,
                projects,
                json,
                mock(PresalesAccess.class),
                mock(WikiKnowledgeBaseService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(SemanticProperties.class),
                mock(ObjectProvider.class),
                renderer,
                mock(ObjectProvider.class),
                mock(ProjectAuthorityFence.class),
                mock(PresalesSourceAuthorization.class));
    }
}
