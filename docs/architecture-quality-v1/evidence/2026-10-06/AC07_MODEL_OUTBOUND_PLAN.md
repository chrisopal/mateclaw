# AC07: revalidate retained source context at model egress

Base: origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`; HEAD `8d2c8b2a68c3d63280f79e8ea21623fbe2223b7b`; initial working tree clean.
Initial dev: `1lij96bz`, SCAN_PASS (application toolchains NOT_RUN).

## Bounded defect and design

A project tool can pass pre/post checks and append authorized source text to the same attempt's graph messages. Authority can then be revoked before Reasoning, Summarizing or LimitExceeded sends that retained text again. Final result rejection cannot undo this outbound disclosure.

Bind the existing server-created ProjectExecutionOptions and public ProjectToolPolicy.Revalidator immutably to the per-project NodeStreamingChatHelper in AgentGraphBuilder. At the actual stream call, after any backoff, revalidate before contacting a model. Missing project revalidator fails closed. Propagate authorization failure directly; never route it through provider failure/retry/fallback classification. No new policy registry, business imports, state credentials, dependencies or transaction changes. Ordinary nonproject helpers retain current behavior.

The three reachable project consumer nodes share this helper. Project plan_execute is explicitly rejected by AgentGraphBuilder; project graph retry/fallback and Reasoning compact/empty retries are disabled. Nonetheless the shared helper guard must cover retries/fallback if invoked with project options. ConversationWindowManager also performs a synchronous initial-history summary from StateGraphReActAgent; fresh task UUIDs usually have no history, but cannot serve as the enforcement boundary. Pass the server-created options to initial-state preparation and guard only the synchronous summary delegate with the same public revalidator. CWM's existing summary-error fallback can return trimmed history, but must not send revoked content; the following streaming call remains guarded. The compact-retry route is nonproject-only. A universal model wrapper was rejected because concrete model types select Anthropic/DashScope protocol options in nodes; both outbound paths preserve these concrete types and perform one policy check per actual model send.

## Implementation and verification sequence

1. Add constructor-only immutable binding/wiring (no authorization behavior), then add deterministic RED tests against real nodes/helper and ToolExecutionExecutor. Preserve existing observation retention tests.
2. Verify successful source tool pre/post checks followed by revocation prevents the next model call; authorized positive path transmits the exact retained text. Exercise Reasoning, Summarizing, LimitExceeded, missing policy, retry/fallback and ordinary chat.
3. Add the single egress revalidation check; rerun tests GREEN. Run existing helper/node/source-provider/observation/architecture regression suites, explicit formatting, post-edit dev.
4. Report exact logs and counts; independent review and full commit gate remain parent responsibilities. No live provider/production/remote database or formal business signoff is claimed.

Rollback: revert this bounded helper/builder/test change together; doing so reopens the same-attempt authorization gap. Historical snapshots and published bytes are untouched.
