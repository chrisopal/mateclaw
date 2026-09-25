# Bidding P1 workbench UI blueprint

## Product truth
Real users are workspace members who manage one-lot bidding projects. The real path covers project ownership, employee assignments, PDF/DOCX sources, four analysis results, evidence, human edits and confirmation, and persisted task recovery. The existing bidding API and database own state. Employee/skill readiness comes from the server. P2 outline, writing, review, and export stages are unavailable and must appear as unavailable dependencies, with no action controls. No fixture results or invented metrics are presented as business data.

## Primary task
From `/bidding`, create or open a project, assign a real workspace member as owner, bind available analysis employees, upload and read sources, select the source set, dispatch analysis, resolve task failures and result conflicts, inspect evidence, edit results, and confirm the baseline. Refreshing the workbench re-reads the current authorized project, source set, analysis revisions, and baseline from the server.

## Information architecture
- `/bidding`: workspace-scoped project list, owner/stage filters, four metrics, create and open actions.
- `/bidding/:id`: project title and six tabs. Overview owns project and role assignments plus phase status; Sources owns immutable file versions, readability, content preview and source-set confirmation; Analysis owns four result panels, evidence, editing and baseline confirmation. The remaining three tabs show the concrete P2/P3 dependency state only.
- Task drawer: paged project tasks, attempts, failure detail, retry/cancel actions, and collapsible diagnostic JSON. Poll only while open and active tasks exist.
- Evidence drawer: authorized source block and matching quote.

## State inventory
Pages distinguish initial loading, empty project list, empty project sources/results, populated state, partial source/result/task failures, stale-version conflict, unavailable feature (404), request failure, workspace switch, read-only role, missing employee/skill configuration, withdrawn or unreadable source, incomplete analysis, and successful refresh/confirmation. Disabled actions sit next to a short, current prerequisite reason. Old workspace/project responses are aborted or ignored; edits survive a 409.

## Visual mapping
Keep the existing Element Plus and enterprise theme. Use existing semantic surface, text, border, primary action and danger tokens. Dense list and result tables use the established compact enterprise spacing; form labels remain aligned. Primary commands are filled blue, inspect/edit actions outlined, destructive actions danger. Status includes text/icons as well as color. Keep the page on the current canvas and avoid nested card mosaics. Desktop content uses a clear title/action row, compact metrics, then continuous table/panels. Tables own horizontal overflow at narrow widths. Action groups use 8px gaps and remain together; drawers keep independent long-content scrolling. Honor light and dark theme variables, focus indicators and reduced-motion behavior.

## Interaction contract
All project, member, employee, capability, source, revision, baseline, evidence and task data comes from typed workspace-scoped API calls. Owner controls submit `userId`. Each read has an abort signal and captured workspace/project identity; switching workspace aborts reads, and late writes cannot replace current state. POST requests have operation IDs and no automatic network retry. 409 keeps the draft and presents a reload/resolve path. Upload uses multipart API; source confirmation and dispatch use explicit project commands. Result editing creates a candidate revision; confirmation is available only to authorized approvers after complete/current results. P2/P3 tabs are read-only status surfaces.

## Acceptance evidence
Run the three bidding Vitest files, direct ESLint on changed TypeScript/Vue sources, precision check, `vue-tsc --noEmit`, and Vite build to a temporary outDir. Run focused server dashboard and analysis tests. UI source behavior should cover unavailable API, owner userId submission, stale workspace responses, 409 draft preservation, capability/employee/source/task recovery states, and persisted refresh readback. Browser screenshots at 1440px and 390px in light/dark, and live business E2E, are separate acceptance evidence; code/tests/build do not claim those runtime states.
