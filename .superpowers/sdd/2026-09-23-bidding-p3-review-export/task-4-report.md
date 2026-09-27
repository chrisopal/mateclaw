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
