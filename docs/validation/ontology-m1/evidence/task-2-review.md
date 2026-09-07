# Task 2 review

Verdict: **APPROVE** at `e5abeda222f1b0a4d54703cfaf963a0a9a82dbac`.

## Stage 1: spec compliance

PASS. The implementation covers the frozen ontology identity, draft, validation, publication, revision, diff, availability, operation-readback, status, capability, and three-dialect migration scope. It does not add graph, binding, fact, UI, tool, POM, global mapper-scan, or global authentication work.

The initial review commit `4c927eb8` used `semantic.enabled` instead of the approved host property `mateclaw.semantic.enabled`. Runtime acceptance exposed the mismatch. Follow-up `e5abeda2` corrects the property binding and both conditional assemblies, updates the HTTP fixtures, and adds regression coverage for enabled, missing, and obsolete-unprefixed configurations. No compatibility alias was added.

Workspace scope and current role are checked in both the semantic interceptor and application service. Disabled/deleted principals are reloaded and rejected. Resource reads are joined through the scoped ontology parent, including for global administrators. Viewer/member/admin-owner boundaries match the contract.

The parent-row lock, revision-row SQL CAS, active-draft unique constraint, and ontology-level monotonic `draft_counter` cover concurrent saves, discard/recreate ABA, and publication/new-draft ABA. Publication replay is checked after authorization and parent lock but before requiring a current draft. Same-key changed payload and concurrent cross-ontology reuse return 409; the losing cross-ontology publication rolls back under the workspace operation-key uniqueness constraint.

Publication state, parent pointers, command result, and governance record share one transaction. Historical definition/name/description remain stored on immutable published rows; availability changes only the availability field and is transactionally governed. Public IDs, counters, timestamps, enum/scalar validation, nullable fixed units, and field-error envelopes conform to the frozen wire contract.

The H2, MySQL, and Kingbase V191 migrations create the four required tables with the single-active-draft and operation-key constraints. H2 compatibility checks are appropriately reported as partial dialect evidence. Parent runtime acceptance additionally confirmed V190 to V191 on the real QA database. Real standalone MySQL and Kingbase execution remains the declared SEM-11 gate and does not block M1 approval.

## Stage 2: code quality

PASS. No CRITICAL, HIGH, MEDIUM, or LOW findings remain. SQL uses bound MyBatis parameters. Exception handling is scoped to semantic controllers and does not expose internal failures. The change adds no dependency and keeps the host integration narrow.

The Java LSP tool available in this environment has no Java backend, so its per-file calls skipped diagnostics. Compilation is covered by the parent task gate; the targeted follow-up check below independently passed.

## Evidence

- Parent task gate: 21 original semantic tests passed; parent runtime acceptance found the feature-property mismatch and confirmed the real QA V190 to V191 migration.
- Follow-up commit report: 24 targeted tests, zero failures/errors/skips, plus reactor production/test compilation and diff checks.
- Reviewer targeted run: `SemanticConfigurationTest` and `SemanticAuthorizationTest#approvedFeaturePropertyEnablesStatusAndBusinessRoutes`, exit 0.
- `git diff --check 4c927eb8..e5abeda2`: pass.

Required fixes: none remaining.
