# AQ07 Java migration guard engineering evidence

Scope: a bounded AQ-07 control-plane slice. Formal AC-39/46 and the other 44 AC items remain NOT_RUN. This is not complete AQ-07, database or customer acceptance. [Plan](AQ07_JAVA_MIGRATION_GUARD_PLAN.md).

## Coverage change

Before: DB-001/002 covered only SQL. Published Java entry edits/deletes/renames, missing Java dialects and shared V1 algorithm edits had no finding. After: SQL/Java entry invariants plus DB-003/004 explicit source freeze. All three V218 wrappers, shared BackfillV1 including Row, and ProjectionV1 including Projection are declared. Every initial SHA-256 was independently compared with published HEAD bf7a12f5406f46fcac1e3292a078f45414be3c6e. No production source, old migration, policy, baseline or assertion changed.

The base and candidate declarations are both checked. Removing the manifest/items, updating a digest to match modified code, or modifying an existing source while first freezing it fails. Malformed schemas, duplicate keys, invalid paths/digests, missing sources and undeclared Java entries fail. Valid unchanged sources and an appended complete new version pass. Worktree snapshots preserve UTF-8 CRLF bytes consistently with Git blobs.

## Actual checks before submission

- Initial dev: actual exit 0, SCAN_PASS, report `_86mjmx_`, application tools NOT_RUN.
- New regression module on old checker: 19 tests, 35 assertion/subtest failures, exit 1. This is genuine red evidence before production checker edits.
- All guard tests after edits: 91 tests, exit 0. Existing tests retained; 19 new test methods cover the changed behavior.
- Coherent-slice dev: actual exit 0 / SCAN_PASS, report `68av0fe9`, submission_ready=false.
- Copied published source CLI probe: 18 scenarios × worktree/index = 36 actual CLI checks with expected exits. Scenarios include each Java dialect's edit/delete, both shared algorithm edits and rehashes, rename, removed/invalid manifest, missing new dialects, a complete new version and existing SQL edit. Temporary Git repositories are isolated and removed; no database is contacted.
- Independent read-only reviewer `/root/java_migration_guard_review`: COMMENT, no substantive defect in four-file implementation/plan scope. Independently ran 91 tests, dev exit 0 / SCAN_PASS (`i1muzumi`), Python AST parse, diff whitespace and published-source hash comparison. LSP returned `tsc skipped: no tsconfig found`; ast-grep absent. These diagnostics are NOT_RUN, not successful type/AST-tool checks. This review is not maintainer or merge approval.

Full exact-staged, normal hook and checked-push evidence will be recorded from their actual outputs after execution; none is claimed here in advance.

## Review and remaining boundary

Manifest closure completeness is explicit human/technical review, not automatic Java dependency discovery. Future new dependencies and compiler/JDK/Jackson changes still require compatibility and real Flyway checksum evidence. MySQL/Kingbase validate=false settings are unchanged; source SHA-256 does not enable runtime validation. Real Kingbase, production upgrade/rollback, full V2 data model, versioned hash/legacy receipt policy, browser/Office/model acceptance and independent maintainer/stakeholder QA remain open.

Trusted-base CI remains unchanged. The existing remote base has not installed this checker, and required CI enforcement is NOT_VERIFIED. Keep PR #5 draft. No merge, deployment, repository administration or production writes.

Rollback: revert the guard installation if separately justified and reviewed; do not edit frozen V217/V218/V1 sources or rewrite their digests to make a check green. Changes are confined to control scripts, explicit freeze data and engineering constraints/evidence.
