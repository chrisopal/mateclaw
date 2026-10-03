# AQ-00/46 checked-push warning barrier failure closure

This is engineering evidence only; formal acceptance, maintainer control-plane approval and required remote CI enforcement remain NOT_RUN/PENDING/NOT_VERIFIED. Full scope is not closed.

## Failure and repair

Checked push f1t0yr2g actually exited1/FAIL/submission_ready=false at target010453ea2120af167ea08997f620a28e90b4d3c2, full range basefc87d8eb413535fabac37650e1e82ca76368e460. Transport did not publish this target. Prior green source checks did not override the failed push. The raw Java failure reported embeddingFailure_surfacesWarning while only started/completed broadcasts were recorded.

The old unit test counted down from recordWarning; production surfaceWarning invokes the warning broadcast afterwards. Await establishes no ordering with that subsequent invocation. The test now waits for exact KB_ID/EVENT_RAW_WARNING and verifies recordWarning explicitly. Five-second wait, no-failed-status and exact warningCode broadcast assertions remain. No production, gate, timeout, skip, dependency, permission, transaction or migration changes. Existing test receives fixed AOSP formatting; the other two test bodies are unchanged semantically.

## Exact checks

- Start HEAD010453ea/tree78d6af48; fixed origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93. Initial dev mh_xij04 and after-fix dev mcyuh969 actualexit0/SCAN_PASS, not submit authorization.
- Targeted Maven JDK21 -pl mateclaw-server -am test, explicit WikiProcessingServiceErrorCodeTest: actualexit0,3 tests/0 failures/errors/skips before formatting. Explicit regex Spotless formatter actualexit0, only target test matched. Final source compilation/tests covered below.
- Source commit 8d9ec356a32e474e00d1791eb3cb95049e005622, tree dcaa02e912f6817e40f37e925e8520c43a58d3aa. Staged gate qwdvtw8w and normal hook n12cddru actualexit0/PASS/submission_ready=true, matched this exact source tree. Actual Java 6071, UI 1093, Node5, typecheck/ID precision, enterprise/classic builds; incremental UI lint/format and independent cost-tool fixed mapping NOT_APPLICABLE.
- Independent final diff review COMMENT/no actionable defects. Java LSP unavailable; no duplicate test/gate runs by reviewer. This is technical review, not maintainer signoff.
- [Manifest](warning-barrier-test-results.json) binds source-file SHA256, archived failed push report/Java log, targeted/formatter/dev/full staged and hook reports. Display copies redact credentials and normalize only line-end whitespace, retaining input and display hashes.
- Retry push and final documentation tree checks are subsequent operations; verify exact final remote SHA and PR readback before reporting delivery. No retrospective PASS is written over failed f1t0yr2g.

## Scope, rollback and next work

Rollback restores this single unit-test synchronization and its evidence; no database or production action. Original project WIP is untouched. All46 formal AC retain NOT_RUN. Real persistence/SSE transport, browser/roles, V2 migration, required remote CI and maintainer/QA remain outstanding. Next bounded investigation is AC-21 terminal reception: protect already-terminal/same-ID-replaced tasks against late failure/fallback results, establish actual red regression before any bug claim, preserve current RUNNING version-drift failure and cancellation semantics. Coordinator SQL extraction and all-writer pagination projection remain separate unfinished work.
