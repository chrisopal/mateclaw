Status: DONE
Verification: PASS
Plan deviations: none

## Task 3 delivery

- Human approval is bound to one candidate's artifact id/digest, manuscript, prepared template and format refs, and current whole-book review snapshot. The service checks real approver authorization, current dependencies, reviewer gate, source-backed finding dispositions, human todo classification, and explicit human inspection before recording an immutable decision and changing the candidate by compare-and-set.
- Formal download rechecks workspace reader permission, dependency closure, exact immutable decision refs, current review evidence/dispositions, and stored byte digest/size. It returns the original stored DOCX bytes with the fixed filename, DOCX content type, and `no-store`; no renderer call occurs.
- Project-row locking protects command mutations, approval, review dispatch/result acceptance and finding/todo changes. Command routing leaves project update/archive and review dispatch to their existing `REQUIRES_NEW` transactions; other bidding commands use a required transaction and lock. This avoids the nested project-row lock timeout found by the root full gate while preserving serialization.
- The HTTP/application test runs the real source upload/confirmation, manuscript assembly, claimed structured review tasks, prepared export refs, export tool/result handler, artifact database readback, approval, and formal download. It covers a persisted BLOCKER finding refusing approval until an evidence-backed `DISMISS_WITH_EVIDENCE`; forged digest, mismatched template ref and incomplete inspection refusals; preview approval/formal-download refusal; current viewer access to artifact metadata, whole-book review and formal bytes followed by 403 after membership revocation; two concurrent approval operations producing exactly one immutable decision and one `409 ARTIFACT_APPROVAL_CONFLICT`; exact-operation replay returning the same decision; new candidate having no inherited decision; and old formal bytes becoming unavailable with `409 DEPENDENCY_STALE` after a real format-requirements revision and later outline confirmation. Existing coverage also exercises HUMAN_TODO `false → true → false`, exact DB/candidate/formal bytes, no renderer calls on formal download, and immutable decision history after current dependencies change.
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

- Concurrency coverage exercises two real concurrent approval commands and the same-attempt export idempotency path. It does not simulate an approval and dependency mutation racing simultaneously; the sequential real format and outline mutations prove that old approval cannot reactivate after current refs change.
- The application fixture seeds the confirmed analysis-baseline revision; source confirmation, review task result acceptance (including the blocking finding), export, approval, download, todo classification and outline mutation use real services/API paths. It does not claim model-provider or Office visual acceptance.
- Migration dialect coverage and the full backend gate remain with the root coordinator.
