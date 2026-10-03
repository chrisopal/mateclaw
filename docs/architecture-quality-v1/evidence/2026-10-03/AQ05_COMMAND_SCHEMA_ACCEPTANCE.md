# AQ-05 public command runtime field schema: engineering evidence

Source: `2604ef22964ab743c3695882a3acd4a3a79dbdb2` / tree `63ff6711aeb1704f1b91002129c860bdc8568bfc`. Start HEAD `fc87d8eb413535fabac37650e1e82ca76368e460`; fixed origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`. Initial clean worktree; original project WIP was not edited.

## Problem and resulting behavior

The previous service used coercive `asText/asInt` on unknown JSON and kept its original node. Actual HTTP/H2 red cases persisted numeric titles, boolean customer names and invalid task envelopes with success, or returned lookup/state errors for malformed IDs. The new domain-local PresalesCommandPayload validates declared fields for all16 public action branches (ARCHIVE has no required fields). It reports400 INVALID_REQUEST with a fixed field path, without including user values.

Service adds only seven lines at the existing application boundary: role, operation, expectedVersion, project lock, source/repair, receipt replay, CAS and archive checks still run first. The validator does not query repositories, change transactions, normalize strings or grant authority. PresalesProjectItems.Rejected uses the existing service error adapter. No controller/schema-library/runtime dependencies were added.

## Compatibility and explicit new admission

- All declared write fields and nested review issues, solution sections/requirement responses have shape checks. Missing mandatory fields, text lengths, enum membership, existing entity references, source policy and approvals stay with existing domain rules. Shape acceptance is not command/business acceptance.
- Defaulted enums still allow explicit null to reach the old fallback. statementRevision keeps raw strings (including empty, leading zeros and large numeric text), or accepts nonnegative safe integral JSON numbers. IDs remain strings.
- Unknown extension nodes, raw field order, duplicate/reference order and opaque inner JSON are not traversed or rewritten. This is declared write-field admission, not a complete reserved-field/server-response schema.
- Manual SAVE_AI_TASK result/contextSnapshot now require objects when present. Null/scalar/array top-level values are explicitly rejected for new writes. Model schemaVersion/needsHumanReview and snapshot operational_record remain opaque internally. Internal employeeResult skips this manual schema and retains pinned validation, durable identity and authority fence. GenerationController RUNNING envelopes remain compatible.
- Existing solution presentation presence rejection and top-level/section sourceRefs policy keep their422 codes and established title/presentation/baseline/source order. For other multiply malformed inputs, a new shape error may precede a branch lookup/text error; this is an intentional new input restriction, not a claim of byte-identical invalid-input behavior.
- Historical matching receipts replay before new validation; mismatching hashes conflict. Existing stored bodies, frozen handoffs and published bytes are not modified. Source revocation still blocks malformed requests and only original exact repair shapes pass.

## Actual checks

| Check | Result | Evidence |
|---|---|---|
| Initial and final dev | SCAN_PASS / exit0, submission_ready=false | initial-dev.txt, dev-final.txt |
| Prior implementation red | actualexit1;41 tests with30 failures and11 passes | red.txt |
| First schema green | exit0;41 tests | green.txt |
| Targeted regression | exit0;103 tests in8 classes, zero skipped | regression.txt |
| New contracts |31 pure shape +33 HTTP/H2 =64 tests | test source and regression log |
| Exact staged commit | PASS / exit0 / submission_ready=true; Java6071 | task `go1_v5dz`, staged-report.json |
| Normal pre-commit hook | PASS / exit0 / submission_ready=true; Java6071 | task `nfo0gka8`, hook-report.json |

Staged and hook target the exact source tree above. Full Java reactor clean verify, compilation, fixed Java format and gate self-tests were actually executed. The actual full source gate also ran UI1093, Node5, type/ID precision and both enterprise/classic builds: PASS. ui-format/ui-lint were NOT_APPLICABLE because no frontend targets changed; cost-tool was NOT_APPLICABLE under its fixed mapping. Do not infer all UI checks were skipped merely because this slice edited server source.

The HTTP negatives assert status/code/field-only message and identical body bytes, unchanged revision count, and no operation receipt. Additional cases assert role/source/repair/replay/conflict/CAS/archive priority, legacy enum defaults, raw revision text and opaque model envelope preservation. Existing39 targeted transaction/generation/command/solution/atomic/integration tests remained unchanged.

## Independent review and real failures

Read-only native reviewer `/root/command_payload_contract_audit` inventoried actual16 branches, frontend contracts, runtime producers and old policies, then reviewed the four Java files and plan. Final COMMENT: no actionable defects. The reviewer did not duplicate test execution. Four Java LSP attempts used an actual tsc-only backend and returned skipped/no tsconfig: Java LSP NOT_AVAILABLE, not zero-error Java evidence. Maven is the actual compiler proof. This is not maintainer/control-plane or business approval.

The first format selector matched no files despite exit0 and is not format proof; the glob selector failed with Dangling meta character and exit1. Correct regex selector actually formatted three new files, confirmed Service already clean, and returned exit0. A Python stdin plan-supplement write failed with Non-UTF-8 before writing; the original plan remained unchanged during staged verification. No gate, assertion, timeout, baseline or config was weakened. All actual logs including failures are retained.

Manifest [command-schema-test-results.json](command-schema-test-results.json) binds source SHA/tree, source-file SHAs, gate reports, raw input/display-log SHAs and credential redaction counts. Display-only line-end normalization and JWT/Bearer/generated-password redaction do not change check verdicts. No credential values are retained in evidence.

## Remaining requirements and rollback

All46 formal AC remain NOT_RUN. Required remote CI, maintainer/control-plane and business/QA approval are NOT_VERIFIED/PENDING. This slice does not complete full server response DTO, field-specific domain states, model schema or all opaque extension validation. Top-level HTTP record deserialization coercion and production historical sampling remain separate concerns.

SQL pagination still reads complete body_json. GenerationCoordinator also writes body/version directly in terminal fallback and restart recovery, so a Service-only query projection would miss alternate writers; next-query-requirements.txt records this inventory. The actual three migration directories are h2/mysql/kingbase; the attempted lookup under nonexistent postgresql was a read-path error, not a missing supported-dialect result. Existing row columns cannot represent customer/owner/stage and ROOT Unicode search; full projection/backfill/count/list/index plans need migration and comparison evidence. V2 independent objects/dependencies/single-write migration, missing referenced formal spec mapping, three-dialect backup/restore and golden published-byte evidence remain required. Real roles/concurrent API409, Workspace/theme/narrow browsers, graph/tools/cache/history/export, async restart, live model and independent QA remain unverified.

Rollback restores this validator/service call and corresponding tests only. No database operation, old Flyway modification, new dependency, merge, deployment or production-data action occurred.
