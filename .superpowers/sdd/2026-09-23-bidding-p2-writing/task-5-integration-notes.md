# Task5 final integration checks

UI implementation exists uncommitted; stage has root-independent21testsPASS. This is not final task completion.

- Match approved Task2 actual SAVE_OUTLINE envelope, DISPATCH_OUTLINE baseline/material refs, and CONFIRM_OUTLINE expected editExpectedRef (not candidate.ref/project.ref). Use server-issued zero head initially.
- Read dispatchTodo from GET outline; short Chinese actionable config state. Do not display reasonCode/decisionId raw.
- Add chapter arrays when human creates new chapter; remove subtrees recursively, retain valid sibling ordering and mapping fields. Surface mapping errors on confirm; allow incomplete candidate save.
- Match approved Task3 EDIT_CHAPTER field whitelist and exact output schema. Avoid payload spread of immutable task/schema/status metadata. Preserve allowed mixed blocks/evidence. Fix candidate selection/dirty handling against background refresh.
- Server correlates per-chapter tasks. Confirm task status and retry target matches persisted chapter association. Disable stale adoption; auth changes clear sensitive snapshot/body.
- Build/test/check enterprise explicitly. Final runtime/browser checks are required (blue button alignment, spacing, light/dark narrow screen, save -> refresh/read-back, conflict draft preservation). Update acceptance artifact truthfully.
