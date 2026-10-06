# AC-20 versioned request hash plan (Proposed)

Start: clean isolated worktree, HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2, tree 3a16cabe1768953a4887c2613a1238b222815f55; origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93. Initial dev `szxr7ysm`, actual exit 0 / SCAN_PASS. Preserve original checkout WIP.

R-05 / AC-20 require same-key same-input replay and different-input rejection. Current PresalesService hashes encoded Create or [projectId,Command] through StatementApplicationService.hash. UTF-8 encoding replaces isolated surrogate units with '?', producing the already characterized distinct-request collision. Receipt has only its scope/actor/operation key, hash and response; absent owners/defaulted fields and ignored command extensions make response reconstruction insufficient proof of original request.

The user policy question is pending: reject only ambiguous legacy matches (recommended) versus all legacy matches. No answer or elapsed time is approval. Independent work below is common to either choice. Do not modify production replay behavior or claim AC-20 fixed until the policy is settled and the real writer/replay paths are verified.

## Independent implementation

1. Before new production code, run negative regression cases against the current hash implementation. Preserve the original legacy collision characterization. Add independently generated, fixed SHA-256 vectors for ASCII, BMP, valid surrogate pairs, isolated high/low units, adjacent malformed units, '?', literal backslash-u and property-name differences.
2. Add a small presales-owned immutable V2 hash utility. Preserve the exact existing serialized envelope (order/null/extensions/action/project/expectedVersion/operationId), hash a fixed ASCII domain tag `mateclaw:presales:request:v2` followed by NUL, then each UTF-16 code unit as two manually emitted big-endian bytes. Charset encoders must not process request units. Store `v2:` plus 64 lowercase hex; version parsing accepts exactly legacy 64 lowercase hex or V2, rejecting unknown/malformed forms without fallback.
3. Recheck migration numbers; append matching V219 SQL migrations widening only mate_presales_operation.request_hash from VARCHAR(64) to VARCHAR(67), preserving NOT NULL. No receipt/body/revision/artifact rewrites or backfill. Use native dialect syntax. No Java migration entry or modification of frozen V217/V218/V1 sources.
4. Migration regression first: real Flyway V218 baseline -> V219, exact legacy table snapshot equality, 67-character write/read, existing PK/NOT NULL and transaction rollback, repeated migrate/validate, isolated backup/restore. Fresh V211 schema -> latest is also checked. H2 is not MySQL/Kingbase evidence; run a disposable loopback MySQL fixture, never existing project/production schemas.

## Dependent integration after policy confirmation

PresalesService encodes its current envelope once, writes new V2 hashes and dispatches stored versions strictly under the existing actor/source/repair/replay/CAS/archive ordering. V2 mismatch never tries legacy. Legacy mismatch remains OPERATION_CONFLICT. If only ambiguous legacy matches are rejected, test '?' and isolated units across the entire encoded envelope in both directions, including ignored extensions and property names; return explicit 409 without any body/revision/receipt update. Never auto-retry with a new operationId because the original command may have succeeded.

Lock exact retries and different project/action/version/null/extension conflicts, no rejected persistence, HTTP status and 409 input preservation, concurrent first-write/CAS/rollback behavior, and current ordinary legacy replay before later shape validation. Keep the shared semantic hash unchanged; its statement/review/governance consumers remain outside this fix. Update assertions transparently for new versioned writes, retaining explicit legacy fixtures rather than weakening the old-input requirement.

## Scope, review and rollout

Owner boundaries: V2 utility and SQL triplet by leader; one migration test module may be independently delegated; later Service/replay and relevant contracts only after policy confirmation. Reuse current repository/transactions; no generic request framework, feature switch, dependency or new business tables. Dev after coherent changes; targeted regressions; independent source/migration/compatibility review; exact staged commit, normal hooks and checked push only for a coherent integrated change.

Independent architecture analysis `/root/request_hash_compatibility_design` found the missing request facts and recommended the versioned prefix/minimal widening. This is technical evidence, not stakeholder/maintainer approval. Alternative version-column storage adds plumbing; unversioned silent replacement cannot distinguish legacy or support exact replay. Retaining vulnerable legacy matches knowingly preserves the defect and is not an accepted repair.

Do not deploy with old writers still creating legacy receipts. Widened schema can remain on rollback, but V2 rows and old readers require verified compatibility; do not shrink to 64 or delete newer rows. Real Kingbase, production rollout/reverse migration, full V2 object model, business QA and required CI remain unverified. AC-20 and the full goal remain open; pure utility/storage evidence alone does not close application idempotency.
