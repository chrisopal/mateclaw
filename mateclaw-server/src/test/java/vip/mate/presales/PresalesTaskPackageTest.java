package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresalesTaskPackageTest {
    @Test
    void exactFilesAndPromptSurviveSerializationWithoutCurrentContractReconstruction()
            throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("refs/detail.md", "original reference 中文\n");
        files.put("SKILL.md", "original instructions\n");
        var original = PresalesTaskPackage.create("S1", files, null);
        files.put("SKILL.md", "mutated input");
        var json = new ObjectMapper();
        var restored = json.readValue(json.writeValueAsBytes(original), PresalesTaskPackage.class);
        assertEquals(original, restored);
        assertTrue(restored.instructions().startsWith("original instructions\n"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> restored.skillFiles().put("evil", "evil"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("SKILL.md", "original instructions\n");
        reversed.put("refs/detail.md", "original reference 中文\n");
        assertEquals(original.digest(), PresalesTaskPackage.create("S1", reversed, null).digest());
        reversed.put("refs/detail.md", "changed reference only");
        var changed = PresalesTaskPackage.create("S1", reversed, null);
        assertEquals(original.skillDigest(), changed.skillDigest());
        assertNotEquals(original.digest(), changed.digest());
    }

    @Test
    void selfClaimedDigestDoesNotAuthenticateChangedFiles() {
        var original = PresalesTaskPackageFixtures.original("S1");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PresalesTaskPackage(
                                original.format(),
                                original.skill(),
                                original.skillName(),
                                original.instructions(),
                                Map.of("SKILL.md", "forged"),
                                null,
                                original.digest()));
    }

    @Test
    void rejectsAmbiguousPathsAndMissingRoot() {
        for (String path : new String[] {"../escape", "a//b", "/absolute", "a/./b", "a\\b"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PresalesTaskPackage.create(
                                    "S1", Map.of("SKILL.md", "original", path, "invalid"), null));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PresalesTaskPackage.create("S1", Map.of("only.txt", "missing"), null));
    }
}
