Status:DONE Verification:PASS Plan deviations:none

# Task 3 — P2-03 分章写作、批量任务与候选采用

Implemented the chapter writing workflow on the approved outline and frozen material snapshots. Each leaf receives an independent queued task with a persisted server head guard, including the version-zero guard for a chapter with no prior revision. Worker completion stores a candidate only; human edits and adoption update only that chapter head through compare-and-set. Late results become `STALE` and cannot replace a human edit.

The writer skill now has complete `SKILL.md`, input schema, and output schema. Content is restricted to heading, paragraph, list, rectangular table, and authorized material image blocks; HTML, URLs, unknown fields, untrusted local paths, and content over 2 MiB are rejected. The write input includes the confirmed outline/baseline, assigned requirement and scoring projections, material snapshot, prior selected chapter (if present), and server metadata. Responses must explicitly account for every assigned requirement. Citations must exactly match evidence attached to assigned requirements/criteria or quote text in the authorized material snapshot. Assembly follows the confirmed leaf traversal order and records a draft pending review; it does not represent approval.

## Verification

- `mvn -pl mateclaw-server -am -DskipTests -Dmaven.compiler.proc=full compile` — PASS, Java 21 reactor compile.
- `mvn -pl mateclaw-server -am -Dtest='BiddingWritingTest,BiddingContentBlocksTest,BiddingTaskTest,BiddingRetryPolicyTest,BiddingOutlineTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 32 tests: Writing 1, ContentBlocks 3, Task 21, RetryPolicy 2, Outline 5; zero failures/errors/skips.
- `python3 -m json.tool` for both writer schemas — PASS.
- `git diff --check` — PASS.

The writing integration test exercises independent completion of two chapter candidates, then edits one chapter and proves the old candidate cannot complete or be adopted over the new head. Content-block tests reject local file paths and raw HTML, ragged tables, and oversized content.

## Limits

No live model execution, customer bid file, browser acceptance, or P3 review/export flow was run. Those remain outside this engineering slice. Task retry reuses the frozen task snapshot, so a changed outline, selected chapter, material permission, or head guard must be freshly dispatched rather than silently refreshing old input.
