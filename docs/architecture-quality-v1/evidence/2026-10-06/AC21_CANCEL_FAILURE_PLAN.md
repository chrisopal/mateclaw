# Next bounded AC21 fix: durable stop failure must not orphan siblings

Implementation base: HEAD608d525a5f6ad6f966cc3ebc604c62408be56070; clean worktree. Initial dev s_c8wl_9 SCAN_PASS. Reuse existing tracker, relay and ChildResult, preserve public cancellation API and error/timeout/cancelled wire outcomes. No database, authorization or transaction changes.

Observed current control flow: requestStopWithoutNotification may rethrow durable Stop persistence failure after real ChatStreamTracker performs its local stop fallback. The parallel collection loop does not catch it; f.cancel, later sibling stop requests, relay cleanup, registry removal and delegation_end are then bypassed. The existing tracker test covers local fallback but not the delegation consumer.

Bounded implementation sequence (after current AC07 commit/push freeze is released):
1. Regression first: adapt real tracker/relay deterministic parallel timeout and fail-fast fixtures; make one child's durable Stop publisher throw after a known signal. Assert later siblings receive stop, relays and registry are cleaned, and result/end includes an honest failure outcome for the failed durable stop. Preserve successful result and completion-before-end contracts.
2. Isolate per-child stop failure and put that child's future cancellation in a finally path. Continue sibling stop requests. Reuse existing ChildResult error outcome; do not silently label durable stop failure successful cancellation. Keep default requestStop unchanged.
3. Ensure cleanup runs for all prepared children under the tested failure; only extend exception handling where a concrete regression shows this same exit path bypasses cleanup. Do not create executor, queue, service or timeout architecture.
4. Run original parallel ordering/SSE cancellation/tracker tests plus the new negative and positive tests; post-dev, independent diff review, exact-tree commit gate, normal hooks and push.

Limits: this addresses thrown exceptions, not arbitrarily blocking child locks, callbacks/dispose or permanently blocked final SSE flush. No claim all cancellation paths are bounded. Bad persisted version/history recovery remains a separate AC21 subitem.

Future cancellation is verified by the explicit per-child finally path plus independent source review; the integration regression observes real hooks/disposal, stop flags, sibling continuation, final structured outcomes and relay/registry cleanup, without exposing the private future map solely for tests. No original assertions are removed.
