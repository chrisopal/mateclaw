# Task 7 report

Status: DONE

Verification: PASS

Plan deviations: none

Implemented the workspace-scoped bidding list and workbench, source upload and explicit source-version selection, source-set confirmation, four analysis result panels with evidence and human revision, task retry/cancel history, and persisted current-baseline/revision readback. Selected sources alone are validated; supported `NEEDS_REVIEW` blank-page exclusions require an explicit reason, while unselected failed or unsupported versions do not block a valid selected subset. Dashboard filters match project-list workspace/name/stage/owner predicates; deadline counts require an unambiguous date and timezone. Pending confirmation counts only complete successful analysis task groups awaiting approval. Superseded baselines are omitted from current analysis readback while strict revision reads remain strict.

Round-1 review regressions cover all seven findings with mounted component interactions or focused Java behavior: source subset/exclusions; source-set supersession; real 409 draft preservation; readonly task actions; stale list/member/create responses after filter/workspace changes; complete-group pending confirmation; and dashboard filter/date rules.

Verification run:

- `pnpm exec vitest run src/features/bidding/__tests__/biddingProjects.test.ts src/features/bidding/__tests__/biddingTasks.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS, 3 files / 12 tests.
- `pnpm exec eslint src/features/bidding/components/BiddingSources.vue src/features/bidding/components/BiddingTaskDrawer.vue src/features/bidding/pages/BiddingProjects.vue src/features/bidding/pages/BiddingWorkbench.vue src/features/bidding/__tests__/biddingProjects.test.ts src/features/bidding/__tests__/biddingTasks.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS.
- `pnpm lint:precision` — PASS (`check-snowflake-precision.sh`).
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` from `mateclaw-ui` — PASS.
- `pnpm exec vite build --outDir /tmp/mateclaw-task7-vite-build --emptyOutDir` from `mateclaw-ui` — PASS; existing large-chunk warning only.
- `MATE_JAVA21=$(/usr/libexec/java_home -v 21) && JAVA_HOME=$MATE_JAVA21 PATH=$MATE_JAVA21/bin:$PATH mvn -q -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='BiddingDashboardTest,BiddingAnalysisTest' -Dsurefire.failIfNoSpecifiedTests=false test` — PASS.
- `git diff --check` — PASS.

NOT_RUN: interactive browser acceptance at 1440px/390px in light and dark themes; live workspace business E2E; full repository test suite. Vite reported existing large-chunk size warnings, with a successful production build.

Round-2 review fix: A same-project refresh keeps the existing project subtree mounted while the request is pending, so analysis edit text/reason survive a 409 reload and a failed project read. Scope changes still clear old project data immediately. The mounted regression performs `EDIT_ANALYSIS_ITEM` -> 409 -> failed Reload -> verifies both draft fields -> successful Reload -> verifies both fields again -> resubmits against the refreshed expected project ref.

TDD evidence:

- RED: `pnpm exec vitest run src/features/bidding/__tests__/biddingWorkbench.test.ts` — failed at the post-Reload textarea assertion (`expected null not to be null`), proving the dialog was unmounted.
- GREEN: same command — PASS, 5 tests.
- Focused regression set: `pnpm exec vitest run src/features/bidding/__tests__/biddingProjects.test.ts src/features/bidding/__tests__/biddingTasks.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS, 3 files / 13 tests.
- `pnpm exec eslint src/features/bidding/pages/BiddingWorkbench.vue src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` from `mateclaw-ui` — PASS.

Round-3 review fix: Same-project refresh continues to retain project content for transient failures, but a 401/403/404/410 or disabled capability now clears the project, capabilities, sources, analysis, and open project-scoped drawers before rendering the unavailable/access state. Late source/analysis readbacks cannot repopulate cleared content. The mounted regression checks each status and asserts both project and analysis data disappear; the earlier 409 draft test still covers transient 5xx retention and successful readback.

TDD evidence:

- RED: `pnpm exec vitest run src/features/bidding/__tests__/biddingWorkbench.test.ts` — failed the mounted stale-content assertions for 401/403/404/410 while the previous project and analysis remained visible.
- GREEN: same command — PASS, 9 tests.
- Focused bidding UI set: `pnpm exec vitest run src/features/bidding/__tests__/biddingProjects.test.ts src/features/bidding/__tests__/biddingTasks.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS, 3 files / 17 tests.
- `pnpm exec eslint src/features/bidding/pages/BiddingWorkbench.vue src/features/bidding/__tests__/biddingWorkbench.test.ts` — PASS.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` from `mateclaw-ui` — PASS.
- `git diff --check` — PASS.
