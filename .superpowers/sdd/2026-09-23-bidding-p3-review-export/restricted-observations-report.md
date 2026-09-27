Status: DONE
Verification: PASS
Plan deviations: none

# Restricted Bidding observation preservation

The restricted Bidding runtime now preserves successful, server-authorized source-reader results and pinned `load_skill` / `readSkillFile` snapshots across immediate spill handling and later conversation-window aging/pruning. The observation key is `(conversationId, toolCallId)`; provider-supplied call IDs alone do not grant protection. Generic executions retain their prior spill behavior, and `read_file` remains absent from the Bidding tool allowlist.

The narrow Bidding policy is created from the verified claim in `BiddingEmployeeRuntime.executionOptions`. It names only the two scoped Bidding source readers, `load_skill`, and `readSkillFile` (plus the separately authorized export tool where applicable). The protected result is marked only after a nonblank, non-error result. The exact result is retained; this does not broaden filesystem access or change any pinned skill/schema bytes.

`ToolResultStorage` bounds the in-memory protection registry to 8,192 observations, expires entries after six hours, and removes a conversation's entries in `purgeConversation`. If protected observations leave a turn above its configured aggregate budget, the executor emits `restricted_observation_budget` with `status=insufficient_context`, and window compaction returns a skipped/insufficient-context status rather than silently discarding protected evidence. Protection is temporary metadata, not durable storage.

## Regression evidence

- Before the fix, `RestrictedProjectObservationTest` reproduced the defect: the 8,326-character, 28-block restricted source result was replaced by an inaccessible spill marker; its exact middle and end evidence were not available inline. The initial red run failed the exact response equality assertion.
- After the fix, the same test executes the real `ToolExecutionExecutor` and verifies byte-for-byte source preservation through immediate result handling, age compaction, and later prune/spill eviction. It verifies exact `load_skill` and `output.schema.json` snapshots survive the same age/prune paths, generic outputs remain compactable, and a generic run using the same source tool still spills.
- The test confirms arbitrary `read_file` recovery stays denied, the same call ID in another conversation is not protected, preservation over the 4,000-character test budget produces the explicit insufficient-context event, and conversation purge clears protection metadata.
- `BiddingEmployeeRuntimeTest` verifies the claim-derived preservation set and confirms `read_file` is not allowed.

## Verification

All commands ran with Temurin 21 and `-Dmaven.compiler.proc=full`.

- `mvn -pl mateclaw-server -am -Dtest=RestrictedProjectObservationTest,BiddingEmployeeRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 9 tests, 0 failures/errors.
- `mvn -pl mateclaw-server -am -Dtest='Bidding*Test,PresalesIntegrationTest,PresalesEmployeeRuntimeTest,ToolRegistryProxyTest,ToolExecutionExecutor*Test,ToolResultStorage*Test,ConversationWindowManager*Test,RestrictedProjectObservationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 293 tests, 0 failures/errors.
- `mvn -pl mateclaw-server -am -Dtest=RestrictedProjectObservationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 1 test, 0 failures/errors; this final rerun includes the cleanup assertion.
- `git diff --check` — PASS.

## Limits

The earlier window policy already protected `load_skill`, but `readSkillFile` was not exempt from age/prune compaction. That mismatch is confirmed; whether it caused every observed invalid model output is unproven. The controlled regression demonstrates the observation contract only; it does not establish provider token-limit behavior, fix unrelated structured-output errors, or constitute live bid acceptance. Provider termination metadata, model configuration, skill/schema contents, and production services were not changed or exercised.
