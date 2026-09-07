# M1 final integration review

Verdict: **APPROVE**

Reviewed code range: `3a0c0948..9da3971b` in the isolated `semantic-m1` worktree. Scope is SEM-01 through SEM-03: ontology core, durable workspace API, and ontology management UI. Read the M1 execution plan, frozen wire contract, UI blueprint, all three approved task reviews, implementation files, test reports, and runtime readback artifacts. This pass concentrated on integration and did not repeat styling review or expand to M2.

## Integration findings

- Core remains JDK-only in production. Server performs mapping, authentication, workspace checks, persistence, transactions, and HTTP error conversion; no reverse core-to-host dependency was introduced. DTO enums and definition fields agree with both the validator and UI adapter.
- Feature configuration consistently uses `mateclaw.semantic.enabled`, default false. Business controller/service assembly is conditional, the application access service also denies disabled access, and authenticated status remains available. Menu and direct routes require both the feature status and backend-derived capabilities.
- Every ontology operation requires explicit workspace scope and reloads the current active principal. The semantic interceptor runs before the legacy scope fallback; the application service repeats the scope/role check. Published revisions, draft bases, differences, and operation reads resolve through the scoped ontology parent. Viewer/member/admin-owner policy matches the new capability mapping and UI actions.
- Parent locking, conditional draft SQL updates, the unique draft slot, and the monotonic ontology draft counter consistently protect create/save/discard/publish and prevent old CAS tokens from authorizing replacement drafts. Publication revalidates persisted content, so an old UI validation report cannot authorize new content.
- Publication consumes the draft and writes the published revision, latest pointers, command result, and governance record in one transaction. Idempotent replay is authorized and scoped before result lookup, and resolved before requiring the consumed draft. Cross-ontology operation reuse loses atomically under the workspace operation-key constraint. Historical definition and metadata have no post-publication mutation route; availability changes only governed availability state.
- UI uses the existing HTTP client and pins the captured workspace after its interceptor. Aborts, generation checks, and the existing layout route/workspace key prevent stale requests from becoming active content. The removable workspace guard preserves dirty input on rejected switches. CAS conflicts retain input; edits invalidate validation. Ambiguous publication outcomes preserve the original operation ID and payload, block conflicting edits/navigation, and expose recovery through the operation result contract.
- M1 user flows are present: searchable list, creation, type/property/relation editing, precise validation location, deletion/reference protection, draft discard, immutable version detail, copy to a new draft, saved-state differences, publication, and availability control. Latest requested stacked equal-width metadata fields are in the delivered code and runtime record. No graph bindings, fact management, agent tools, or fake binding counts were added.

## Evidence and limits

- Read current Surefire XML: core 12 tests and server semantic 24 tests (including three architecture checks), with zero failures/errors/skips.
- Read UI records: 18 feature tests and all 377 tests across 60 files passed. The full-suite log also contains a non-failing `ECONNRESET` / `socket hang up` diagnostic; this review does not claim an empty diagnostic log.
- Read runtime artifacts: 22 API checks; role read/write denial evidence; v1/v2 UI creation and publication; immutable v1; stored differences; availability persistence; workspace-switch cancellation; metadata layout and mobile width observations.
- Read shutdown database evidence: latest version 2, no remaining active draft, both published rows retained, two publication governance rows and one availability governance row, successful V191 migration. Parent reports restart API readback and compiled preview validation as separate delivery gates.
- Ran `git diff --check 3a0c0948..9da3971b`: pass. This reviewer did not run Maven, Vitest, or builds to avoid shared-target races; parent-provided fresh typecheck, owned lint, and classic/enterprise build evidence remains attributed to the parent gate.
- Standalone MySQL/Kingbase execution remains the disclosed later environment gate. The missing inherited precision-check wrapper and existing large vendor chunk are disclosed baseline constraints, not grounds to extend M1.

Required fixes: **none**. No blocking integration or security findings in the reviewed M1 scope.
