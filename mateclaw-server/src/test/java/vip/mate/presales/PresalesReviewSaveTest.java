package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresalesReviewSaveTest {
    @Test
    void producesImmutableTypedIssuesAndResolvesExactSolutionFirst() {
        var calls = new ArrayList<String>();
        var result =
                PresalesReviewSave.decide(
                        new PresalesReviewSave.Draft(
                                "90071992547409999",
                                "review",
                                List.of(
                                        new PresalesReviewSave.IssueDraft("BLOCKER", "OPEN"),
                                        new PresalesReviewSave.IssueDraft("WARNING", "RESOLVED"),
                                        new PresalesReviewSave.IssueDraft("INFO", "ACCEPTED"))),
                        calls::add);
        assertEquals(List.of("90071992547409999"), calls);
        assertEquals(
                List.of(
                        new PresalesReviewSave.Issue(
                                PresalesReviewSave.Severity.BLOCKER,
                                PresalesReviewSave.Status.OPEN),
                        new PresalesReviewSave.Issue(
                                PresalesReviewSave.Severity.WARNING,
                                PresalesReviewSave.Status.RESOLVED),
                        new PresalesReviewSave.Issue(
                                PresalesReviewSave.Severity.INFO,
                                PresalesReviewSave.Status.ACCEPTED)),
                result);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test
    void referenceFailurePrecedesSummaryAndIssueErrors() {
        var missing = new PresalesRejected(404, "NOT_FOUND", "solutions item not found");
        assertSame(
                missing,
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesReviewSave.decide(
                                        new PresalesReviewSave.Draft("foreign", "", null),
                                        id -> {
                                            throw missing;
                                        })));
        assertError("summary required, max 10000 characters", "", null);
        assertError("Review issues required", "s", null);
        assertError(
                "Review issue object required",
                "s",
                Arrays.asList((PresalesReviewSave.IssueDraft) null));
    }

    @Test
    void enumErrorsRemainCaseSensitiveAndFollowIssueThenFieldOrder() {
        assertError(
                "Invalid severity",
                "s",
                List.of(new PresalesReviewSave.IssueDraft("warning", "bad")));
        assertError(
                "Invalid status",
                "s",
                List.of(
                        new PresalesReviewSave.IssueDraft("WARNING", "open"),
                        new PresalesReviewSave.IssueDraft("bad", "OPEN")));
    }

    @Test
    void summaryUsesExistingUtf16LimitAndAllowsEmptyIssueList() {
        assertEquals(
                List.of(),
                PresalesReviewSave.decide(
                        new PresalesReviewSave.Draft("solution", "😀".repeat(5000), List.of()),
                        id -> {}));
        assertError("summary required, max 10000 characters", "😀".repeat(5000) + "x", List.of());
    }

    private void assertError(
            String message, String summary, List<PresalesReviewSave.IssueDraft> issues) {
        var rejection =
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesReviewSave.decide(
                                        new PresalesReviewSave.Draft("solution", summary, issues),
                                        id -> {}));
        assertEquals(400, rejection.status());
        assertEquals("INVALID_REQUEST", rejection.code());
        assertEquals(message, rejection.getMessage());
    }
}
