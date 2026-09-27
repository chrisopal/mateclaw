Status: DONE
Verification: PASS
Plan deviations: none

## Task 3 delivery

- Human approval is bound to one candidate's artifact id/digest, manuscript, prepared template and format refs, and current whole-book review snapshot. The service checks real approver authorization, current dependencies, reviewer gate, source-backed finding dispositions, human todo classification, and explicit human inspection before recording an immutable decision and changing the candidate by compare-and-set.
- Formal download rechecks workspace reader permission, dependency closure, exact immutable decision refs, current review evidence/dispositions, and stored byte digest/size. It returns the original stored DOCX bytes with the fixed filename, DOCX content type, and `no-store`; no renderer call occurs.
- Project-row locking protects command mutations, approval, review dispatch/result acceptance and finding/todo changes. Command routing leaves project update/archive and review dispatch to their existing `REQUIRES_NEW` transactions; other bidding commands use a required transaction and lock. This avoids the nested project-row lock timeout found by the root full gate while preserving serialization.
- The HTTP/application test runs the real source upload/confirmation, manuscript assembly, claimed structured review tasks, prepared export refs, export tool/result handler, artifact database readback, approval, and formal download. It proves a persisted BLOCKER remains blocking after `FIX` on the unchanged candidate, then performs a real targeted writer task, adoption, new manuscript assembly, automatic whole-book review, source-backed `DISMISS_WITH_EVIDENCE`, and approval of a newly generated candidate; forged digest, mismatched template ref and incomplete inspection refusals; preview approval/formal-download refusal; current viewer access to artifact metadata, whole-book review and formal bytes followed by 403 after membership revocation; two concurrent approval operations producing exactly one immutable decision and one `409 ARTIFACT_APPROVAL_CONFLICT`; exact-operation replay returning the same decision; new candidate having no inherited decision; and old formal bytes becoming unavailable with `409 DEPENDENCY_STALE` after a real format-requirements revision and later outline confirmation. Existing coverage also exercises HUMAN_TODO `false → true → false`, exact DB/candidate/formal bytes, no renderer calls on formal download, and immutable decision history after current dependencies change.
- Project update/archive deadlock regression passed after the command transaction routing change. A same-active-attempt concurrent export test still checks one render/one persisted artifact/same manifest bytes.

## Verification

Latest focused command:

```sh
env JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home \
  /opt/homebrew/Cellar/maven/3.9.11/libexec/bin/mvn \
  -pl mateclaw-server -am \
  -Dtest='BiddingApprovalTest,BiddingApprovalEndToEndTest,BiddingProjectTest,BiddingArtifactTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
```

Result: BUILD SUCCESS, 16 tests, 0 failures, 0 errors: `BiddingProjectTest` 11, `BiddingApprovalTest` 1, `BiddingApprovalEndToEndTest` 1, and `BiddingArtifactTest` 3. Surefire XML confirms all four classes ran. `git diff --check` passed. Earlier Task 3 focused suites also passed 21 tests across approval, artifact, and change-impact coverage before this fix round. Root full gate and independent review are pending this scoped commit.

## Limits

- Concurrency coverage exercises two real concurrent approval commands, an HTTP approval racing a real `SAVE_FORMAT_REQUIREMENTS` handler at the project-row lock, and the same-attempt export idempotency path. In the mutation race, approval acquired the real lock first; both commands returned 200, the candidate recorded one approval decision, and formal download then returned `409 DEPENDENCY_STALE`.
- The application fixture seeds the confirmed analysis-baseline revision; source confirmation, review task result acceptance (including the blocking finding), export, approval, download, todo classification and outline mutation use real services/API paths. It does not claim model-provider or Office visual acceptance.
- Migration dialect coverage and the full backend gate remain with the root coordinator.


## Independent review fix round 1

Status: DONE
Verification: PASS
Plan deviations: none

