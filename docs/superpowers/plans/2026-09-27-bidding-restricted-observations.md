# Restricted bidding observations repair

Prerequisite: Task4 source gate and Sol approval. Execute this scoped repair before Task5 full-flow acceptance. Live diagnosis establishes an inaccessible recovery instruction, not every invalid-output root cause.

### Task 1: Preserve authorized observations through restricted execution

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/execution/ProjectExecutionOptions.java` if a scoped policy field is necessary.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/context/ConversationWindowManager.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolResultStorage.java` only if existing retrieval policy can safely express the repair.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java` only for explicit restricted-observation policy wiring.
- Create: `mateclaw-server/src/test/java/vip/mate/agent/graph/executor/RestrictedProjectObservationTest.java`.
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
