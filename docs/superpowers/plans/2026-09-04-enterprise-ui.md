# MateClaw Enterprise UI Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the bounded login work; the primary agent owns integration and verification.

**Goal:** Apply the approved MBC-inspired enterprise visual profile without changing MateClaw business flows.

**Architecture:** Add a scoped CSS adapter under `src/styles/enterprise`, bridge existing `--mc-*` and `--el-*` variables, and use a build-time profile with a classic fallback. Keep template seams small and preserve the router/KeepAlive tree, data stores, API payloads, permissions and original logo.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vite, existing Vitest/happy-dom.

## Global Constraints

- No new production dependencies, backend edits, deployment, remote writes or auth changes.
- Primary #1677FF, ink #0B1220, canvas #F5F7FA; neutral derived dark palette; panels 6px and controls 4px.
- Preserve classic mode by default until visual acceptance; preview with `VITE_UI_PROFILE=enterprise`.
- Do not reshape chat, document preview, workflow graph, live execution and generated content into generic table pages.
- No generic wildcard overrides of all buttons, SVGs, divs, or inline content.
- Work on `codex/enterprise-ui`; keep the pre-existing untracked specification intact.

## Task 1: Enterprise profile, shell, login and shared route patterns

**Files:**
- Create: `mateclaw-ui/src/styles/enterprise/{profile.ts,index.css,tokens.css,element-plus.css,shell.css,patterns.css,login.css}`.
- Create: `mateclaw-ui/src/styles/enterprise/__tests__/profile.test.ts`.
- Create: `mateclaw-ui/src/components/enterprise/EnterpriseLoginLayout.vue`.
- Create: `mateclaw-ui/src/views/__tests__/Login.enterprise.test.ts`.
- Modify: `mateclaw-ui/src/main.ts`, `mateclaw-ui/src/views/layout/MainLayout.vue`, `mateclaw-ui/src/views/Login.vue`.
- Modify if visual inspection requires it: `mateclaw-ui/src/views/Dashboard.vue` (chart colors only, preserve data logic).
- Create: `mateclaw-ui/.env.enterprise`, `docs/ui/enterprise-profile.md`.

**Interfaces:**
- `resolveUiProfile(value: unknown): 'classic' | 'enterprise'`: accepts only the explicit enterprise value and defaults to classic.
- `applyUiProfile(root: HTMLElement, value: unknown): UiProfile`: sets the profile attribute without disturbing theme or existing attributes.
- `isEnterpriseUi: boolean`: build-time profile used by the two template seams.
- New login layout wraps the unchanged login form via its default slot.

- [x] Add failing tests for profile initialization and invalid-input fallback, preserving `.dark` and unrelated attributes.
- [x] Run `pnpm exec vitest run src/styles/enterprise/__tests__/profile.test.ts` and observe missing implementation; implement the profile functions and rerun.
- [x] Add login regression tests for enterprise/classic presentation and unchanged submit/SSO behavior; implement a slot-based split layout with local bilingual copy and responsive CSS.
- [x] Capture original login and representative route UI or document unavailable authenticated states before visual changes.
- [x] Map all existing theme variables; bridge Element Plus variants/teleported overlays; add shared panels, header, sidebar and route-specific semantic CSS selectors.
- [x] Keep all added CSS under `html[data-ui-profile="enterprise"]`, including `.dark` variants. Use min-width/min-height zero and preserve scroll ownership.
- [x] Preview enterprise and classic independently; verify responsive layout and existing controls on real rendered screens.
- [x] Run full tests, ESLint without `--fix`, vue-tsc and Vite build with isolated output directory. Record pre-existing failures separately.
- [x] Review the full diff for business behavior changes and accessibility; fix introduced issues; record visual verdict and upstream integration notes.

## Verification commands

```sh
pnpm exec vitest run
pnpm exec eslint src
pnpm exec vue-tsc --noEmit
VITE_UI_PROFILE=enterprise pnpm exec vite build --outDir dist/enterprise
VITE_UI_PROFILE=classic pnpm exec vite build --outDir dist/classic
git diff --check
```

## Baseline

- Upstream/fork base: `bd41a718`.
- 53 Vitest files / 346 tests passed before edits (an existing ECONNRESET log was also printed).
- 5173 belongs to an unrelated MBC frontend and must not be stopped or reused.
- UI preview uses a dedicated port. If backend cannot safely start, use explicitly identified fixture-based visual verification and do not claim authenticated live E2E.

## Outcome

Implemented on `codex/enterprise-ui`, not merged or pushed. See `docs/ui/enterprise-verification-2026-09-04.md` for actual coverage and baseline exceptions. Classic remains the default pending user visual acceptance. The profile is a shared visual foundation and selected pattern adaptation, not a claim that every specialized view/state has been visually certified.
