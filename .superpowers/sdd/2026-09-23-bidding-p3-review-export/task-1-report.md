Status: DONE
Verification: PASS
Plan deviations: none

Implemented the independent technical review vertical slice. Review tasks use the separately bound reviewer and exact immutable manuscript/chapter refs, with chapter-specific tasks plus a cross-chapter task. Reviewer material snapshots recheck the reviewer’s current wiki/page-type or presales access; unreadable sources block dispatch while leaving an assembled draft intact. The current-review GET endpoint rechecks scoped access and returns task status, coverage, findings, disposition, and HUMAN_TODO state. The server derives technical approval blockers; the closed review schema rejects approval and human-todo resolution fields. Finding dispositions require source-backed evidence where applicable, FIX can seed a targeted chapter revision, and revision candidates remain unadopted. Human todos retain a real owner and separate status from technical approval. Existing BiddingTaskService enqueue/claim/result-handler persistence was reused and verified; it required no code change.

Automatic review runs after assemble commits. A reviewer/source dispatch failure leaves the immutable draft persisted, records a safe reason code for the read model, and emits a sanitized log entry without exception text. Adopting a revised chapter and assembling again dispatches chapter and cross-chapter reviews for the new exact manuscript; the old review cannot satisfy the current manuscript gate.

Verification command:

```sh
export JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home
mvn -pl mateclaw-server -am -Dtest='BiddingReviewTest,BiddingWritingTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
```

Result: BUILD SUCCESS; 13 tests, 0 failures, 0 errors. BiddingReviewTest 5/5 covers mandatory blocker logic, strict exact chapter coverage and rejection of model approval, scoped read, mandatory-proof dismissal rejection, and an H2-backed reviewer workflow with persisted tasks/results/findings, actual source-backed dismissal, deferred suggestion, owner-bound HUMAN_TODO resolution with persisted evidence/reason, technical gating, targeted FIX selection, stale-manuscript rejection, and same-employee rejection. BiddingWritingTest 6/6 covers unbound-reviewer assemble draft persistence, targeted revision remaining a candidate, assemble-triggered review, adoption and new-manuscript re-review with old review invalidation, plus draft persistence and safe `SOURCE_UNREADABLE` read-back when automatic dispatch fails. BiddingMaterialsTest 2/2 covers reviewer wiki/page-type read ACL and current binding. `git diff --check` passed.

The brief names BiddingDependenciesTest, but no such test file exists in this checkout; it was not run. These H2/API and task read-back tests do not constitute production bid acceptance.

Changed files: BiddingCommandService.java, BiddingController.java, BiddingMaterials.java, BiddingReviewService.java, BiddingSkillValidator.java, BiddingWritingService.java; bidding-technical-review skill bundle; BiddingReviewTest.java, BiddingMaterialsTest.java, BiddingWritingTest.java.

Remaining limits: Task 2 export/material APIs are outside this task. No live database or process was used.
