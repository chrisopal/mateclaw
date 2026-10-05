package vip.mate.presales;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Human review rules; authorization, transaction and immutable revisions belong to the caller. */
final class PresalesReviewSave {
    private PresalesReviewSave() {}

    enum Severity {
        BLOCKER,
        WARNING,
        INFO
    }

    enum Status {
        OPEN,
        RESOLVED,
        ACCEPTED
    }

    /** Raw enum names are parsed only after solution and summary checks. */
    record IssueDraft(String severity, String status) {}

    record Draft(String solutionId, String summary, List<IssueDraft> issues) {}

    record Issue(Severity severity, Status status) {}

    static List<Issue> decide(Draft draft, Consumer<String> requireProjectSolution) {
        requireProjectSolution.accept(draft.solutionId());
        PresalesProjectItems.text(draft.summary(), "summary", 10000);
        if (draft.issues() == null) throw bad("Review issues required");
        var issues = new ArrayList<Issue>();
        for (var issue : draft.issues()) {
            if (issue == null) throw bad("Review issue object required");
            Severity severity;
            try {
                severity = Severity.valueOf(issue.severity());
            } catch (IllegalArgumentException invalid) {
                throw bad("Invalid severity");
            }
            Status status;
            try {
                status = Status.valueOf(issue.status());
            } catch (IllegalArgumentException invalid) {
                throw bad("Invalid status");
            }
            issues.add(new Issue(severity, status));
        }
        return List.copyOf(issues);
    }

    private static PresalesRejected bad(String message) {
        return new PresalesRejected(400, "INVALID_REQUEST", message);
    }
}
