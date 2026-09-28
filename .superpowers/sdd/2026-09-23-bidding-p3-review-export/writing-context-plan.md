# P3 actual-model writing context repair

Observed in an isolated, awake DeepSeek run on 2026-09-28: four analyses succeeded, the synthetic baseline was confirmed, and the outline task succeeded. The first chapter-writing task then failed with persisted `INSUFFICIENT_CONTEXT`. Its log reported a protected-observation retention failure at estimated 11,298 tokens in a 128,000-token window, immediately after `ConversationWindowManager` pruned one older tool response. The writer had loaded the same pinned `output.schema.json` twice; the earlier response was protected project evidence.

The pre-request `pruneOldToolResultsForModelInput` path performs duplicate replacement or spill on older responses without checking `isProtectedObservation`, unlike the other trimming paths. Scope the repair to `ConversationWindowManager.java` and `RestrictedProjectObservationTest.java`: first add a failing regression with a protected older pinned-schema response and a later duplicate; then preserve its exact bytes while retaining ordinary duplicate compaction. Do not increase model windows, relax schema checks, or grant filesystem reads.

Acceptance: focused red/green regression; full selected backend gate; independent GPT-6 Sol review; repackage and retry only the failed chapter-writing task in a fresh copy of the isolated QA database. Full real-bid quality remains open.
