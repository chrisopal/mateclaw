Status: DONE
Verification: PASS

Task 4 implements isolated project execution via `BiddingEmployeeRuntime.execute` and the production `AgentService` / `AgentGraphBuilder` / `StateGraphReActAgent` path. Trusted options stay in graph state and reach actual tool callbacks; fixed model, skill files, tool scope, claim validation, read-only source receipts, output cap, timeout, and terminal event checks are enforced in runtime code. A real H2 source-set revision/head, task, active attempt/token, model, agent, and package drive the production-path test. The fake OpenAI-compatible streaming client emits partial JSON and disconnects; execution returns no payload, preserves `STREAM_INCOMPLETE`, and calls the provider once with retries/fallback disabled.

The H2 isolation tests cover non-active/current-attempt rejection, workspace mismatch, forged ordinary `ToolContext` identity, immutable pinned skill reads, and a successful authorized source-block read with a persisted receipt. Existing Presales, skill-tool, and fallback/failover regressions pass. `StateGraphReActAgent` now emits a terminal failure event even when the failed final answer is empty; `readResult` preserves the first, more specific failure event instead of replacing it with a later generic terminal failure.

Verification commands, all run with Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`:

```sh
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -version
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest=BiddingEmployeeRuntimeTest,BiddingRuntimeIsolationTest,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest,NodeStreamingChatHelperFallbackChainTest,NodeStreamingChatHelperFailoverTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
git diff --check
```

Result: BUILD SUCCESS; 41 tests passed (4 runtime, 4 isolation, 4 skill-load, 11 skill-file, 1 Presales runtime, 3 Presales coordinator, 8 fallback-chain, 6 failover). Surefire XML confirms `java.version=21.0.7` and `java.home=.../temurin-21/Contents/Home`.

Not run: Task 5 queue/dispatch integration (out of scope and not present); actual external model/network behavior. The production-entry test mocks `BiddingAccess` at the host authorization seam and uses the configured fake provider; H2 claim/attempt, source-set, employee-config, skill-pin, callback, and receipt checks remain real. No full production dispatch claim is made.

Plan deviations: none

Independent gate follow-up: the broader `Bidding*Test` suite initially exposed a Spring startup regression in `BiddingHttpFixture.App`: `BiddingEmployeeRuntime` eagerly required `BiddingEmployeeBindings`, whose graph requires `AgentService`, although that fixture intentionally has no agent graph. The runtime now lazily resolves execution-only agent dependencies, so host startup does not require the optional graph; the normal `execute` path still resolves these dependencies when invoked. Added a minimal Spring-context regression that constructs the runtime without `AgentService` and skips the unrelated H2 setup.

Verification after the fix (Temurin Java 21.0.7, compiler annotation processing `full`):

```sh
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -version
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest='Bidding*Test,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest,NodeStreamingChatHelperFallbackChainTest,NodeStreamingChatHelperFailoverTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
git diff --check
```

Result: BUILD SUCCESS; 95 tests passed across the 8 `Bidding*Test` classes (62 tests) and six existing Skill/Presales/fallback classes (33 tests), with zero failures or errors. The startup regression test ran as part of `BiddingRuntimeIsolationTest` (5 tests total, 0.16s). No Task 5 queue/dispatch integration was run; this remains out of scope.

## Review Fix Round 1 (2026-09-25)

Addressed the three Important review findings within the Task 4 ownership set. Added a server-side `ProjectToolPolicy.Revalidator` hook in the existing execution policy file; `BiddingEmployeeRuntime` implements it and the real graph builder supplies it to `ToolExecutionExecutor`. Executor pre-callback and post-callback checks now revalidate actor membership, active attempt/token, employee/model config, pinned skill package, and input references. `SkillLoadTool` and `SkillFileTool` also revalidate immediately before reading and before returning pinned bytes, so a revoked task receives no skill content and cannot trigger the post-callback skill-loaded receipt.

The production execution path now passes the pinned `output.schema.json` to the schema-aware `readResult` overload. Missing or malformed schemas fail closed; accepted outputs must be JSON objects matching the supported schema subset. The validator supports type, required, properties, additionalProperties boolean, items, enum, const, string bounds/pattern, numeric bounds, and array bounds. It rejects unknown or unsupported keywords (including `$ref`, `format`, and composition keywords such as `oneOf`) and malformed schema structures. The existing three-argument overload remains source-compatible for previous collector tests and enforces object output; production always uses the pinned-schema overload.

Added regression coverage for skill reads after attempt revocation, valid/invalid pinned-schema outputs, missing/unsupported schemas, first specific execution failure surviving a later generic failure, and invalid output against the same fixed package schema used by the production-entry test.

Verification ran with Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`:

```sh
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest=BiddingEmployeeRuntimeTest,BiddingRuntimeIsolationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest='Bidding*Test,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest,NodeStreamingChatHelperFallbackChainTest,NodeStreamingChatHelperFailoverTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest=BiddingEmployeeRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
git diff --check
```

Results: focused initial run passed 10/10 tests; combined requested suite passed 96/96 tests with BUILD SUCCESS; final `BiddingEmployeeRuntimeTest` rerun passed 5/5 after adding specific-failure precedence and fixed-package schema assertions. `git diff --check` passed. No live provider, browser, MySQL, or Kingbase validation was performed.

## Review Fix Round 2 (2026-09-25)

After `appendReceipt` locks the active attempt row, it now reruns the full active-claim validation before modifying `tool_receipts_json`. The isolation regression revokes actor membership at that receipt boundary and confirms the call fails while the persisted receipt array remains empty.

`readResult` now returns an already captured `project_execution_failed` event from its Flux-error path before applying generic transport classification. A regression emits an authentication failure event and then `Flux.error`, and confirms the authentication code/category survive. Schema `pattern` validation now uses `Pattern.matcher(value).find()` directly, preserving anchors and alternation semantics; the regression covers `^a|z$` matching `abc` through its anchored first alternative.

Verification ran with Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`:

```sh
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest=BiddingEmployeeRuntimeTest,BiddingRuntimeIsolationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
MATE_JAVA21=$(/usr/libexec/java_home -v 21); JAVA_HOME="$MATE_JAVA21" PATH="$MATE_JAVA21/bin:$PATH" mvn -pl mateclaw-server -am -Dtest='Bidding*Test,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest,NodeStreamingChatHelperFallbackChainTest,NodeStreamingChatHelperFailoverTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
git diff --check
```

Results: focused tests passed 11/11; combined suite passed 97/97 with BUILD SUCCESS; `git diff --check` passed. No live provider, browser, MySQL, or Kingbase validation was performed.
