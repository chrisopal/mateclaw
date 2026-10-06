# AQ-00/46 regression closure: await the final async warning event

Start HEAD010453ea2120af167ea08997f620a28e90b4d3c2/tree78d6af48de7cafe7fa0ce440323048bed516b801; base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93. Clean isolated worktree, original project WIP untouched. Initial dev exit0/SCAN_PASS, log /tmp/mateclaw-warning-barrier-initial.log.

Actual checked push73076 ended exit1, FAIL/submission_ready=false, target010453ea, basefc87d8eb. Report f1t0yr2g: Java failure WikiProcessingServiceErrorCodeTest.embeddingFailure_surfacesWarning:136; source gates6071/1093 previously passed, but cannot supersede failed push. No remote update/PR rewrite occurred. Full failure logs retained.

Smell and causal evidence: test releases CountDownLatch inside recordWarning. Production surfaceWarning invokes broadcast only after recordWarning returns. The main thread can verify before that subsequent invocation; actual failure lists only raw.started/raw.completed. Independent read-only reviewer confirmed happens-before does not cover later broadcast.

Plan before edits:
1. Change only this test's synchronization: latch on exact KB_ID/EVENT_RAW_WARNING broadcast, retain five-second bounded wait, original no-failed-status and exact warningCode broadcast checks. Add explicit rawService.recordWarning verification to preserve/strengthen the behavior implied by the old latch.
2. Keep other two tests/production code unchanged; explicit fixed formatting for this existing test is allowed development, with independent diff review to separate style from behavior. No new sleeps, timeout increases, skip/mock production feature removal or gate changes.
3. Run the three real unit tests, dev, fixed format and full exact-tree gate/normal hooks; include the failed pre-push as red evidence, then checked push the full unpushed range and verify remote SHA/PR readback.
4. Independently review this same delivery's failed-gate closure. New native reviewer creation hit thread limit; reuse its completed technical reviewer only for related delivery regression closure, not another broad design. This does not substitute maintainer control-plane approval.

The test barrier observes the final behavior being asserted, rather than weakening the assertion. Warning persistence and SSE payload, non-failed material state and unchanged error-code vocabulary are required. Gate config, production Wiki behavior, presales schema and old source evidence stay unchanged. All46 formal AC remain NOT_RUN; maintainers/QA/remote required CI, browsers, SQL/V2/async terminal reception remain pending.

Rollback restores this test and evidence only; no database or production action.
