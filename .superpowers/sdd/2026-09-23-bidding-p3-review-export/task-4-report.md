Status: DONE
Verification: PASS
Plan deviations: none

Implemented the sixth bidding-workbench tab for independent whole-book review, evidence-backed finding disposition, targeted chapter revision through the existing compare/adopt flow, separate human classification and closure of business todos, fixed export preparation, candidate/preview/formal file states, exact-file human inspection and approval, and scoped download handling. Project approval capability controls approval and todo resolution. Protected review/task/artifact state and pending drafts are cleared or guarded on scope changes and access revocation; stale responses and object URLs are discarded.

The artifact download API consumes the backend's supported `candidate`, `preview`, and `formal` modes. Preview files have a separate download action and remain ineligible for approval. Approval fields are taken from the authenticated READY approval-context response; no digest or whole-book review reference is reconstructed in the UI.

Changed paths:
- `mateclaw-ui/src/features/bidding/api/biddingApi.ts`
- `mateclaw-ui/src/features/bidding/api/types.ts`
- `mateclaw-ui/src/features/bidding/shared/state.ts`
- `mateclaw-ui/src/features/bidding/pages/BiddingWorkbench.vue`
- `mateclaw-ui/src/features/bidding/components/BiddingTaskDrawer.vue`
- `mateclaw-ui/src/features/bidding/components/BiddingWriting.vue`
- `mateclaw-ui/src/features/bidding/components/BiddingReview.vue`
- `mateclaw-ui/src/features/bidding/components/BiddingArtifacts.vue`
- `mateclaw-ui/src/features/bidding/components/BiddingApprovalDialog.vue`
- `mateclaw-ui/src/features/bidding/__tests__/biddingReview.test.ts`
- `mateclaw-ui/src/features/bidding/__tests__/biddingArtifacts.test.ts`
- `mateclaw-ui/src/features/bidding/__tests__/biddingTasks.test.ts`
- `mateclaw-ui/src/features/bidding/__tests__/biddingWorkbench.test.ts`

Verification evidence:
- Focused Task4 UI tests: `pnpm exec vitest run src/features/bidding/__tests__/biddingArtifacts.test.ts src/features/bidding/__tests__/biddingReview.test.ts src/features/bidding/__tests__/biddingTasks.test.ts src/features/bidding/__tests__/biddingWriting.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — 5 files, 63 tests passed before the preview-mode contract adjustment; afterward the affected artifact/workbench suites passed 43 tests.
- Focused preview/API-consumer retest: `pnpm exec vitest run src/features/bidding/__tests__/biddingArtifacts.test.ts src/features/bidding/__tests__/biddingWorkbench.test.ts` — 2 files, 43 tests passed.
- Broader UI regression: `pnpm exec vitest run src/features/bidding/__tests__ src/features/presales/__tests__` — 14 files, 111 tests passed after final changes.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` — passed after final changes.
- Scoped ESLint over owned changed Vue/TypeScript paths — passed after final changes.
- `pnpm run lint:precision` — passed; Snowflake numeric-conversion check passed.
- Enterprise build: `node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build --config ../scripts/presales/vite.config.mjs --mode enterprise --outDir /tmp/mateclaw-task4-enterprise-dist` — passed after final changes (6544 modules; existing large-chunk advisory only).
- Root's isolated browser visual verdict: iteration 3 desktop light empty state scored 92/pass. This does not cover populated, dark, 390px, or business-action acceptance.

Known limits: populated/dark/mobile browser acceptance and the full real business workflow remain NOT_RUN here and are reserved for root QA. No actual model task was executed. The human inspection fields record an authenticated user's claim and do not prove Office rendering. The Enterprise build reports pre-existing large-chunk advisories.

## Fix round 1

Status: DONE
Verification: PASS
Plan deviations: none

All five findings from `task-4-review-1.md` are addressed:

1. Review evidence now follows the server contract `{sourceId, version, blockId, quote}` and human-todo classification uses the active immutable `todoRef` for both payload and expected ref. On 409, the selected todo is refreshed by stable ID after review reload; the reason/evidence draft stays open. The new workbench test asserts the exact source block, first stale todo ref, retained reason, and refreshed todo ref on retry.
2. Artifact list metadata is modeled without manuscript/template/format refs. Cards render the server manifest/metadata shape without dereferencing unavailable input refs. Exact dependency refs remain required on `ArtifactApprovalContext` and are displayed in the approval dialog only after the authorized endpoint returns them. A manifest-shaped render test reproduces the previous `undefined.version` TypeError as RED and passes after the fix.
3. Approval, prepare, generate, approval-context refresh, and review/artifact refresh now capture the originating workspace/project/signal and verify it before success, error, and final state changes. Tests defer each command across a workspace switch using the same project ID, exercise late success and 403/409 paths, and verify the new project stays loaded and no stale approval dialog/context is reopened. The late 403 approval/export regressions reproduce against the pre-fix component and pass with the fix.
4. Review labels recognize actual `BLOCKER`, `MAJOR`, `MINOR`, `SUGGESTION` severities and server categories including `MISSING_MANDATORY_PROOF` and `UNANSWERED_TECHNICAL_REQUIREMENT`. Missing mandatory proof cannot be dismissed in the UI. The human `FIX` decision is shown only to project approvers. Tests use these exact server enum values and check visible labels, blocker styling, hidden dismissal, and member permissions.
5. Candidate/preview/formal downloads pass the captured AbortSignal. A deferred download test confirms a scope switch aborts it and a late Blob does not create an object URL.

The empty artifact alert now uses enterprise surface, border, and text tokens in dark mode. Root rechecked the empty desktop dark surface at 94/PASS and the empty 390px light layout at 92/PASS. These checks cover empty states only; populated review, approval, download, and other real business interactions remain NOT_RUN in browser QA.

Round 1 verification:
- Initial semantic RED: the real metadata fixture raised `TypeError: Cannot read properties of undefined (reading 'version')`; actual severity/category fixture rendered `Other`/`Suggestion` and exposed writer-only `Targeted revision`; classification sent project expected plus nested `sourceRef` instead of todo expected plus flat evidence.
- Scope/download RED against the pre-fix workbench source: 4 selected regressions failed (late approval 403 cleared the new workspace, late PREPARE and GENERATE 403 cleared it, and download omitted the AbortSignal). The same selected tests passed after restoring the fix.
- Focused UI tests: five bidding files, 74 tests passed.
- Bidding and presales regression: 14 files, 122 tests passed.
- `pnpm run lint:precision` passed.
- `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit` passed.
- Scoped ESLint over the owned changed Vue/TypeScript files passed.
- Enterprise build passed: `node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build --config ../scripts/presales/vite.config.mjs --mode enterprise --outDir /tmp/mateclaw-task4-round1-final-dist` (6544 modules; existing chunk-size advisory only).
- `git diff --check` passed.

Changed in this fix round: `mateclaw-ui/src/features/bidding/api/types.ts`, `mateclaw-ui/src/features/bidding/components/BiddingArtifacts.vue`, `mateclaw-ui/src/features/bidding/components/BiddingReview.vue`, `mateclaw-ui/src/features/bidding/pages/BiddingWorkbench.vue`, `mateclaw-ui/src/features/bidding/__tests__/biddingArtifacts.test.ts`, `mateclaw-ui/src/features/bidding/__tests__/biddingReview.test.ts`, and `mateclaw-ui/src/features/bidding/__tests__/biddingWorkbench.test.ts`.

Remaining limitation: full populated browser workflow acceptance is still separate root QA. Human inspection checkboxes record the actor's assertion and do not prove Office rendering; no actual model task was run by this UI fix.
