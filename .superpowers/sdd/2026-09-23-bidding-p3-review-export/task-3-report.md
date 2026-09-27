Status: DONE
Verification: PASS
Plan deviations: none

## Delivered

- Added human approval bound to the exact candidate artifact id, digest, manuscript, template, format, and whole-book review snapshot. Approval requires the real approver permission, a candidate (never preview), current dependencies, current review evidence, a clean technical gate, and an explicit human inspection record. The decision is immutable and the candidate row transitions by compare-and-set.
- Formal download now rechecks reader authorization, current dependency closure, current review evidence and dispositions, the immutable approval decision, and stored byte digest/size. It returns the original database bytes with a fixed DOCX filename, DOCX content type, and `Cache-Control: no-store`; it does not call the renderer.
- Serialized bidding command mutations and artifact generation on the project row. Review dispatch/result acceptance, finding decisions, todo classification/resolution, approval, and HTTP command mutations use that same lock so approval evidence cannot race these changes.
- Extended the real application integration test through confirmed source upload, outline confirmation, chapter edit and manuscript assembly, claimed reviewer tasks, prepared export refs, real export tool/handler persistence, human approval, candidate HTTP read-back, two formal HTTP read-backs, and a subsequent real outline change that makes the old formal download fail while retaining the original decision.
- Added the `false → true → false` HUMAN_TODO impact-classification regression. The technical-impact state blocks formal download, and reverting it to business-only still does not reactivate the old approval.
- Added a latch-controlled same-active-attempt concurrent export test. Both real tool calls reach the real project lock; they return the same manifest and persist one rendered artifact. The existing capacity tests continue to exercise limits using actual generated DOCX byte sizes.

## Verification

Command:

```sh
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home \
PATH=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home/bin:$PATH \
mvn -pl mateclaw-server -am \
  -Dtest=BiddingApprovalTest,BiddingApprovalEndToEndTest,BiddingArtifactTest,BiddingChangeImpactTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
```

Result: BUILD SUCCESS; 21 tests, 0 failures, 0 errors: `BiddingApprovalTest` 1, `BiddingApprovalEndToEndTest` 1, `BiddingArtifactTest` 3, and `BiddingChangeImpactTest` 16. `git diff --check` passed.

The end-to-end test asserts database bytes equal candidate bytes and both formal downloads byte-for-byte, validates `no-store`, and verifies no renderer calls during formal downloads. It also checks a real todo change blocks the old formal artifact and that the original approval decision remains in history after current outline dependencies change.

## Limits

- The task repository intentionally allows at most one RUNNING task per workspace, so a distinct-task/two-worker same-project race cannot be created through valid claims. No repository or task-claim policy was widened. Concurrency proof therefore covers two concurrent tool invocations for the same valid active attempt, while actual capacity remains covered by sequential generated-byte boundary tests.
- The end-to-end fixture seeds a confirmed analysis-baseline revision as fixture data; source upload/confirmation, writing, review task results, export, approval, download, todo classification, and outline mutation use the real services/API paths. Model execution and Office visual inspection are not claimed.
- Cross-dialect migration execution and the root full-suite gate remain owned by the root task coordinator.
