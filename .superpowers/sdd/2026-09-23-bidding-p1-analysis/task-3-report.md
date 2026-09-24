# Task 3 Report

Status: DONE
Verification: PASS
Plan deviations: none
Implementation commit: `8ffe5dd9a7e059806d44776bdcb7bec1cd25582f`

## Scope delivered

- Added workspace/native/enabled employee checks, model configuration validation, role assignment persistence, operation replay, and effective configuration digests that distinguish inherited tool grants (`null`) from explicit empty grants.
- Added immutable per-project skill package pins with deterministic SHA-256 digests, UTF-8 package size enforcement, workspace-scoped active skill lookup, and pinned-file reads that reject traversal, absolute, absent, and cross-package paths.
- Preserved employee bindings and selected revision references across project metadata updates.
- Kept employee-binding services lazy for the existing bidding-only application test slice; those services resolve only when the employee endpoint or assignment action is invoked.
- Tests use the real H2-backed `AgentService`, `AgentBindingService`, and `SkillRuntimeService` flow for assignment/readback, as well as isolated package edge tests.

## Verification evidence

- Expected red test before implementation:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest='BiddingSkillPackagesTest,BiddingEmployeeBindingsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — FAIL at test compilation because `BiddingSkillPackages` had not yet been implemented.
- Final focused test command:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest='BiddingEmployeeBindingsTest,BiddingSkillPackagesTest,BiddingProjectTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — EXIT_CODE=0, BUILD SUCCESS; 23 tests, 0 failures, 0 errors (10 employee-binding, 5 package, 8 project/API).
- All bidding test command:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest='Bidding*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — EXIT_CODE=0, BUILD SUCCESS; 52 tests, 0 failures, 0 errors.
- `git diff --check` — PASS.

## Coverage

- Skill package digest is independent of map insertion order and changes with schema content.
- A pin remains readable after active skill files change; a subsequent pin gets a new digest.
- Traversal, absolute path, missing package member, and symlink escape read are denied.
- Cross-workspace, disabled employee, unassigned skill, and missing active skill are denied.
- Real H2 assignment validates current workspace agent bindings; persisted role binding and skill pin are read back; replaying the same operation returns the same result; subsequent project edits preserve binding refs and the stored fingerprint includes the pinned digest.
- Null tool grants remain inherited and differ from an explicit empty set; no model credential is exposed by the employee availability view.
- Exact prior assignment replay succeeds after project archival; new assignment attempts after archive are rejected.
- P1 assignment succeeds with the analyst bound and writer/reviewer explicitly null; those unbound later-stage roles report missing/not-ready required skills. Non-null role bindings pin only that role's required skills, so unrelated oversized global skills do not block assignment.
- Effective model selection follows the existing `ModelConfigService`, `ProviderRouter`, and configured-provider fallback path; tests cover configured override, unconfigured override fallback, missing-name default, and digest change when the selected model configuration changes.
- Authorized employee availability includes safe required skill IDs/names and role reasons without credentials.

## NOT_RUN

- Full server/repository Maven test suites were not run; verification covered all 52 `Bidding*Test` cases.
- Live HTTP/E2E against a running application was not run.
