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
