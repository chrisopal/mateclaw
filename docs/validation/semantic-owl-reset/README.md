# OWL RESET rehearsal evidence

This directory owns the isolated RESET fixtures and records the boundary of the delivery tool. The tool is [`scripts/semantic-owl-reset`](../../../scripts/semantic-owl-reset/). Its input is an explicit workspace/ontology/revision/graph/KB allowlist; it does not enumerate a workspace or delete by table.

The two fixtures are synthetic Functional Syntax documents used by the RESET-REBUILD rehearsal:

- `fixtures/quality.ofn`
- `fixtures/inventory.ofn`

The rehearsal uses a fresh H2 file created under `/tmp`, not the application `data/mateclaw` database. It verifies that ontology source snapshots, observed review snapshots, import artifacts, V206 source-change runs/locks/items, V207 migration plans, axioms, statement/evidence history, extraction receipts/actions, governance records/events, and mutation/command records are included in the target closure while the other graph and KB raw material remain. A deliberately missing rebuild fixture also verifies transactional rollback leaves the old ontology present. A committed manifest records the redacted datasource fingerprint, the actual closed revision/graph IDs, graph mutation versions, schema fingerprint, expected row counts, retained digests, backup path/digest, writer fence, and plan fingerprint.

MySQL mutation was exercised against a temporary database on the local `mateclaw-mysql` container. The migration rehearsal applies the repository MySQL V191–V204, V206, and V207 files, then verifies RESET and backup restoration against that actual migrated schema. It requires an explicitly named isolated `semantic_reset`/`owl_reset` database, the offline writer fence, and `--allow-non-h2`; the current business schema is refused. Kingbase is unverified because no Kingbase driver or isolated environment is available in this checkout. V204 source-review snapshots, V206 source-change runs/locks/items, and V207 migration plans are covered by metadata discovery; future semantic tables fail closed unless their rows are reachable through the explicit closure or are added to the schema-aware test.

The RESET scope does not retire `definition_json`: V199 intentionally leaves it nullable for the transition, and this delivery neither drops it nor rewrites it. Column removal requires proving that no out-of-scope retained rows depend on it and providing a matching-version restore plan. The user has already authorized replacement; this is a data-dependency gate, not a new permission requirement. No legacy column is treated as new OWL authority.

## Column-independent rebuild verification

The application runtime and RESET fixture INSERT no longer write `definition_json`. Both rehearsal scripts accept `SEMANTIC_RESET_TEST_RETIRED_SCHEMA=1`, which removes the column **only from their freshly created disposable database**, after seeding and before RESET. This tests rebuild and same-schema backup recovery without that column; it is not an application-database retirement command and does not restore a removed schema column. Default mode still verifies the transition schema retaining its nullable legacy column.

Evidence: [H2 variants](../semantic-owl-execution/reset-column-independent-rebuild.json) and [MySQL variants](../semantic-owl-execution/reset-column-independent-mysql.json). Actual application column retirement still requires the scope/data dependency checks and matching-schema recovery specified by the implementation plan.

The guarded `LegacyColumnRetirement` executor has a separate five-case rehearsal: `python3 scripts/semantic-owl-reset/run-retirement-rehearsal.py`. It requires the current server ontology test report for the existing dependency classpath and the local MySQL test container. It creates only temporary schemas and tests missing-fence refusal plus interrupted DROP/ADD recovery. It does not retire the application schema. See `../semantic-owl-execution/retirement-rehearsals.json` for the latest evidence.

## Isolated runtime column retirement (2026-09-09)

The guarded executor has now removed `definition_json` from the isolated H2 runtime database at port 18109. This supersedes the earlier pending-retirement statement for that isolated database only. The main checkout database has not been altered.

The first preflight refused H2's actual migrated `TEXT` representation (`CHARACTER VARYING`). The executor now records the original bounded SQL type in its manifest and restores VARCHAR with the original precision; restoration accepts only fixed LONGTEXT/CLOB or validated numeric VARCHAR forms. The five-case rehearsal includes H2 TEXT drop/restore/interruption recovery alongside CLOB and MySQL.

The isolated server was stopped before a full H2 file backup. All 10 existing revisions passed document, import-lock, policy and DL profile validation before DROP. Repeated apply confirmed the retired schema and identical document fingerprint. Restart returned health 200; a fresh ontology draft was inserted, edited and published, then a second draft was edited without changing published history. A subsequent independent request read the publication back.

Evidence: [runtime retirement](../semantic-owl-execution/retirement-isolated-runtime.json), [new persistence fixture](../semantic-owl-runtime/column-retired-persistence-readback.json), [rehearsals](../semantic-owl-execution/retirement-rehearsals.json). Physical retirement of main databases and Kingbase verification remain outside this evidence.
