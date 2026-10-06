# AQ10 finite listing measurement

Engineering evidence only; AQ10 remains incomplete.

## Method

Verified candidate JAR SHA 0c1d90e324591260d1c667ac09d06752f0dd56233e83946f363abe6e46ad2b5b, candidate tree 4862bf6660e57a4b2515830c58134a17ab290a1c. Three production listing sources match e70924e3 exactly (source-hashes.json). Uses actual repository and frozen listing policy, V211 and Flyway V217-V219 on fresh isolated in-memory H2 databases; no existing data, model calls or new dependencies.

100/1000/5000 synthetic projects, 32 KiB requirement text each; page size20; first page, 10-percent substring match and out-of-range page. Five warmups and 30 samples per path; all 18 output comparisons pass exact serialized JSON byte equality. QUERY_CACHE_SIZE=0 is checked in INFORMATION_SCHEMA. Initial cache-enabled observations are preserved in cached-* and excluded from the primary table.

Java21.0.7, macOS/aarch64,12 logical CPUs,1.5GiB max heap. Shared host had a delivery gate running. Single reused JDBC connection. Timings include SQL preparation/execution and Java result projection/decoding, but exclude JSON response serialization, HTTP, authorization, pooling and network. No steady-state confidence interval or production SLA claim.

## Results

| Projects | Scenario | Reference / projected p95 ms | Reference / projected median thread allocation bytes | Reference / projected JDBC result rows |
|---|---|---|---|---|
| 100 | first-page | 4.431 / 1.237 | 3886816 / 261568 | 100 / 20 |
| 100 | filtered-page | 4.505 / 1.604 | 3646880 / 625208 | 100 / 10 |
| 100 | out-of-range | 3.828 / 0.465 | 3882032 / 213560 | 100 / 1 |
| 1000 | first-page | 35.021 / 0.981 | 38711256 / 330272 | 1000 / 20 |
| 1000 | filtered-page | 34.693 / 1.444 | 36362472 / 2222960 | 1000 / 20 |
| 1000 | out-of-range | 34.730 / 0.564 | 38711112 / 290536 | 1000 / 1 |
| 5000 | first-page | 174.590 / 2.352 | 193524304 / 714896 | 5000 / 20 |
| 5000 | filtered-page | 173.161 / 6.248 | 181767200 / 9310896 | 5000 / 20 |
| 5000 | out-of-range | 173.774 / 2.262 | 193524112 / 659104 | 5000 / 1 |

Each path issues one statement. The projected empty page returns one LEFT JOIN sentinel, not one business object. Resultset rows do not measure database scanned rows. Full EXPLAIN, p50, raw samples, GC and heap observations are in results.json. H2 selected the workspace index; count/fault scans, substring LIKE and sorting remain. No index or SQL was changed.

Heap is sampled after operations, not peak or retained memory. Thread allocation is not retained heap. DB_CLOSE_DELAY=-1 retains earlier scale databases until JVM exit, so later process heap and GC include prior datasets and sequential history. Do not interpret these as isolated per-scale memory.

## Failures preserved

Initial Jackson tree type comparison rejected output with differing in-memory numeric node types but identical serialized JSON. Both 5252-byte outputs and failure logs remain; comparison was changed to exact JSON bytes without deleting fields or weakening values. H2 result caching produced implausibly small repeated-query timings; cache-enabled results were preserved and the entire finite matrix rerun with query caching explicitly disabled.

## Independent review

Native reviewer /root/aq10_measurement_review returned COMMENT: no blocker for this bounded descriptive H2 claim; one LOW documentation clarification about retained earlier databases, now recorded above. It verified all18 samples, comparator, JAR/extracted class identity and cache setting. Java LSP was unavailable and AST tooling absent; neither counted as passing. This is technical review, not maintainer or formal acceptance.

## Remaining scope

Concurrent HTTP load, MySQL/Kingbase, cold caches, multi-workspace distributions, large extension metadata, V2 dependency impact graph and production logging/end-to-end observability remain unverified by this experiment. No numerical SLA was invented. No production source, frozen migration, existing test or gate changed. AQ10 remains open.

## Repository evidence package

See [measurement.tar.gz](aq10-listing-measurement/measurement.tar.gz) for the Java harness, plan, identities, complete raw measurements, preserved initial failures/cache-enabled run and manifest. SHA-256 `a5c28009adf93b2dd27dc2da8c92b3dca2e550b45f7ec4f658896e44ced18cf5`. Relative file references above resolve inside that archive. Runtime dependencies were extracted from the verified JAR and are not duplicated in this small evidence archive. Current production code is committed as 5f61abce; this measurement does not prove current remote CI or full AQ10 acceptance.
