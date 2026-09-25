Status: DONE
Verification: PASS
Plan deviations: none

## Implementation
- Added a responsive mobile task-card list in `BiddingTaskDrawer.vue`. At widths up to 600px it shows every task name, state, attempt count, and permitted retry/cancel control without horizontal clipping. Task selection remains keyboard-operable, and mobile attempt cards show attempt number, state, and failure reason. The existing Element Plus tables remain active on desktop.
- Added a mounted regression covering all four task labels, state/count, retry and cancel commands, selecting a task, and viewing attempt detail. The test restores the viewport width in `afterEach`.
- Replaced untyped rejection access in the drawer with a typed helper. This also resolves the shared vue-tsc error reported for `rejection?.code` without changing API types.

## TDD Evidence
- RED: before the mobile list was implemented, the focused test failed because `.mobile-task-card` rendered zero cards (expected four); this was the expected missing responsive behavior.
- GREEN: `pnpm exec vitest run src/features/bidding/__tests__/biddingTasks.test.ts` → 1 file, 7 tests passed.

## Browser Verification
- At 390×844, opened the real task drawer, selected a failed task, and captured `/tmp/task10-mobile-390.png`. The screenshot shows all four task cards with retry actions and two visible attempt cards. Browser geometry reported `documentElement.scrollWidth=390`; task cards end at x=370 and remain within the viewport.
- At 1280×900, captured `/tmp/task10-desktop-1280.png`. Browser computed styles reported both desktop tables as `block` and both mobile lists as `none`; document scroll width remained 1280.

## Verification
- `pnpm exec vitest run src/features/bidding/__tests__/biddingTasks.test.ts` → PASS, 7/7.
- `./node_modules/.bin/eslint src/features/bidding/components/BiddingTaskDrawer.vue src/features/bidding/__tests__/biddingTasks.test.ts` → PASS.
- `bash ../scripts/check-snowflake-precision.sh` → PASS.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` → PASS.
- `git diff --check` → PASS.

## Files Changed
- `mateclaw-ui/src/features/bidding/components/BiddingTaskDrawer.vue`
- `mateclaw-ui/src/features/bidding/__tests__/biddingTasks.test.ts`
- `.superpowers/sdd/2026-09-23-bidding-p1-analysis/task-10-report.md`

## Self-review and Gaps
- Existing task polling, workspace/project cancellation, task selection, and status/failure rendering remain intact; the prior mounted task tests still pass.
- Full UI build and complete UI test suite were not run for this bounded responsive task. Browser verification was performed against the existing isolated local acceptance app and did not mutate bidding project data.