- HIGH FIX-resolution flaw: approval uses the same explicit resolution predicate as the generation gate, so only `DISMISS_WITH_EVIDENCE` and `DEFER_SUGGESTION` resolve findings. `FIX` remains a revision request. The unchanged candidate + BLOCKER + `FIX` HTTP path is covered; approval returns `409 TECHNICAL_REVIEW_BLOCKED`. The E2E then dispatches/completes a targeted writer task, adopts its output, assembles a distinct manuscript (which dispatches new review tasks), completes a new whole-book review, and only then approves a fresh candidate.
- MEDIUM immutable proof: review snapshots now contain the exact `findingDecisionRef` and immutable payload. The test checks the returned ref against the exact saved command result and payload fields (decision, actor, reason, source id/version/block/quote), then reads the immutable approval decision and asserts its persisted `reviewEvidence` exactly equals that validated proof. Approval and formal-read reconstruction re-fetch that revision by exact kind/id/version/digest and revalidate source dependencies/readability and stored block/quote. A separate source-revocation-after-decision scenario was not run in this round; current-source validation is exercised on the successful proof path and stale dependency behavior remains covered elsewhere in the focused suite.
- MEDIUM approval/mutation race: the E2E runs real HTTP handlers concurrently. A spy calls through to the real project-row lock, pauses approval only after the lock is acquired, and verifies the format handler reaches the same lock before release. The actual outcome was approval 200, format save 200, one candidate decision, then formal GET 409 `DEPENDENCY_STALE`.
- TDD RED evidence: before the production predicate change, the focused E2E failed at `BiddingApprovalTest.java:96` because unchanged-candidate approval returned HTTP 200 with status `APPROVED` instead of expected 409 `TECHNICAL_REVIEW_BLOCKED`. The exact command was `env JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home /opt/homebrew/Cellar/maven/3.9.11/libexec/bin/mvn -pl mateclaw-server -am -Dtest='BiddingApprovalEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test`. Surefire recorded it at `mateclaw-server/target/surefire-reports/TEST-vip.mate.bidding.BiddingApprovalEndToEndTest.xml`; that transient report was overwritten by the subsequent GREEN run, so the failure response is preserved here rather than claimed as a retained log file.
- Final focused verification command: `env JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home /opt/homebrew/Cellar/maven/3.9.11/libexec/bin/mvn -pl mateclaw-server -am -Dtest='BiddingApprovalTest,BiddingApprovalEndToEndTest,BiddingReviewTest,BiddingProjectTest,BiddingArtifactTest,BiddingChangeImpactTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test`. Result: BUILD SUCCESS, 39 tests, 0 failures, 0 errors, 0 skipped. This explicitly selected the separate `BiddingApprovalEndToEndTest` top-level class in `BiddingApprovalTest.java`. `git diff --check` passed.

Root full gate and scoped independent re-review remain pending this commit. This fix round does not claim live provider or Office visual acceptance.

## HTTP approval-context seam fix round 2

Status: DONE
Verification: PASS
Plan deviations: none

- Added viewer-authorized `GET /projects/{id}/artifacts/{artifactId}/approval-context`. It scopes artifact lookup to workspace/project, derives artifact/manuscript/template/format refs from that row, and returns `READY` with the server-generated review snapshot, `BLOCKED` with a bounded reason code, `STALE` without a review ref, or `NOT_APPROVABLE` for preview. Authorization failures remain 403 and cross-workspace/project misses remain 404. The GET is read-only and does not invoke the renderer.
- The approval E2E now builds every positive approval reference from authenticated HTTP context GETs; it no longer calls `reviews.approvalEvidence` internally. It exercises blocked blocker and FIX context, blocked human-todo context, preview context, ready owner and viewer contexts, revoked workspace membership (403), revoked reviewer page-type access (403 with no proof in response), cross-workspace artifact access (404), and stale context/POST after TODO and format changes. It checks blocked and ready GETs do not add business revisions or decisions and that ready GET does not call the renderer. Persisted approval proof still equals the exact context proof.
- TDD RED: before adding the route, the authenticated E2E GET expected HTTP 200 and received HTTP 404 `Resource not found`; Surefire path at that run was `mateclaw-server/target/surefire-reports/TEST-vip.mate.bidding.BiddingApprovalEndToEndTest.xml` (transient report overwritten by GREEN).
- Focused verification: `env JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home /opt/homebrew/Cellar/maven/3.9.11/libexec/bin/mvn -pl mateclaw-server -am -Dtest='BiddingApprovalTest,BiddingApprovalEndToEndTest,BiddingReviewTest,BiddingProjectTest,BiddingArtifactTest,BiddingChangeImpactTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test`. Surefire recorded 41 tests, 0 failures, 0 errors, 0 skipped across the seven explicitly selected classes. `git diff --check` passed.

Root full gate and independent scoped re-review remain pending this commit. No Task 4 work or live provider/Office acceptance is claimed.
