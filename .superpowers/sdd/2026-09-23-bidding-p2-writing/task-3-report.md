Status: DONE
Verification: PASS
Plan deviations: none

# Task 3 — P2-03 分章写作、批量任务与候选采用

Implemented the chapter writing workflow on the approved outline and frozen material snapshots. Each leaf receives an independent queued task with a persisted server head guard, including the version-zero guard for a chapter with no prior revision. Worker completion stores a candidate only; human edits and adoption update only that chapter head through compare-and-set. Late results become `STALE` and cannot replace a human edit.

The writer skill now has complete `SKILL.md`, input schema, and output schema. Content is restricted to heading, paragraph, list, rectangular table, and authorized material image blocks; HTML, URLs, unknown fields, untrusted local paths, and content over 2 MiB are rejected. The write input includes the confirmed outline/baseline, assigned requirement and scoring projections, material snapshot, prior selected chapter (if present), and server metadata. Responses must explicitly account for every assigned requirement. Citations must exactly match evidence attached to assigned requirements/criteria or quote text in the authorized material snapshot. Assembly follows the confirmed leaf traversal order and records a draft pending review; it does not represent approval.

## Verification

- `mvn -pl mateclaw-server -am -Dtest='BiddingWritingTest,BiddingContentBlocksTest,BiddingTaskTest,BiddingRetryPolicyTest,BiddingOutlineTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, fresh Java 21 reactor compile and 36 tests: Writing 3, ContentBlocks 5, Task 21, RetryPolicy 2, Outline 5; zero failures/errors/skips.
- `python3 -m json.tool` for both writer schemas — PASS.
- `git diff --check` — PASS.

The persisted writing workflow enqueues two chapter tasks, claims and completes them through `BiddingTaskService`, adopts both candidates, then rewrites one chapter. It verifies prior-chapter provenance stays in the frozen task/private revision snapshot but is excluded from current dependency refs, while the versioned head guard remains visible in the GET candidate envelope and still rejects a late task as `STALE`. It then reads back the adopted ref, assembles the selected chapter together with a `HUMAN_EDIT` chapter, and replays adoption and assembly without incrementing or duplicating revisions. Repository policy allows only one running task per workspace, so the two chapter tasks are completed sequentially; simultaneous execution in one workspace is not claimed. Additional regressions cover complete-envelope size limits and the image authorization metadata check.

Prior-chapter references are historical provenance, not current revision dependencies. Candidate currentness and adoption continue to validate current outline/baseline/material refs, and the separately persisted `headGuard` still requires the exact original chapter head. This avoids making a rewritten candidate stale immediately after its own adoption without weakening current-source or late-result checks.

## Limits

No live model execution, customer bid file, browser acceptance, or P3 review/export flow was run. Those remain outside this engineering slice. Current authorized material snapshots contain `WIKI_PAGE` or `PRESALES_RELEASE` text sources; no server-side `IMAGE_ASSET` source exists, so image blocks remain rejected until a trusted image-material snapshot producer is implemented. Task retry reuses the frozen task snapshot, so a changed outline, selected chapter, material permission, or head guard must be freshly dispatched rather than silently refreshing old input.
