# AC-21 terminal reception and coordinator storage boundary engineering evidence

This slice does not close full architecture, AC-21, maintainer approval or business acceptance. All46 formal AC remain NOT_RUN, remote required CI enforcement NOT_VERIFIED. No production database/model or original workspace WIP was changed.

## Problem, resulting behavior and structural simplification

Old employee FAILED writes bypassed the success-only durable RUNNING/identity check; coordinator terminal/fallback paths excluded only CANCELLED. Actual H2 red cases changed SUCCEEDED to FAILED, resurrected old runId/snapshot and incremented version3 to4; CAS retry repeated the overwrite. Failure fallback also ignored cancellation reservation and retained an obsolete RUNNING result.

Coordinator now requires full live equality with original Submission.task and RUNNING before model start, terminal submission and every fallback reread. It checks pending cancellation before fallback reads/CAS, stops for removed/terminal/replaced tasks and removes obsolete result before failure-only setAll. Same RUNNING task with whole-project version drift still fails as before, preserving newer unrelated fields; this V1 behavior is not final AC-18 independent-dependency compliance.

Service employee entry admits only SUCCEEDED/FAILED. Failure requires matching RUNNING envelope, excluding only status/finishedAt/result/error/rejectedOutput. Success keeps its original three excluded fields and existing result/snapshot/employee checks, authority fence, pin/model validation and projection order. Role/source/replay/CAS/archive ordering and manual command path stay intact; same matching receipt replay still precedes this admission check. New nonterminal/stale employee rejection is explicit admission tightening.

All coordinator SQL moved to existing PresalesProjectRepository: reused typed ProjectRow, exact scoped runtime lookup, restart rows and body/version CAS. Repository owns facts only; JSON, identity, diagnostics, retry counts5/3, cancellation and recovery eligibility remain coordinator policy. Name/status columns and other workspace rows are preserved. No new runtime hooks, dependencies, controller SQL, migration or query-pagination claim.

## Actual execution and exact tree

- Start HEAD3f0fbd4732efb80b1b9e42925264297c49cbdf01/treef0a8c143531ac35513885fdd258c586f78434186, base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93. Initial dev bi81bzqq SCAN_PASS/actualexit0.
- Red52/27 failures/0 errors; nonterminal red2/2 failures; intermediate62/32 failures/1 Mockito restubbing fixture error, all actualexit1. Corrected doAnswer retains deletion assertions. Confirmed pre-production red63/33 failures/0 errors/0 skips, actualexit1, stored H2 bytes show real changes. No red was relabeled PASS.
- First guard dev w62opysr SCAN_PASS and guard63 passed; typed repository extraction, explicit fixed regex Spotless formatting and final9classes119 passed, actualexit0. 43 added regression cases:21 service cases plus22 H2 coordinator cases. Original coordinator4 and runtime16 preserved; old SQL-mock recovery seam updated to typed facts, real H2 recovery added.
- Tests cover statuses, run/operation/employee/model/Skill/presentation pin, conversation/snapshot/unknown extensions, success diagnostic strictness, pending cancellation during failure/CAS retry, missing objects, same project ID across two workspaces, real recovery, obsolete result removal, retained matching-run diagnostics and unrelated fields. Actor-loss double is signaled by model callback rather than magic getter-call counts. It is not a real actor revocation or production concurrency run.
- Source commit f7d6cf80b433a659b6227009b606577cf5a86767, tree 10b05cbc1587ca1ac852be827c346b3881396a2c. Exact staged 6s_fjolw and normal commit hook lls2qw6r actualexit0/PASS/submission_ready=true, same tree. Java 6114, UI 1093, Node5, typecheck/ID precision and enterprise/classic builds passed. UI format/lint and independent cost tool fixed mapping NOT_APPLICABLE.
- Independent6file/plan review COMMENT/no actionable defects, no edits or duplicate gates. Java LSP6 calls Transport closed/unavailable, not zero-error proof. Real Maven compilation is evidence. New reviewer spawn hit agent limit; same SAVE_AI_TASK boundary reviewer handled the related employee counterpart. No maintainer/QA/control-plane approval is implied.
- [Manifest](terminal-reception-test-results.json) binds source-file hashes, 26 credential-redacted archive copies/input hashes, red/green/formatter/dev reports and exact full staged/hook results. Final docs gate and checked-push/remote-PR readback follow source archival; inspect PR for final task IDs/target SHA.

## Risks, unfinished work and rollback

This is single-process V1 membership/CAS protection, not distributed attempt fencing or comprehensive cancellation timing/billing proof. Whole-project drift, body scans and mutable envelope fields remain legacy. Formal V2 specs mapping, independent objects/dependencies/attempts, SQL projection/backfill/count/index performance, golden bytes and three-dialect migration/restore are still outstanding. Browser/roles/live model/runtime restart, maintainer/business/QA and remote required CI remain unverified. Do not convert narrow engineering checks to full AC-21 PASS.

Rollback restores these three production classes and three test files plus evidence; no database migration or production step. Late/nonterminal internal employee admission tightening is deliberate; public/manual command semantics and frozen artifacts were not rewritten.
