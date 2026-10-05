# AC-20 versioned request hash substrate — engineering evidence only

Status: **SUBSTRATE_VERIFIED_APPLICATION_NOT_INTEGRATED**. Historical receipt policy remains pending. At this checked candidate PresalesService was byte-identical to HEAD; the later independent AQ05 result-projection extraction does not change its hash/writer/replay methods. The V2 utility is not yet used by application writers or replay. This working proposal is uncommitted and has not been pushed, deployed or merged. AC-20 and all formal acceptance gates remain open.

## Candidate identity and changes

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`; committed tree `3a16cabe1768953a4887c2613a1238b222815f55`; origin/dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`. Preceding substrate candidate file SHA-256 values and retained log/archive SHA-256 values are in [hash-v2-substrate-results.json](hash-v2-substrate-results.json). This committed tree does **not** include the untracked candidate; no exact staged-tree submission check has been claimed.

- PresalesRequestHashV2 hashes the existing serialized envelope using a fixed ASCII domain plus NUL and manually emitted UTF-16 big-endian code units. It returns `v2:` plus 64 lowercase hex and strictly distinguishes known legacy/V2 stored formats. It does not normalize JSON or change the shared semantic hash.
- V219 SQL in h2/mysql/kingbase only widens request_hash from 64 to 67, preserving NOT NULL. No receipt/backfill or existing migration/source changes. All five frozen V218/V1 Java source hashes independently match the manifest and published HEAD bytes.
- Two test modules protect independent fixed vectors (including isolated property names), collision distinctions and real H2/Flyway storage behavior. The migration snapshot now serializes JDBC strings as exact UTF-16 code units; this proves the seeded JDBC facts remain identical, not physical database-page byte identity. All fixtures are synthetic.

## Commands and actual results

JDK 21 selected with `/usr/libexec/java_home -v 21`.

```bash
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
mvn -B -pl mateclaw-server -am \
  -Dtest=PresalesRequestHashV2Test,PresalesRequestHashMigrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -am '-Dtest=Presales*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
python3 /tmp/mateclaw-hash-mysql-probe.py
```

- Initial dev `szxr7ysm`, actual exit 0 / SCAN_PASS. Substrate dev `g4k0xzzq`, actual exit 0 / SCAN_PASS. Final source scan `b93ai_6r`, actual exit0/SCAN_PASS; scoped read-only Google Java Format1.22.0/AOSP check actual exit0. Application toolchains NOT_RUN by quick-scan definition; submission_ready=false.
- Genuine RED against the old production primitive: 14 hash tests, 12 failures, 0 errors/skips. Five distinct code units collapsed to one hash, including real serialized Create and extension/property-name collisions. Seven migration tests failed because V219 did not exist. Original RED XML and Maven log retained.
- First GREEN: 23 tests, 0 failures/errors/skips. After review changes and scoped formatting, final presales run: 313 tests total, **312 actually executed**, 0 failures/errors, 1 existing conditional skip (PRESALES_PPT_SKILL_ROOT absent). New modules account for **25 executed tests**: 18 hash + 7 migration.
- The initial scoped Spotless expression matched no files. Direct cached Google Java Format1.22.0/AOSP then formatted the three new Java files, but a later real project Spotless check during AQ05 found differences in import ordering and string-concatenation layout. The claim that this was caused by untracked-file exclusion was incorrect. During AQ05 the actual configured Spotless formatter was applied to the six scoped candidate Java files using the correctly anchored regex selection, followed by project Spotless check and final regression. No check command used --fix and no gate/tool configuration changed. The preceding source hashes below identify the earlier snapshot; the current combined candidate hashes and final project verification are recorded in the AQ05 projection evidence.
- Independent bounded review `/root/authority_writer_audit`: COMMENT, no substantive substrate blocker. Two LOW evidence suggestions (UTF-8 snapshot loss and independent property-name vectors) were addressed before the final run; reviewer read-back confirmed both closed. Java LSP unavailable/NOT_RUN; compilation and actual tests are the Java evidence. This is not maintainer or stakeholder approval.

## Real database boundaries

H2 uses a minimal manually installed V211 presales schema, baseline216, then the actual V217/V218/V219 migrations. Seven cases cover exact seeded facts across four tables (including listing projection columns), 67-character write/read, scoped PK, NOT NULL, independent-connection visibility and rollback, repeated migrate/validate, presales fresh bootstrap and native SCRIPT/RUNSCRIPT isolated restore. This is a presales-slice bootstrap, **not a whole-system fresh install**.

MySQL used a separate loopback-only mysql:8.0 container pinned to the recorded cached image ID, anonymous data volume and randomly isolated mateclaw_aq_acceptance_* schemas. JDBC/Flyway used the existing project classpath; no dependency was added. Four actual phases passed: seed V218, native mysqldump/restore plus upgrade, source upgrade, and fresh presales bootstrap through219. Upgrade phases verify all four seeded table snapshots, exact67 storage/read, original scoped PK and NOT NULL (actual1048/1062 errors), independent-connection visibility/rollback, repeated migrate/validate and one V219 checksum row. The source and restored schemas are independent. Own container and anonymous volume were removed, exit0; temporary credentials were deleted and are absent from archived evidence. Existing mateclaw-mysql and production schemas were untouched.

The initial external MySQL probe failed because JDBC getColumns omitted the catalog and returned matching columns from multiple isolated schemas. Only the temporary probe was corrected to use connection.getCatalog; the SQL, assertions and repository tests were not weakened. The failed result is retained alongside the subsequent four successful phases.

Kingbase real execution, production upgrade/reverse migration, old/new writer mixing and rollback after V2 receipts remain NOT_RUN. Do not shrink the column to64 or delete new receipts on rollback. Native backup/restore here is technical fixture evidence, not production recovery approval.

## Integration still required

Choose the historical compatibility policy: reject only ambiguous legacy matches versus reject all legacy matches. Old receipts lack original request facts; response reconstruction cannot prove equivalence. Then integrate new V2 writes and strict stored-version dispatch under current actor/source/repair/replay/CAS/archive ordering. Protect HTTP409 payload preservation, no rejected persistence, original operationId reuse, ignored fields/property names, ordinary legacy behavior under the selected policy, concurrent first writes and rollback. Never auto-retry a rejected receipt with a new operationId.

Commit gate, full-range checked push, PR read-back, business acceptance and remote required CI have not been run for this unintegrated candidate. Source utility/storage tests do not close application idempotency or architecture acceptance.
