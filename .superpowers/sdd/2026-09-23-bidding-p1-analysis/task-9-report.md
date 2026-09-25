# Task 9 report: project owner and viewer permissions

Status: DONE
Verification: PASS
Plan deviations: none

## Result

Project GET now includes a server-computed `capabilities.canApprove` value after the project has been authorized in the requested workspace. It is true for workspace owners/admins and the active project owner; it is false for other members and viewers. The workspace `/capabilities` response remains coarse. Approval and mutation endpoints continue to enforce permissions server-side.

Analysis and revision reads now authorize active workspace viewers and validate that stored source references are still current. `BiddingDependencies.validate` remains member-only and continues to guard dispatch, edits, and task execution revalidation.

## Verification

- RED observed before implementation: project owner GET lacked the scoped approval capability; viewer analysis GET returned 403.
- `mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=BiddingProjectTest,BiddingAnalysisTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS, 15 tests.
- `./node_modules/.bin/vitest run src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS, 12 tests.
- ESLint on `BiddingWorkbench.vue`, `api/types.ts`, and `biddingWorkbench.test.ts` — PASS.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` — PASS after the concurrent Task 10 owner corrected its file. This task did not modify that file.
- `git diff --check` — PASS.

## Notes

The integration/browser check artifact `.playwright-cli/`, generated `output/presales/`, and the separately owned acceptance document are outside this task and are not included in its commit.
