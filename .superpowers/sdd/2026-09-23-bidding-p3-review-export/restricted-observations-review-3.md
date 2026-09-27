# Restricted observations scoped review 3

de9a1864 → 20499d90. SPEC: PASS. Quality: APPROVE.

- **HIGH literal-marker control forgery — ADDRESSED.** `ToolResultStorage.java:283–289` now derives overflow only from enabled aggregate budget plus server-registered protected keys; response text is no longer inspected for terminal control. `ToolExecutionExecutor.java:557–558,754–755,775–779,1243–1247` carries a private AtomicBoolean shared only within the execution's prepared calls. Only actual server-side protection admission failure sets it. Literal callback output cannot set this state. The generic and in-budget restricted exact-marker regressions at `RestrictedProjectObservationTest.java:38–68` preserve the literal response and assert no budget-control event. Actual capacity/turn/window terminal regressions remain green. No generic or restricted source-text control bypass remains in this changed path.
- **Synchronous provider-counter hardening — ADDRESSED.** `BiddingContextBudgetEndToEndTest.java:145–148` increments the same providerCalls counter on `ChatModel.call(Prompt)` and throws explicitly; existing stream recording and one-call failure/two-call normal-continuation assertions remain. An unexpected synchronous provider path can no longer escape the assertion.

No new actionable breakage found in this narrow fix diff. Earlier budget refusal, registry admission and cleanup findings remain closed; their unchanged implementation was not broadly re-reviewed.

Evidence: fresh root formal gate d9851921 → 20499d90 PASS, 15 files; 61 suites/378 tests, zero failures/errors/skips, 33.947s. Inspected fixed diff, prior review and appended report; no duplicate Maven or source edits/children. Java-capable LSP unavailable; rely on actual compiled root tests. This is scoped repair acceptance, not live-provider or Task5 business acceptance. Only this authorized local report written.
