# Bidding P2 enterprise workbench blueprint

## Product and scope

Keep `/bidding` and the six existing business tabs in the shared enterprise shell. P2 adds optional receipt of an exact published presales version, authorized project materials, human-confirmed technical outline, chapter writing and candidate adoption. Independent projects do not require presales. P2 produces a selected technical draft, not an approved or exported bid. P3 review/export controls remain unavailable.

## Surfaces

- Overview: existing real owner and employee binding controls. Presales receipt is a bounded dialog: choose an accessible project and explicit published version, inspect baseline/solution/risks/version summary, then receive. Later source releases never silently replace the received one.
- Files and sources: retain P1 tender upload/read/confirmation and add a separate materials section. Authorized knowledge pages must be explicitly selected with applicability and current validity. Revoked content is removed from rendered state and local drafts when authorization fails.
- Analysis: retain four results, evidence and baseline confirmation. Outline dispatch follows only the saved workflow consent and valid employee configuration; otherwise show one actionable missing-prerequisite state.
- Outline: chapter tree and unmapped mandatory requirements. Saving a candidate and confirming it are separate commands; candidate status never enables chapter writing. Validation belongs to the server; show its consequential errors next to the affected item.
- Technical writing: 260px chapter navigation and `minmax(0,1fr)` document area. Below 1100px collapse navigation instead of squeezing prose. On-demand evidence drawer; no permanent third column. Each chapter has its own task state, retry and selection status. Compare candidates with input version, changed content, missing evidence and selected status before adoption.
- Review/export: P3 unavailable; do not present a draft as a final approved document or invent working download controls.

## Enterprise grammar

Use existing `--mc-*` semantic tokens and Element Plus components under `--mode enterprise`. Flat solid surfaces, small radii, thin separators; no decorative dashboard cards. Page/section headings, version metadata, compact action groups and readable document blocks have separate spacing. Ordinary commands are blue filled or blue outlined buttons with visible focus and 8px grouped gaps; navigation may use link styling. Raw UUIDs, execution codes and implementation descriptions stay outside the reading path. Translate business states/categories. Empty states contain one useful state and a real next action, not explanatory paragraphs.

## Protection and acceptance

Preserve drafts on version conflict; warn before route/workspace/tab changes. Reject stale adoption in the UI and server. Never let late responses from another project/workspace repopulate current content. Read-only members see authorized results and cannot mutate. Authorization failure clears restricted data rather than retaining cached snapshots.

Verify actual receipt/outline/chapter APIs, references, refresh persistence and selective retries separately from fixtures. Run focused mounted tests, lint/precision, vue-tsc and an enterprise build. Inspect populated long-content layouts and evidence/dialogs at desktop and 390px, light/dark. Record real-model and real-tender acceptance separately; no mock output or screenshot count is business acceptance.
