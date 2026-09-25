Status: DONE
Verification: PASS
Plan deviations: none

## Implementation

- Added additive V214 migrations for `mate_bidding_task.actor_id` in H2, MySQL, and Kingbase. Existing V212/V213 migrations remain unchanged; H2 migration coverage confirms legacy task rows remain readable with a null actor, new actor IDs round-trip, and existing bidding source bytes remain intact.
- Added transactional task enqueue, retry, cancel, completion, and detail/list read paths. Enqueue stores the server-authenticated actor and immutable input/reference/skill/config snapshot; missing actors, missing workspaces, stale authorization, unavailable handlers, and invalid result fingerprints fail closed.
- Added persistent attempt claiming with compare-and-set, opaque attempt tokens, a global limit of two workers, one task per workspace, retry-cycle limits, idempotent history-preserving manual retry, cancellation invalidation, bounded local result-commit retry, boot/deadline interruption recovery, and a bounded scheduler. Source reads use one dedicated bounded executor (one worker, one queued scan); the duplicate SourceService poller was removed. Task detail omits tokens and the stored model config ID while exposing fixed input, attempts, rejection diagnostics, and the effective accepted result reference/payload.
- Added test-only `BiddingFakeRuntime` with injected dependencies. Task lifecycle tests inject only a test result handler and retain repository, transaction, authorization, and token checks.

## Test-first evidence

The initial policy test was run before `BiddingRetryPolicy` existed and failed at test compilation with the expected missing-class error. Implementation followed, then H2 tests exposed and corrected: overclaiming when the scheduler supplied one free slot; duplicate claims for one workspace in a batch; self-matching in the workspace `NOT EXISTS` CAS; and a fourth-attempt assertion that incorrectly rejected an eligible sibling task. The owner/task SQL typo on rejected-result persistence was also fixed and exercised by the revoked-actor late-result test.

## Verification

All commands used Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`.

- Focused Task 5 suite: `mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='BiddingRetryPolicyTest,BiddingTaskTest,BiddingRecoveryTest,BiddingMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` — 26 tests, 0 failures, 0 errors (review round 1; supersedes earlier focused count).
- Combined Bidding and Task 4 compatibility suite: `mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='Bidding*Test,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest' -Dsurefire.failIfNoSpecifiedTests=false test` — 107 tests, 0 failures, 0 errors (review round 1; supersedes earlier combined count).
- `git diff --check` — PASS.

The H2 tests verify concurrent claims, same-workspace exclusion, filling a second global slot, three transient attempts with no fourth attempt for the same task, manual retry numbering/history, cancellation and revocation with late results, database commit retries using one accepted execution, source scanning while DB model slots are occupied, task readback scoping/redaction, and boot/deadline recovery with sibling isolation.

## Gaps

- MySQL and Kingbase migration scripts were reviewed but not executed against those database engines.
- No production employee/model run, browser flow, or multi-instance scheduler behavior was exercised. Multi-instance scheduling remains unsupported by the plan; scheduler acceptance is H2/in-process only.

## Review round 1 fixes (base `432275ab`)

- `BiddingTaskService.run` now handles unchecked runtime failures at the model boundary and immediately persists a permanent failure. A failure after entering `execute` records `resultUnknown=true`; authorization revalidation failures record `resultUnknown=false`. Neither path transparently retries the model. An H2 scheduler-to-fake-runtime test verifies one runtime call, `FAILED`, the persisted unknown-result flag, and no `WAITING_RETRY` state. Failure field order is asserted through JSON readback, including `stopped=false`.
- `complete` now retries only Spring persistence exceptions (`DataAccessException` or `TransactionException`) with the existing 100/300/900 ms delays. Business validation and non-persistence result-handler/programming failures leave the failed transaction and persist a rejection diagnostic plus rejected-output content in a separate transaction. The H2 regression verifies an unchecked handler failure is called once and the output/diagnostic are persisted; the preexisting transient-persistence test verifies accepted execution commits after retry.
- `claimDue` parses and validates input, references, and pinned package files before claiming the row; a `LEFT JOIN` brings missing package rows into diagnostic handling. Malformed JSON, null/non-object snapshots, malformed refs/files, or missing packages are persisted as `TASK_SNAPSHOT_INVALID`, and later valid candidates are still claimable. H2 verifies malformed and missing-package earliest rows fail while a later candidate is claimed. Synthetic fail-closed attempts for missing actor/workspace and invalid snapshots now increment task `attempt_count` together with `attempt_no`, preserving later manual retry numbering.
- `BiddingTaskTest` now exercises `BiddingScheduler -> BiddingFakeRuntime -> TaskService -> H2` end to end with synchronous scheduling and an explicit `Instant` clock. It persists three transient attempts without sleeping, proves there is no fourth runtime call, starts a manual retry cycle, then accepts a result as attempt four. The test fake uses local mocks so it does not require production agent beans in the slim fixture.
- Review-round verification used Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`: focused suite 26/26, combined Bidding and Task 4 compatibility suite 107/107. No MySQL/Kingbase execution or live model/browser behavior is claimed.
