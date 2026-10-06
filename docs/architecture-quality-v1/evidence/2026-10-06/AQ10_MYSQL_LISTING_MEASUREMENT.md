# AQ10 bounded real-MySQL listing measurement

Finite external engineering experiment completed. AQ10 remains open; no production SLA, business acceptance or full-system migration claim.

## Scope and runtime identity

Read-only reuse of production candidate JAR SHA256 `0c1d90e324591260d1c667ac09d06752f0dd56233e83946f363abe6e46ad2b5b`, original candidate tree `4862bf6660e57a4b2515830c58134a17ab290a1c`. JAR entries were compared byte-for-byte with the extracted production classes and MySQL migration resources before execution (`runtime-identity.json`). The three listing source hashes also match the current candidate checkout HEAD `711e9fdc934216972cac92fa694c1d437582a569` (`source-identity.json`). No repo source/index, original H2 evidence or dependency JAR was changed. No model, application HTTP, live project data or permission bypass was used.

Local existing image pinned by full ID `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`; `SELECT VERSION()` reports **8.0.46**. One newly created container bound solely to `127.0.0.1:57428`; no image pull or dependency addition. Three new acceptance schemas each had zero tables before setup. Actual MySQL V211 was applied, followed by Flyway baseline216 and actual V217, V218 and V219; history confirms success for each. This deliberately narrow setup is not the complete application Flyway migration sequence or a populated legacy-data upgrade/backfill test.

## Method and limits

Same synthetic matrix as H2: 100/1000/5000 projects, 32KiB requirement text per project, page size20, first page, substring filter matching10%, and out-of-range page. Actual production repository compared against frozen full-body listing policy. Five warmups and30 measured operations per path,18 path/scenario/scale measurements. Each path has an exact serialized JSON byte comparison outside timing (nine reference self-checks and nine meaningful projected-versus-reference comparisons); timing iterations additionally check total stability. No fields are removed or normalized for equality. Timing path order alternates at1000 as in H2, but is otherwise sequential and not randomized.

Java21.0.7, macOS/aarch64,12 logical CPUs,1536MiB max Java heap. One reused JDBC connection per schema over loopback and Docker, warm repeated calls, shared host. Timings include SQL preparation/execution, JDBC/network transfer and Java projection/decoding; response JSON serialization, HTTP, authorization and pooling are excluded. SQL statement/result-row counters and EXPLAIN are collected outside the timed loop. MySQL8 has no legacy query cache; the H2 cache setting is not applied. InnoDB and OS caches remain warm.

**MySQL data and indexes reside in the new container's tmpfs `/var/lib/mysql`, not production disk.** Schemas from earlier scales remain until the owned container is deleted. MySQL buffer/cache and process history are therefore not reset per scale. Client Java thread allocation excludes database-server allocation and retained server buffers. Java sampled heap is after operations, not peak or retained heap; GC/heap observations also include earlier JVM history. No cold-cache, production storage, concurrency, steady-state confidence interval or universal speedup claim is made; H2 and MySQL numbers have different transport/storage boundaries and are not a controlled engine comparison.

## Results

Values in each pair are full-body reference / projected. All18 comparison observations pass; all18 paths issue one SQL statement.

| Projects | Scenario | p50 ms | p95 ms | Median client thread allocation bytes | JDBC result rows |
|---|---|---|---|---|---|
| 100 | first-page | 14.191 / 0.829 | 21.869 / 1.012 | 10520128 / 68792 | 100 / 20 |
| 100 | filtered-page | 13.763 / 0.984 | 21.413 / 1.153 | 10283296 / 48680 | 100 / 10 |
| 100 | out-of-range | 14.119 / 0.580 | 20.143 / 0.694 | 10518448 / 20368 | 100 / 1 |
| 1000 | first-page | 139.331 / 1.250 | 152.613 / 1.555 | 105111192 / 69232 | 1000 / 20 |
| 1000 | filtered-page | 140.414 / 3.479 | 153.941 / 3.751 | 102762408 / 73048 | 1000 / 20 |
| 1000 | out-of-range | 139.290 / 1.563 | 147.986 / 1.841 | 105111208 / 20360 | 1000 / 1 |
| 5000 | first-page | 716.964 / 4.384 | 778.985 / 4.690 | 525535392 / 68304 | 5000 / 20 |
| 5000 | filtered-page | 701.823 / 14.801 | 759.501 / 15.674 | 513778432 / 72936 | 5000 / 20 |
| 5000 | out-of-range | 698.489 / 6.619 | 734.659 / 6.946 | 525535408 / 19768 | 5000 / 1 |

An empty projected page produces one LEFT JOIN sentinel result row and zero business objects. JDBC result rows are not database scanned rows. EXPLAIN `rows` values are estimates, not actual scan counts; no EXPLAIN ANALYZE was used. Plans retain count/fault checks, substring filtering and temporary/filesort steps; the workspace and listing-order indexes are chosen in the retained plans. No production SQL, index, frozen migration or comparator was changed to improve these figures. Raw30 latency samples, exact SQL, EXPLAIN plans, per-path heap and GC observations are in `results.json`.

## Verification and cleanup

`javac` exit0; direct Java harness exit0 with `COMPLETED 18 measurements; all output comparisons passed`. Exact command arrays are in `commands.json`, outputs in `javac.log` and `measurement.log`. `python3 verify_and_report.py` checks the full finite matrix, totals, row counts, iterations, migrations, exit codes and cleanup proof. Java LSP is unavailable; diagnostic server discovery offers no Java backend (`diagnostics.json`). No LSP or repository gate PASS is claimed. This worker was explicitly restricted to external experiment artifacts and did not execute repository builds/tests/gates; parent owns delivery-gate evidence.

Container `dd6b26e1b896116846e1fc75a2a94f9238b4c2b0d0351680a51367a1db794f76` was stopped, read back as exited/running=false/exit0/OOMKilled=false, then removed by its exact recorded ID after ownership-label verification. Its absence was verified. Only its tmpfs data and owned private credential files were removed. No existing container/database/volume was reused or cleared. `container-lifecycle.json` retains stopped-state and removal evidence. Private credentials were generated locally, supplied through a private env file and process environment, never printed or included in the manifest, then removed. Shareable files are scanned for secret-shaped literals. Generated harness classes and reused runtime/dependency JARs are excluded from `manifest.json`.

No measurement or SQL/output failure occurred. Parent /root independently checked all18 groups,30 samples each, nearest-rank p50/p95, equality flags, all three version/empty-schema/migration records, exact-ID container absence and private-directory deletion. Its bounded review returned COMMENT with no new blocker for this experiment. This is technical evidence review, not maintainer approval or complete AQ10 acceptance.

## Remaining scope

Kingbase listing measurements, concurrent HTTP load, cold caches, production disk/network distributions, multiple workspaces, large extension metadata, full populated migration/backfill behavior, V2 dependency impact graph and end-to-end logging/observability remain unverified here. This completes only the bounded real-MySQL counterpart of the H2 listing experiment.

## Repository evidence package

The [measurement archive](aq10-mysql-measurement/measurement.tar.gz) contains the 21 shareable manifest files plus manifest.json, including the harness, ownership-safe runner, raw samples, plans and cleanup proof. Archive SHA-256 `cc362d5ec97ce523f35de9290e1445eb735ce3b205f24f67acaa4ebee3665aa7`. File references above resolve inside this archive. Runtime/dependency JARs and generated class files are excluded; the verified JAR identity is retained for reproduction.
