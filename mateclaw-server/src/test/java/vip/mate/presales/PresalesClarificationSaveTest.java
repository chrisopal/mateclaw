package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresalesClarificationSaveTest {
    private PresalesClarificationSave.Draft draft(
            String question, String status, String answer, String source) {
        return new PresalesClarificationSave.Draft(
                question, status, "requirement", "owner", answer, source);
    }

    @Test
    void answeredResolvesReferencesBeforeRecordingServerIdentity() {
        var calls = new ArrayList<String>();
        var result =
                PresalesClarificationSave.decide(
                        draft("q", "ANSWERED", "a", "source"),
                        "actor",
                        id -> calls.add("requirement:" + id),
                        id -> {
                            calls.add("owner:" + id);
                            return "resolved";
                        });
        assertEquals(List.of("requirement:requirement", "owner:owner"), calls);
        assertEquals(PresalesClarificationSave.Status.ANSWERED, result.status());
        assertEquals("resolved", result.ownerId());
        assertEquals("actor", result.answeredBy());
        assertDoesNotThrow(() -> LocalDateTime.parse(result.answeredAt()));
    }

    @Test
    void openDoesNotRequireAnswerAndBlankReferencesDoNotCallCollaborators() {
        var result =
                PresalesClarificationSave.decide(
                        new PresalesClarificationSave.Draft("q", "OPEN", "", " ", "", ""),
                        "actor",
                        id -> fail("unexpected requirement lookup"),
                        id -> {
                            fail("unexpected owner lookup");
                            return null;
                        });
        assertEquals(PresalesClarificationSave.Status.OPEN, result.status());
        assertNull(result.ownerId());
        assertNull(result.answeredBy());
        assertNull(result.answeredAt());
    }

    @Test
    void domainValidationAndCollaboratorFailuresKeepTheirOrder() {
        var calls = new ArrayList<String>();
        var question =
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesClarificationSave.decide(
                                        draft("", "invalid", "", ""),
                                        "actor",
                                        calls::add,
                                        id -> {
                                            calls.add(id);
                                            return id;
                                        }));
        assertEquals("question required, max 5000 characters", question.getMessage());
        assertTrue(calls.isEmpty());
        var status =
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesClarificationSave.decide(
                                        draft("q", "answered", "", ""),
                                        "actor",
                                        calls::add,
                                        id -> {
                                            calls.add(id);
                                            return id;
                                        }));
        assertEquals("Invalid status", status.getMessage());
        assertTrue(calls.isEmpty());
        var missing = new PresalesRejected(404, "NOT_FOUND", "missing");
        assertSame(
                missing,
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesClarificationSave.decide(
                                        draft("q", "ANSWERED", "", ""),
                                        "actor",
                                        id -> {
                                            throw missing;
                                        },
                                        id -> {
                                            fail("owner after missing reference");
                                            return id;
                                        })));
        var denied = new PresalesRejected(400, "INVALID_OWNER", "denied");
        assertSame(
                denied,
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesClarificationSave.decide(
                                        draft("q", "ANSWERED", "", ""),
                                        "actor",
                                        calls::add,
                                        id -> {
                                            throw denied;
                                        })));
        assertEquals(List.of("requirement"), calls);
    }

    @Test
    void utf16LengthLimitsMatchExistingTextPolicy() {
        var valid = draft("😀".repeat(2500), "ANSWERED", "a".repeat(10000), "s".repeat(2000));
        assertDoesNotThrow(
                () -> PresalesClarificationSave.decide(valid, "actor", id -> {}, id -> id));
        for (var invalid :
                List.of(
                        draft(valid.question() + "x", "OPEN", "", ""),
                        draft("q", "ANSWERED", valid.answer() + "x", "s"),
                        draft("q", "ANSWERED", "a", valid.answerSourceId() + "x"))) {
            var error =
                    assertThrows(
                            PresalesRejected.class,
                            () ->
                                    PresalesClarificationSave.decide(
                                            invalid, "actor", id -> {}, id -> id));
            assertEquals(400, error.status());
            assertEquals("INVALID_REQUEST", error.code());
        }
    }
}
