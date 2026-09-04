# Enterprise UI profile

The enterprise profile adapts MateClaw to the MBC-inspired visual system without replacing its Vue/Element Plus application. It retains routes, API calls, permissions, workspace switching, KeepAlive keys, theme store, and specialized chat/canvas/document surfaces. At the user's request, enterprise login/sidebar use the generated blue 3D mark documented in `enterprise-icon.md`; the classic profile retains the original logo.

## Preview and build

Run from `mateclaw-ui`:

```sh
# Explicit enterprise preview
pnpm exec vite --mode enterprise --host 127.0.0.1 --port 5176 --strictPort
# Enterprise artifact, isolated from the backend's static output
pnpm exec vite build --mode enterprise --outDir dist/enterprise
# Classic remains the default; this is also the rollback build
VITE_UI_PROFILE=classic pnpm exec vite build --outDir dist/classic
```

`.env.enterprise` contains only `VITE_UI_PROFILE=enterprise`. Unset or invalid profile values select classic. UI profile selection does not change the existing light/dark/system preference.

Do not rely on changing an environment variable after a static artifact has been built: Vite embeds this setting at build time. A release must build and deploy the selected profile explicitly.

## Ownership and upstream synchronization

Base: official/fork `dev` at `bd41a718` (2026-09-04).

Most customizations live in `src/styles/enterprise/`:

- `profile.ts`: explicit profile selection and root attribute.
- `tokens.css`: complete existing `--mc-*` palette remapping, including dark mode and chat/code tokens.
- `element-plus.css`: component variables and teleported overlays.
- `shell.css`: flat sidebar, compact page header, route scroll viewport, mobile navigation.
- `patterns.css`: app-owned semantic controls, cards, filter bars, dashboard and chat chrome.
- `login.css`: responsive split authentication layout.

Upstream file seams:

1. `src/main.ts`: one CSS entry and profile initialization before mount.
2. `views/layout/MainLayout.vue`: optional enterprise header, route viewport (`display: contents` in classic), mobile hidden-nav inert state.
3. `views/Login.vue`: a conditional layout wrapper around the **same** existing login/SSO/bind form, visible labels and accessible eye control.

On an upstream merge, review these three seams and changed selectors. Preserve upstream behavior first, then restore the profile integration. Do not copy the frontend into a second project or mass-replace colors in business modules. New feature pages inherit existing theme and Element Plus variables; add an explicit scoped selector only after observing a visual exception.

The source reference uses #1677FF. The adapter retains it for the selected navigation indicator and uses #0966D9 for small action text/white-label controls to improve contrast. Dark controls use a separate solid blue; neutral surfaces use blue-gray, not warm brown.

## Verification and scope

```sh
pnpm exec vitest run
pnpm exec vue-tsc --noEmit
pnpm exec eslint src
```

The repo's existing `build`/`lint` scripts reference an absent `../scripts/check-snowflake-precision.sh` in this checkout. Use the explicit commands above and Vite commands while reporting that upstream script gap separately; do not silently create a replacement gate.

Before release, inspect both profiles and light/dark modes at 1366, 1440, 1920 and 390px. Verify login, sidebar collapse, mobile navigation, language change, workspace switch, an editable form/dialog, settings navigation, chart/table scrolling, and chat input visibility. Keep model calls, workflow execution and document export verification separate from visual checks.

No model providers were configured for the isolated UI QA run, so disabled chat input and provider warnings are expected. A working UI is not evidence of working LLM inference. Original database files were not used: the QA backend ran against a fresh in-memory H2 database in a temporary directory.

Known upstream baseline: full ESLint reports `prefer-const` in `src/composables/useAgentRunGroups.ts:185` plus existing warnings. The same error reproduces from `git show HEAD:...`; this UI change does not alter that file.
