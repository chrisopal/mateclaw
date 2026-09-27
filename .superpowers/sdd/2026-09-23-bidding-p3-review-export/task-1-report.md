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

### Review 1 remediation (fix base `97db5758`)

All three HIGH findings were addressed in the declared Task1 scope:

- Current review read and the technical gate now validate the exact manuscript dependency closure. Both paths recheck every material in that closure against the currently bound independent reviewer. The read model returns `REVIEW_STALE` or `REVIEW_ACCESS_REVOKED` without returning protected finding/material content; the gate rejects stale refs or revoked access. The real H2/API regression confirms writer access remains valid while reviewer page-type access is revoked, then verifies GET review, approval gate, and reviewer task-detail read all fail closed. Reviewer task detail now checks embedded `evidenceSnapshot.materials.items[].ref` as well as pinned `inputRefs` under the actual current reviewer binding.
- `DEFER_SUGGESTION` is rejected for any finding that blocks technical approval, including mandatory category with `SUGGESTION` severity and `STYLE_SUGGESTION` category with `BLOCKER` severity. Ordinary style suggestions still defer.
- COMMERCIAL requirements now create OPEN `UNCLASSIFIED` HUMAN_TODOs and conservatively block approval. `CLASSIFY_HUMAN_TODO` is an approver-only command with explicit boolean impact, exact expected/current todo ref, nonempty validated source evidence, immutable classification revision, project lock/CAS, and operation replay/conflict protection. Unclassified todos cannot be resolved; a business-only classification keeps the todo open and allows technical approval; a technical-impact classification blocks until human resolution. The regression validates the commercial analysis output against the strict schema and exact source quote, and verifies that the model schema supplies no `affectsTechnical` field.

The requested focused command passed:

```sh
export JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home
mvn -pl mateclaw-server -am -Dtest='BiddingReviewTest,BiddingWritingTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test -q
```

Result: exit 0; 14 tests, 0 failures, 0 errors, 0 skipped (BiddingReviewTest 6, BiddingWritingTest 6, BiddingMaterialsTest 2). `git diff --check` is also required before commit. The DEFER regression was observed RED before its guard: one focused test failed because the expected rejection was absent. The reviewer task-detail regression was observed RED before its controller fix: after revoking reviewer page-type access, GET `/tasks/{taskId}` returned 200 instead of 403.

Fix-round changed files: `BiddingCommandService.java`, `BiddingController.java`, `BiddingReviewService.java`, `BiddingReviewTest.java`. The prior independent 168-test backend gate was run by the parent before this fix round; it has not been rerun here. Production/live bid acceptance remains outside this H2/API verification.

### Review 2 remediation (fix base `59cc6726`)

The HIGH todo-provenance finding is fixed in both classification and resolution. Each action revalidates the todo revision's immutable `refs` and payload `sourceRefs` against the current dependency closure before accepting newly submitted evidence. A focused H2 workflow uploads and parses real DOCX source files, confirms the original source set, classifies a todo, replaces the current set through `confirmSet`, verifies replacement evidence is independently current, and then verifies both reclassification and resolution reject the replaced original source with `SOURCE_NOT_CONFIRMED` and append no new revision.

The reviewer task-detail finding is fixed by recognizing immutable `review:` task targets independently of the employee's current role. Task detail always requires the currently bound reviewer and current material access; it cannot fall back to writer ACL after a reviewer rebind. The H2/API workflow rebinds the former reviewer as writer and binds a new reviewer, then verifies GET `/tasks/{taskId}` returns 403 without returning the embedded material snapshot. The normal writer material-access path remains covered by existing material and task read tests.

Focused verification command:

```sh
export JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home
mvn -pl mateclaw-server -am -Dtest='BiddingReviewTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test -q
```

Result: exit 0; 7 tests, 0 failures, 0 errors, 0 skipped. The RED check temporarily removed only the two fix hunks and ran the same focused suite: 7 tests, 2 failures, one because todo reclassification unexpectedly succeeded after source replacement, and one because a former reviewer received HTTP 200 with the historical embedded review snapshot. The fix hunks were restored and the GREEN command above passed. `git diff --check` passed. The parent will rerun the full backend gate and scoped independent review; those are not claimed here.

Fix-round-2 changed files: `BiddingController.java`, `BiddingReviewService.java`, `BiddingReviewTest.java`. The regression uses isolated H2 and DOCX source-reader behavior; no production database or live process was used.
