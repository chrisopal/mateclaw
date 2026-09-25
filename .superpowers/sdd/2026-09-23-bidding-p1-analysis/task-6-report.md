Status: DONE
Verification: PASS
Plan deviations: none

## Implementation

- Added four bundled bidding analysis skills with fixed SKILL.md instructions, input/output schemas, and per-skill reference notes. Runtime result handling checks loaded skill/config fingerprints and validates output against the pinned output schema; the analysis result handler also requires the pinned skill contract before accepting output.
- Added `BiddingSkillValidator` with explicit per-skill shape, type, coverage, decimal, read-receipt, source/version/block, and exact-quote checks. Golden valid/invalid examples exercise the Java validator and runtime's packaged JSON-schema check.
- Added analysis dispatch over immutable confirmed source sets, stable 12,000-codepoint whole-block sharding, four skill tasks per shard, immutable candidate revisions, conflict-aware merge, manually edited replacement revisions, approver-only baseline confirmation, and idempotent operation/readback records.
- The model/client cannot assert its own reads: acceptance derives processed-block authorization from persisted server `bidding_read_source` receipts. Since completion runs outside an HTTP SecurityContext, exact fixed source revisions are re-read from the repository under the server-owned claim scope and digest, after claim dependency/source-set validation.
- Task result-handler resolution preserves direct numeric skill handlers and allows stable bundled skill names only when the fixed package declares the matching name and contains its schemas.
- Baseline confirmation stores the full merged payloads and decision in existing revision/head/decision tables. The next stage is explicitly `CONFIGURATION_REQUIRED` because the outline-planning package is not installed in P1.

## Test-first evidence

- Ran the validator test before implementation; it failed at test compilation with the expected missing `BiddingSkillValidator` class.
- The first end-to-end run exposed a worker-context authorization failure (`UNAUTHENTICATED`) from calling the interactive source evidence API during result acceptance. The implementation now reads the same immutable, digest-pinned source snapshot through the repository under the verified task claim while still requiring persisted server read receipts.
- Focused Task 6 suite after review fixes: `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest='BiddingSkillValidatorTest,BiddingAnalysisTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — 18 tests, 0 failures, 0 errors. Regression coverage includes original shard read receipts and processed-block coverage before edit/confirm, explicit per-conflict resolution, BigDecimal stated/calculated/difference validation, criterion-ID subtotal reconciliation, hierarchy overlap rejection, and unknown score preservation.

## Verification

All Maven commands used Temurin Java 21.0.7 and `-Dmaven.compiler.proc=full`.

- Bidding + Task 4 compatibility suite: `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest='Bidding*Test,SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — 125 tests, 0 failures, 0 errors.
- After round 2 review fixes: focused Task 6 suite above — 18 tests, 0 failures, 0 errors.
- `git diff --check` — PASS.
- JSON syntax validation for all new skill schemas and the golden fixture — PASS.

## Gaps

- The DB-backed acceptance test executes `BiddingReadTool` and verifies persisted source-read receipts, candidate revisions, confirmed baseline, decision and replay behavior, but uses deterministic golden outputs rather than a live LLM call.
- H2 integration is covered; MySQL/Kingbase behavior, a production model run, and browser acceptance were not exercised.
