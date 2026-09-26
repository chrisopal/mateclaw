# P2 Task 1 — Presales handoff and authorized materials

Status: DONE
Verification: PASS
Plan deviations: none

Controller-approved implementation detail: Added an immutable `kind=material` reference for each received presales release after downstream integration review showed the handoff could otherwise only be displayed, not selected as a task input. The reference is stored in the existing material table and reauthorizes the exact published release and its associated knowledge bases on read.

## Delivered

- Added optional-Presales handoff options and exact published-release receive flow. Receive checks the preview digest and expected bidding project ref, locks the project before idempotency replay/version allocation, persists the immutable receipt and material ref, and leaves customer confirmation `UNCONFIRMED`.
- Added fixed wiki-page material bindings. The active project writer must be a live employee in the current workspace; binding and every later read recheck the employee, workspace, page/KB relationship, current KB visibility, and `WikiPageTypePermissionService.canRead(agentId,kbId,pageType)`. Page-type denial is checked before idempotent replay too. Disabled, deleted, or cross-workspace employees cannot read snapshots. Revoked entries remain recorded but their title/content is suppressed.
- Receiving the same published release again under a different operation id returns controlled `409 HANDOFF_ALREADY_RECEIVED` under the project lock; reusing the original operation remains idempotent.
- Received presales releases use `kind=material`, `id=presales:<presalesProjectId>:<releaseId>`, `version=1`, and the exact release digest. Snapshot reads return the stored frozen handoff only after validating the employee, origin release, source availability, and visibility for each associated KB.
- Presales handoff snapshots freeze clarifications and their references inside the successful `PUBLISH_RELEASE` transaction. Later clarification edits do not alter the published snapshot; solution, baseline, artifact summaries, and release identity remain pinned to that exact release.
- Project body stores only handoff metadata, not the copied presales snapshot, so ordinary project reads do not bypass material authorization. Independent bidding project creation remains available if Presales is disabled.

## API response examples

`GET /api/v1/bidding/handoff-options?presalesProjectId=...` returns a list shaped like:

```json
[{"presalesProjectId":"pre-1","releaseId":"release-1","status":"PUBLISHED","digest":"<sha256>","title":"Example solution","solutionVersion":2,"publishedAt":"<stored timestamp>","available":true,"release":{"id":"release-1","status":"PUBLISHED","publishedAt":"<stored timestamp>"}}]
```

Fields such as `solutionVersion` and `publishedAt` are omitted when absent in the stored release; historical releases without a provable snapshot return `available:false` and `unavailableReason:"HISTORICAL_SNAPSHOT_UNAVAILABLE"`.

`GET /api/v1/bidding/projects/{id}/materials` returns `{ "items": [...] }`. A readable presales entry includes `source:"PRESALES_RELEASE"`, `releaseId`, `receivedAt`, `digest`, `validity:"VALID"`, `ref:{"kind":"material","id":"presales:pre-1:release-1","version":1,"digest":"<sha256>"}`, `title`, and stored optional version/date metadata. A readable wiki entry includes its same fixed ref, title, applicability, and selection time. For revoked/unavailable origins, entries retain ref/status metadata with `validity:"UNAVAILABLE"`; title/applicability/content are omitted.

A material snapshot returns `{ "items": [{"ref":...,"source":"PRESALES_RELEASE","title":"Example solution","applicability":"已发布售前版本的冻结交接资料","selectedAt":"<receipt timestamp>","content":<frozen handoff>,"validity":"VALID"}] }` for a selected presales ref. Task inputs therefore use the same fixed-ref form for wiki and presales materials.

## Verification

- Java: Temurin 21.0.7 at `/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home`.
- `JAVA_HOME=... mvn -pl mateclaw-server -am -Dtest='BiddingHandoffTest,BiddingMaterialsTest,BiddingMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 6 tests, 0 failures/errors.
- `JAVA_HOME=... mvn -pl mateclaw-server -am -Dtest='BiddingHandoffReceiptTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 1 real HTTP/H2 receipt regression.
- `JAVA_HOME=... mvn -pl mateclaw-server -am -Dtest='Bidding*Test,PresalesIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` — PASS, 126 tests, 0 failures/errors; includes bidding runtime, migration/isolation, materials, handoff, receipt persistence, and presales integration coverage.
- `JAVA_HOME=... mvn -pl mateclaw-server -am -DskipTests -Dmaven.compiler.proc=full compile` — PASS.
- `git diff --check` — PASS.
- V215 migration assertion now verifies the exact 11 bidding tables while retaining historical Presales/Semantic row preservation and bidding data-isolation assertions.
- Real receipt regression asserts first HTTP receipt persistence (one handoff and one presales material), same-operation HTTP replay, different-operation HTTP 409 with a fresh ref, and unchanged project ref/table counts after rejection. The disabled-Presales test now only claims independent project/material-list behavior.
- The presales integration regression now adds a clarification after approval but before publication, confirms that clarification is in the published handoff, then adds a later clarification and confirms the published handoff remains unchanged.
- Not tested: migration execution against live MySQL/Kingbase databases; no runtime DB or external tender data was used. Red-before-implementation TDD evidence was not captured; tests were run during implementation and the final targeted set passes.
