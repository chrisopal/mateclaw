# Semantic OWL RESET tool

`SemanticOwlReset.java` is an offline, allowlist-only reset utility for the OWL test data described by [`reset-spec.md`](../../docs/validation/semantic-owl-01/reset-spec.md). It discovers the installed `MATE_SEMANTIC_*` schema through JDBC metadata, follows ontology/revision/graph, axiom/source, statement/evidence, extraction, governance, command, import-artifact, source-review snapshot, and V206 source-change and V207 graph-migration-plan references, and emits exact primary-key rows in a backup NDJSON file.

The command is dry-run by default. A dry-run still reads the target and writes a manifest plus a digest-verified backup, but performs no DML. `--execute` requires either an isolated H2 URL or an explicitly named isolated MySQL `semantic_reset`/`owl_reset` database with `--allow-non-h2`, the explicit offline fence `SEMANTIC_RESET_WRITER_FENCE=CONFIRMED_OFFLINE_STOPPED`, and an existing dry-run manifest supplied with `--manifest`; its closure, graph mutation-version, and schema fingerprints must still match. Exact workspace, ontology, revision, graph, and KB allowlists are required.

MySQL can be used against an explicitly named isolated `semantic_reset`/`owl_reset` database with `--allow-non-h2`; a production-looking database name is refused. Read-only dry-runs only need its JDBC driver on the classpath. Kingbase has no driver in this checkout, so it is not runtime-verified here. No password is accepted as a command-line argument; use `SEMANTIC_RESET_DB_PASSWORD` or another name passed through `--password-env`.

`--rebuild` inserts the two checked-in Functional Syntax fixtures from `docs/validation/semantic-owl-reset/fixtures`, creates published revision rows, graphs, and regenerated axiom indexes in the supplied isolated test database, and verifies document digests before commit. It never deletes knowledge-base records or raw/page content. Repeating a committed run with the same manifest and run id verifies the rebuilt fixtures and exits without changing rows. Passing `--restore-backup` with a committed manifest verifies the backup digest and restores its exact rows transactionally in an isolated target.

Run the full isolated H2 rehearsal from the repository root:

```bash
scripts/semantic-owl-reset/run-h2-rehearsal.sh
```

The rehearsal covers dry-run, row and graph mutation-version race refusal after the manifest, shared-snapshot and out-of-scope migration-plan refusal, rollback on a rebuild failure, transactional delete/rebuild, retained KB data, source-review observed snapshots, import artifacts, and idempotent replay. It creates a temporary H2 file under `/tmp` and does not open the application datasource.

For an available local MySQL container, the migration rehearsal applies the repository's MySQL V191–V204, V206, and V207 files to a temporary database, seeds V206 source-change and V207 migration-plan rows, executes RESET, restores the backup, and drops the temporary database:

```bash
scripts/semantic-owl-reset/run-mysql-migration-rehearsal.sh
```

Legacy `definition_json` retirement is outside this RESET scope. The tool leaves the nullable transition column untouched; dropping it requires a separately authorized migration and matching-version restore plan.
