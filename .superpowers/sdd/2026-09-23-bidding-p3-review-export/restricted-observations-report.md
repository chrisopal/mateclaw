Status: DONE
Verification: PASS
Plan deviations: none

# Restricted Bidding observation preservation

Restricted observations now survive Bidding spill, turn-budget fallback, and later conversation-window aging/pruning without relying on arbitrary `read_file` recovery. If preserving the exact evidence would exceed the effective context budget, execution terminates before another provider call and persists `INSUFFICIENT_CONTEXT` / `VALIDATION` as a failed task attempt. In-budget observations continue through normal reasoning. Generic tool outputs retain their existing compaction behavior.

The protection registry is bounded at 8,192 `(conversationId, toolCallId)` keys. Admission is synchronized and fail-closed when full; it never evicts or TTL-expires an active key. Capacity refusal replaces the newly-returned observation with an internal sentinel and uses the same explicit insufficient-context terminal path. `BiddingEmployeeRuntime` releases only that attempt's in-memory protection metadata in `finally`; spill files and source records are untouched. Existing server-authorized preservation remains limited to the two Bidding source readers plus pinned `load_skill` and `readSkillFile` snapshots.

The integration regression lives under `vip.mate.agent.context` because it exercises package-visible window behavior alongside the real tool executor; the root plan now declares that package path. A required `ToolResultStorage` constructor dependency was kept mandatory. The lightweight `BiddingHttpFixture` now wires a real storage bean from its existing `ToolResultProperties`, while the isolated runtime fixtures pass a mock only to satisfy constructor wiring. No optional production injection or compatibility setter was added.

## Regression evidence

- Capacity RED: [restricted-observation-capacity-red.log](/tmp/restricted-observation-capacity-red.log) — under the prior oldest-entry eviction behavior, the first live source key became unprotected (`expected true but was false`) after registry pressure. The fixed implementation's concurrent last-slot regression admits exactly one of two contenders and retains the original key.
- Graph RED: [restricted-observation-terminal-red.log](/tmp/restricted-observation-terminal-red.log) — with the incorrect event-type check, the actual native graph made a second provider call and returned `{}` instead of terminating on insufficient context. After checking `GraphEvent.data.phase` and emitting a correctly typed `project_execution_failed` event, the same real `execute → graph → readResult → complete` path made one provider call and read back a failed task/attempt with `INSUFFICIENT_CONTEXT` in `error_json`.
- The native runtime suite also verifies a later 4,096-token context-window overflow, a full 8,192-key registry refusal with no second provider call, an in-budget pinned observation that reaches the normal second provider call, and per-attempt protection metadata cleanup. It uses a controlled fake `ChatModel` provider; it does not invoke a live model.
- The existing context test verifies exact >8,000-character source content, including middle and end blocks, plus pinned skill and `output.schema.json` snapshots across immediate result handling and later age/prune. It checks cross-conversation call-ID isolation, generic spill/compaction behavior, denied arbitrary-file recovery, and explicit budget event status.

## Verification

All Maven commands used Temurin 21 and `-Dmaven.compiler.proc=full`.

- [focused-final.log](/tmp/restricted-observations-focused-final.log): `-Dtest='BiddingContextBudgetEndToEndTest,RestrictedProjectObservationTest,BiddingRuntimeIsolationTest'` — PASS, 14 tests, 0 failures/errors.
- [graph-tests.log](/tmp/restricted-observations-graph-tests.log): `-Dtest='ActionNode*Test,ObservationDispatcher*Test,ReasoningNode*Test'` — PASS, 77 tests, 0 failures/errors.
- [suite.log](/tmp/restricted-observations-suite.log): `-Dtest='Bidding*Test,PresalesIntegrationTest,PresalesEmployeeRuntimeTest,ToolRegistryProxyTest,ToolExecutionExecutor*Test,ToolResultStorage*Test,ConversationWindowManager*Test,RestrictedProjectObservationTest'` — PASS, 299 tests, 0 failures/errors.
- `-Dtest='BiddingSourceTest'` — PASS, 17 tests, confirming the updated lightweight HTTP fixture starts and its existing source contract tests still pass.
- `git diff --check` — PASS.

The first broad suite attempt before the fixture bean change produced 100 Spring application-context errors (299 tests discovered); the common cause was the lightweight `BiddingHttpFixture` lacking the newly required `ToolResultStorage` bean. After adding fixture-only real bean wiring, the complete 299-test suite passed. A transient focused run also caught and corrected the event-shape issue before the final focused and broad green runs.

## Limits

The 8,192-key bound prevents unbounded metadata growth; if all keys are active, subsequent protected reads are rejected explicitly and require retry after narrowing/finishing the current work. Attempt completion clears only metadata for its own conversation. A forcibly terminated thread that bypasses `finally` can leave metadata resident until JVM exit; keys are never silently revoked, so a full registry continues to reject new protected reads. No spill/source data is deleted by this change.

Verification used isolated H2 test databases and fake provider responses. It does not establish live-provider truncation behavior, model-quality outcomes, or production runtime acceptance. No model settings, schemas, skills, provider limits, or production processes were changed.
