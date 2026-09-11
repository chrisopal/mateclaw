# T05 UI acceptance

Runtime: 5189 frontend, 18109 backend, persistent `data/semantic-owl-runtime/database.mv.db`. Synthetic fixture IDs are in fixture.json. Credentials are read only in memory by the existing B2 helper; no credentials are stored here.

1. Run `python3 output/ui-acceptance/2026-09-11-t05/prepare_fixture.py` from the worktree only if preparing the existing fixture (file makes it idempotent).
2. In an independent authenticated browser context, select the T05 workspace via UI and open its fixture editor. Never resize or navigate the user's tabs.
3. Execute replay.cjs using the Playwright MCP browser_run_code_unsafe filename argument. It edits the power unit MW then kW, saves/refreshes, validates samples, and checks widths. It preserves the final kW rule.
4. Run `python3 output/ui-acceptance/2026-09-11-t05/verify_policy.py` for independent API readback.

Initial creation, checkbox/attribute selection, cancellation, deletion/discard and simulated response-loss recovery were executed interactively and recorded in browser-results.json. replay.cjs replays the main saved editing/sample/viewport flow; it does not claim those extra scenarios are all automated in that file.

Coverage: 19 real-browser checks executed; 3 NOT_RUN cases explicitly listed in coverage.json. The ledger checker returns INCOMPLETE for those known gaps; no malformed/missing-evidence errors. Do not describe this as full application UI acceptance.

The initial panel screenshot documents the corrected footer/navigation/text-density iteration. Responsive screenshots were captured after CSS transitions finished.
