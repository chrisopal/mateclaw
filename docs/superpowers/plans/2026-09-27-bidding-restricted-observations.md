# Restricted bidding observations repair

Prerequisite: Task4 source gate and Sol approval. Execute this scoped repair before Task5 full-flow acceptance. Live diagnosis establishes an inaccessible recovery instruction, not every invalid-output root cause.

### Task 1: Preserve authorized observations through restricted execution

**Files:**
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingRuntimeIsolationTest.java` constructor and isolated Spring fixture wiring only for the required metadata cleanup dependency.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ActionNode.java` restricted terminal failure only.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/edge/ObservationDispatcher.java` exact restricted failure routing only.
- Create: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingContextBudgetEndToEndTest.java`.
- Modify: `docs/superpowers/plans/2026-09-27-bidding-restricted-observations.md` root-controller scope reconciliation only.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/execution/ProjectExecutionOptions.java` if a scoped policy field is necessary.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/context/ConversationWindowManager.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ReasoningNode.java` to pass conversation ID and fail closed on restricted window overflow before provider invocation; no ordinary reasoning/model behavior changes.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolResultStorage.java` only if existing retrieval policy can safely express the repair.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java` only for explicit restricted-observation policy wiring.
- Create: `mateclaw-server/src/test/java/vip/mate/agent/context/RestrictedProjectObservationTest.java`.
- Modify: `mateclaw-server/src/test/java/vip/mate/agent/context/ConversationWindowManagerExemptAndSpillTest.java`.
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeRuntimeTest.java`.

**Interfaces:**
- Consumes: current pinned project execution, allowed scoped source readers and skill files; real 8516-character/28-block source observation.
- Produces: restricted model observation without an inaccessible arbitrary-filesystem recovery instruction; generic-agent spill behavior preserved.

- [ ] First reproduce current defect with a realistic >threshold multi-block tool response in restricted execution. Test exact middle/end block visibility or allowed source reread recovery; do not assert only output size.
- [ ] Cover both immediate executor compaction and later context-window spill/eviction; preserve source/schema evidence or give a valid scoped reread contract. Keep aggregate context limits effective and report any insufficient-context condition explicitly.
- [ ] Preserve generic-agent spill behavior, arbitrary read_file denial, source assignment/project/workspace authorization and pinned schema protection. No new dependencies, unrestricted file tool, global threshold change or schema relaxation.
- [ ] Run focused executor/window/runtime regressions and complete Bidding + Presales gates. Document actual command/results and known gaps.
- [ ] Commit only declared code/tests/report with Lore trailers; report Status DONE / Verification PASS / Plan deviations none only after evidence. Root scope+tests then independent Sol review required.

Root follow-up after accepted repair: package a fresh QA backend using preserved private DB, rerun one actual DeepSeek analysis generation and record all source read coverage/schema results/attempts. Model maxTokens4096 and graph normal do not establish provider stop semantics. Provider terminal metadata instrumentation is a separate defect hypothesis and requires a separately declared scope/regression before editing. This repair alone cannot be called full live technical-bid acceptance.

Scope clarification: age compaction currently omits conversationId at its caller. Passing it through ReasoningNode is authorized so preservation is keyed by conversation plus call ID rather than trusting provider call IDs globally. Cover same call ID in separate conversations; preserve the existing generic overload for callers without restricted context.

Confirmed scoped extension (2026-09-27): readSkillFile is excluded from immediate spill but is absent from CWM age/prune exemptions. Thus a pinned output.schema.json can be lost later. Extend only the server-created Bidding preservation policy to its pinned `readSkillFile`/`load_skill` observations alongside the two source readers. Keep generic global exemptions unchanged. Within the already declared test/runtime/window files, regress exact schema/skill retention through age/prune plus source retention, denied arbitrary path/file reads, cross-conversation isolation, generic compaction and hard budget refusal. This is a proven observation-contract defect, not proof that it caused every live wrong-schema output.

Root scope reconciliation (2026-09-27): the new combined executor/window regression belongs in `agent/context` to exercise package-scoped window seams without widening production visibility. The original planned `graph/executor` test location is replaced, not a second file. Source base remains d9851921; this documented test-location adjustment is approved before the root gate. Add a long pinned excluded-tool schema regression above `excludedToolInlineChars` under aggregate pressure: the excluded-tool fallback must preserve the exact protected observation and emit insufficient-context; the generic counterpart must still compact.

Sol review fix scope (2026-09-27): HIGH budget events do not enforce refusal; MEDIUM capacity/expiry may silently revoke active protection. Root authorizes exact restricted insufficient-context terminal control in ActionNode/ObservationDispatcher plus ReasoningNode pre-provider window guard. Use existing project_execution_failed persistence and VALIDATION classification, no auto retry or model/skill/schema/ACL changes. Regression must record provider invocations and persisted DB failure for immediate overflow, later-window overflow and capacity admission; in-budget restricted/generic runs remain normal. Bounded atomic admission must retain all admitted active keys. Expiry must never silently make an active prompt generic; use explicit fail-closed expiry or per-attempt finally metadata release in already owned BiddingEmployeeRuntime, without deleting durable source or artifacts. Context-budget E2E only fakes provider responses; real runtime/graph/source/auth/task persistence. Root gate additionally selects BiddingContextBudgetEndToEndTest and relevant ActionNode/ObservationDispatcher/ReasoningNode tests. No Task5/runtime deployment before renewed gate and scoped Sol approval.

Constructor fixture scope reconciliation: metadata-only finally release uses a required shared ToolResultStorage dependency in BiddingEmployeeRuntime. Root authorizes only the direct-constructor argument and isolated Spring bean registration in BiddingRuntimeIsolationTest, preserving isolation assertions. Keep required constructor injection rather than introducing optional setter/null behavior solely to avoid this legitimate test adaptation. The test edit occurred before scope notification; this is recorded and reconciled before gate, not silently treated as originally declared. Graph-level semantic RED is still pending; report only the capacity RED until it exists.
