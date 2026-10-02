package vip.mate.semantic.core.fact;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssertionPayloadSignatureTest {
    @Test
    void hashCollisionsAndInputOrderCannotChangeSignatureWireOrder() {
        String first = "urn:AaAa", second = "urn:BBBB";
        assertEquals(first.hashCode(), second.hashCode());
        var expected = List.of(first, second, "urn:individual");
        var forward = payload(new LinkedHashSet<>(expected));
        var reverse = new ArrayList<>(expected);
        Collections.reverse(reverse);
        var replay = payload(new LinkedHashSet<>(reverse));
        assertEquals(expected, new ArrayList<>(forward.signatureIris()));
        assertEquals(expected, new ArrayList<>(replay.signatureIris()));
        assertEquals(forward, replay);
    }

    @Test
    void signatureSnapshotIsImmutableAndDetachedFromCaller() {
        var original = new LinkedHashSet<>(List.of("urn:z", "urn:a", "urn:individual"));
        var value = payload(original);
        original.clear();
        assertEquals(
                List.of("urn:a", "urn:individual", "urn:z"),
                new ArrayList<>(value.signatureIris()));
        assertThrows(
                UnsupportedOperationException.class, () -> value.signatureIris().add("urn:new"));
    }

    @Test
    void invalidAndNullSignatureInputsRemainRejected() {
        assertThrows(NullPointerException.class, () -> payload(null));
        var withNull = new LinkedHashSet<String>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> payload(withNull));
        assertThrows(IllegalArgumentException.class, () -> payload(Set.of("relative/path")));
    }

    private AssertionPayload payload(Set<String> signature) {
        return AssertionPayload.classAssertion(
                "ClassAssertion(<urn:Class> <urn:individual>)",
                "urn:individual",
                "<urn:Class>",
                signature);
    }
}
