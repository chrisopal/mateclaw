package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import vip.mate.semantic.statement.StatementApplicationService;

class PresalesRequestHashV2Test {
    private final ObjectMapper json = new ObjectMapper();

    private record Vector(String name, String input, String expected) {}

    // Independently generated with Python SHA-256 + utf-16-be/surrogatepass, not production code.
    private static List<Vector> vectors() {
        return List.of(
                new Vector(
                        "isolated property name",
                        "{\"" + (char) 0xd800 + "\":\"x\"}",
                        "v2:b09d4ff79096952d47f1f5744296d4faafff28c8340142432a0b76014b092bff"),
                new Vector(
                        "question property name",
                        "{\"?\":\"x\"}",
                        "v2:fe8d48ba544d439dae2e6d2477f913100b0feafbbd91ab4e39ef9503d774c631"),
                new Vector(
                        "empty",
                        "",
                        "v2:979acfdf276a4938250fb98e234f7cf5683d70fc25a57d98f5526e7afed797f6"),
                new Vector(
                        "ascii",
                        "{\"name\":\"x\"}",
                        "v2:fc5324512edcbc09fc783c120c203f8fcc67b506f19dfa9c055bc8d0ae53041c"),
                new Vector(
                        "bmp",
                        "{\"text\":\"售前\"}",
                        "v2:b0f48654b1a2babca10440d2cb833984d2fd89101f1010a7d08eb3807cad836b"),
                new Vector(
                        "supplementary",
                        "🚀",
                        "v2:182b7eb58083d26c8dfa879d768a8524d66baa4ecab9ee001ea1a0ced0d8a6ef"),
                new Vector(
                        "high",
                        String.valueOf((char) 0xd800),
                        "v2:c3144fbd50f44539f07655743912e3f36c3bed114ffb520b6a3e8e5264f2dca9"),
                new Vector(
                        "low",
                        String.valueOf((char) 0xdc00),
                        "v2:482b87cdcf8eb9560ffe269f26a74d6a23c923dcedb4678d0a5ea529550efa1d"),
                new Vector(
                        "adjacent",
                        String.valueOf((char) 0xd800).repeat(2),
                        "v2:51adf8c43cc5af5fa24087902a21d27dae0c1ca780bc170b42146cad70a2f13d"),
                new Vector(
                        "question",
                        "?",
                        "v2:e8b4d467d41a46b01b3c03c9e2c6007ebf25575cef57cee3f5c043ca7f47bb08"),
                new Vector(
                        "literal escape",
                        "\\ud800",
                        "v2:2619a16a26df9bb8c448406ed42655f21a74cb6c99304a98e19beb1c5f74c051"));
    }

    @TestFactory
    Stream<DynamicTest> matchesIndependentFixedVectors() {
        return vectors().stream()
                .map(
                        v ->
                                DynamicTest.dynamicTest(
                                        v.name(),
                                        () ->
                                                assertEquals(
                                                        v.expected(), candidateHash(v.input()))));
    }

    @Test
    void distinctIsolatedCodeUnitsAndQuestionNeverCollapse() {
        var hashes = new HashSet<String>();
        for (String value :
                List.of(
                        "?",
                        String.valueOf((char) 0xd800),
                        String.valueOf((char) 0xdbff),
                        String.valueOf((char) 0xdc00),
                        String.valueOf((char) 0xdfff))) hashes.add(candidateHash(value));
        assertEquals(5, hashes.size());
    }

    @Test
    void createEnvelopePreservesIsolatedNameVersusQuestion() throws Exception {
        var first =
                new PresalesDtos.Create(
                        "x" + (char) 0xd800 + "y",
                        "customer",
                        null,
                        null,
                        null,
                        null,
                        0,
                        "operation");
        var different =
                new PresalesDtos.Create("x?y", "customer", null, null, null, null, 0, "operation");
        assertNotEquals(
                candidateHash(json.writeValueAsString(first)),
                candidateHash(json.writeValueAsString(different)));
    }

    @Test
    void opaqueExtensionAndPropertyNamesKeepExactUnits() throws Exception {
        String high = String.valueOf((char) 0xd800);
        var first = json.createObjectNode().put("extension", high);
        var different = json.createObjectNode().put("extension", "?");
        assertNotEquals(
                candidateHash(json.writeValueAsString(first)),
                candidateHash(json.writeValueAsString(different)));
        assertNotEquals(
                candidateHash(json.writeValueAsString(json.createObjectNode().put(high, "x"))),
                candidateHash(json.writeValueAsString(json.createObjectNode().put("?", "x"))));
    }

    @Test
    void losslessHashDoesNotNormalizeSerializedEnvelope() throws Exception {
        var original = json.createObjectNode().putNull("optional").put("name", "x");
        var reversed = json.createObjectNode().put("name", "x").putNull("optional");
        var omitted = json.createObjectNode().put("name", "x");
        String first = json.writeValueAsString(original);
        assertNotEquals(candidateHash(first), candidateHash(json.writeValueAsString(reversed)));
        assertNotEquals(candidateHash(first), candidateHash(json.writeValueAsString(omitted)));
        assertNotEquals(
                candidateHash("[\"p\"," + first + "]"), candidateHash("[\"other\"," + first + "]"));
        assertEquals(candidateHash(first), candidateHash(first));
    }

    @Test
    void existingSharedLegacyPrimitiveIsUnchangedAndStillCharacterized() {
        assertEquals(
                StatementApplicationService.hash("?"),
                StatementApplicationService.hash(String.valueOf((char) 0xd800)));
    }

    @Test
    void versionParsingAcceptsOnlyExactKnownFormats() {
        assertEquals(
                PresalesRequestHashV2.Version.LEGACY,
                PresalesRequestHashV2.versionOf("0".repeat(64)));
        assertEquals(
                PresalesRequestHashV2.Version.V2,
                PresalesRequestHashV2.versionOf(candidateHash("request")));
    }

    @Test
    void malformedOrUnknownVersionsNeverFallBackToLegacy() {
        assertThrows(IllegalArgumentException.class, () -> PresalesRequestHashV2.versionOf(null));
        for (String value :
                List.of(
                        "",
                        "0".repeat(63),
                        "0".repeat(65),
                        "A".repeat(64),
                        "g".repeat(64),
                        "v1:" + "0".repeat(64),
                        "v3:" + "0".repeat(64),
                        "V2:" + "0".repeat(64),
                        "v2:" + "0".repeat(63),
                        "v2:" + "0".repeat(65),
                        "v2:" + "A".repeat(64),
                        " " + "0".repeat(64),
                        "0".repeat(64) + "\n",
                        "v2:" + "0".repeat(64) + "\n")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PresalesRequestHashV2.versionOf(value),
                    value);
        }
        assertThrows(NullPointerException.class, () -> candidateHash(null));
    }

    private static String candidateHash(String encodedRequest) {
        return PresalesRequestHashV2.digest(encodedRequest);
    }
}
