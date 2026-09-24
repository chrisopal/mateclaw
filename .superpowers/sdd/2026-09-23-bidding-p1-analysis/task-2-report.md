# Task 2 — Source reading, evidence, and source-set confirmation

Status: DONE
Verification: PASS
Plan deviations: none

## Delivered

Implemented source upload/readback, immutable source bytes and SHA-256 binding, type/magic validation, extraction status persistence, bounded asynchronous read claims and retry, source-set confirmation/exclusions, evidence authorization, and downstream dependency freshness checks. Source extraction supports DOCX body/table ordering and PDF page order/coordinates. Deterministic block IDs are derived from source digest, locator, and extracted content. Unreadable, scanned, mixed image/text, or structurally ambiguous content is surfaced as NEEDS_REVIEW and blocks confirmation; only genuinely empty pages may be excluded with an approver reason.

The additive V213 migrations persist filename, read status, problem codes, and completion timestamp. V212 remains unchanged to preserve its Flyway checksum.

## Verification evidence

- TDD red: before implementation, `mvn -pl mateclaw-server -am -Dtest=BiddingSourceReaderTest -Dsurefire.failIfNoSpecifiedTests=false test` failed at compilation because `BiddingSourceReader` did not exist.
- Green: `mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='Bidding*Test' -Dsurefire.failIfNoSpecifiedTests=false test` — 25 tests passed, 0 failures/errors; Project 7, SourceReader 7, Migration 2, Source 9.
- The suite covers DOCX text/table locators, expected extraction fixture, deterministic reread block IDs; PDF order/coordinates, scanned and mixed image/text pages, empty page, corruption/encryption/type mismatch, 501-page cap; 25 MiB per-file limit; 100 MiB project-total boundary via a repository stub without allocating a 100 MiB blob; SHA and original byte readback; workspace authorization; source-set confirmation, exclusions, viewer rejection, retry/idempotency, two-claim limit, stale-read recovery, and current-set dependency validation.
- H2 migration test applies V212 then V213 and verifies the preexisting row/bytes remain and new fields receive defaults.
- `git diff --check` — PASS.

## Not run / limitations

- MySQL and Kingbase migrations were not run against live database instances (NOT_RUN); SQL dialect files are present.
- Browser/UI and model/OCR integration were not run (NOT_RUN). Scanned/mixed pages are deliberately marked NEEDS_REVIEW; OCR output is not used as a confirmation source.
- The 100 MiB project limit is validated at the repository boundary with a stub to avoid allocating a large blob; no 100 MiB integration blob test was run.

## Fix round 1 — Sol review findings

All six review findings were addressed within Task 2 ownership:

- DOCX reading now includes all relationship-ordered headers and footers with stable `header:n/...` and `footer:n/...` locators. Text boxes across document/header/footer/footnote/endnote XML receive stable package locators and `NEEDS_REVIEW` blocks until their contents are safely covered.
- A bounded 2-source `@Scheduled` poller invokes `readPending`; both `MATECLAW_BIDDING_ENABLED` and `MATECLAW_BIDDING_SCHEDULER_ENABLED` must be true. Startup recovery uses the same gates, so a disabled module/worker does not query bidding source rows. Integration verification enables the worker, uploads through HTTP, never calls `readPending`, and observes `READY`.
- Extracted evidence preserves literal URL text. The parser stores it as text only and performs no network resolution.
- `CONFIRM_SOURCE_SET` now requires `expectedSourceSetRef` (explicit `null` for an initial set), compares it to the current source-set head, and advances the head by version-CAS inside the project lock. Tests cover a stale writer and two concurrent writers using the same empty head; exactly one succeeds.
- The 100 MiB guard now applies to the exact selected source versions at confirmation. Uploads are not rejected by bytes belonging to unselected uploads. A source set may select only one version per source ID, avoiding superseded-version double counting. Boundary helper tests cover 100 MiB and integration tests cover unselected sources and superseded versions without a large test blob.
- Retry is rejected when the exact source version appears in any historical source-set revision, including revisions later marked `NEEDS_RECONFIRMATION`; this preserves previously issued block IDs and confirmed coverage after a later set replaces the current head.

### Fix-round TDD and verification

- RED: Java 21 run of `BiddingSourceReaderTest` after adding header/footer/textbox and URL regression tests: 9 tests, 2 expected failures — header/footer/textbox extraction incorrectly reported complete; URL text was rewritten.
- The first source-test run also exposed two test-harness issues (business code is in the response `data.code` envelope; the direct evidence assertion lacked HTTP viewer context). Assertions were corrected to inspect the response envelope and verify persisted evidence through the authorized read API.
- GREEN: `JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home PATH=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home/bin:$PATH mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='Bidding*Test' -Dsurefire.failIfNoSpecifiedTests=false test -q` — 33 tests passed, 0 failures/errors: Project 7, SourceReader 9, Migration 2, Source 15.
- `git diff --check` — PASS.
- MySQL and Kingbase migration/runtime verification remains NOT_RUN; no migration changes were made in this fix round. Browser, model and OCR integration remain NOT_RUN.

API contract note: source-set confirmation callers must now send `expectedSourceSetRef`, using explicit `null` when no source set is current and the last returned source-set ref thereafter. This is required for stale-write rejection.
