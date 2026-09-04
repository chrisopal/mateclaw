# Enterprise UI verification — 2026-09-04

## Scope

- Base: `bd41a718`; branch: `codex/enterprise-ui`.
- Three existing source seams modified: `main.ts`, `Login.vue`, `MainLayout.vue`.
- New profile, CSS adapter, login layout and regression tests. No backend, dependency, permission, API or data-store changes.
- Original UI specification content is retained; only Markdown trailing-space line breaks were normalized during commit preparation.

## Automated checks

| Check | Result |
|---|---|
| Existing test baseline | 53 files / 346 tests passed |
| Final Vitest | 55 files / 359 tests passed (pre-commit rerun including favicon regression) |
| New profile and login tests | 2 files / 13 tests passed; missing-profile, missing-login-layout and stale-favicon failures observed before implementation |
| Vue-aware typecheck | `pnpm exec vue-tsc --noEmit` passed |
| ESLint on all changed TS/Vue files | Passed |
| Full repository frontend ESLint | 1 pre-existing `prefer-const` error at `useAgentRunGroups.ts:185`, 43 existing warnings; error independently reproduced from HEAD |
| Enterprise Vite production build | Passed; existing large-vendor chunk warnings retained |
| Classic Vite production build | Passed; same vendor warnings |
| Diff whitespace check | Passed |

An existing `ECONNRESET / socket hang up` log occurs during both baseline and final Vitest, while all tests pass. It is not presented as a clean log run.

## Runtime and browser evidence

The actual backend was compiled and started on loopback with a fresh **in-memory H2** database from a separate temporary working directory. No existing database was migrated. `/actuator/health` returned UP. Both original and enterprise login flows reached the authenticated application using the local test account. Final enterprise screenshots include the production artifact served by Vite preview, not only dev-mode rendering.

Screenshots are local QA artifacts under `outputs/enterprise-ui/` (git-ignored):

- Classic: login and dashboard reference captures.
- Enterprise 1440px: login, dashboard, employees, settings, Wiki, memory, native team form dialog.
- Dark: settings, workflow manager, bilingual login; dark inverse token readback is `#101722` over `#EDF2F9` feedback surfaces.
- 1366px and 1920px: workflow layout, no page-level horizontal overflow.
- 390px: dashboard, chat and production login. Chat composer remains within the viewport; mobile login submit bottom was 702px in an 844px viewport.
- Production dashboard: sidebar 220px expanded / 56px collapsed, header 48px, no page overflow at 1440px.
- Mobile navigation opens, route selection closes it, and the closed sidebar is inert in enterprise mode.
- Language switch updates the existing navigation, new context header and local login content; Chinese was restored for the retained preview.

## Independent review

Initial review found two medium contrast regressions: pale dark-login hover and white inverse text over a light feedback surface. Both were corrected; scoped re-review approved the changes. Reviewer-calculated contrast after correction: dark login hover 8.16:1; inverse feedback pair 15.99:1. Browser base-login and inverse-token values were read back; hover specificity was verified by code review rather than a stable browser hover capture.

## Explicit limits

- No model provider was configured: live model inference, streaming content generation, workflow execution and document export were not exercised.
- Wiki/Memory/Workflow used empty states; seeded employee cards and the team form supplied non-empty UI coverage. This is not exhaustive certification of every route, dialog or backend feature.
- Native and Element Plus shared controls are adapted, but specialized generated-content/canvas internals intentionally retain their geometry and semantic colors.
- Default profile remains `classic` until visual acceptance; use `--mode enterprise` for the new UI. No deployment, push, merge, or default switch was performed.

## Icon follow-up and commit checks

At the user's request, Image Gen produced a transparent blue 3D claw used by enterprise login, sidebar and favicon. The original classic PNG/ICO assets remain unchanged. The favicon regression exercises the real profile initializer against a document with an existing icon link, checks repeated application does not duplicate it, and checks classic restoration. Browser readback confirmed the enterprise PNG href and `image/png` type.

Pre-commit builds use `outputs/enterprise-ui/commit-check-enterprise` and `commit-check-classic`, separate from the running preview, so verification does not overwrite served assets. Only source, generated brand asset and supporting documentation belong in the commit; databases, logs, screenshots and `.omx` state are excluded.
