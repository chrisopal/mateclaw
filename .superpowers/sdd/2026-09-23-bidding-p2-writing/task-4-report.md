Status: DONE
Verification: PASS
Plan deviations: none

Task4 review follow-up: closed the high-severity downstream event lifecycle gap. Baseline transition resolution now follows exact persisted baseline Ref edges to the latest selected baseline, then compares the old and latest semantic baseline while cloning only proven unaffected chapter bodies onto the latest outline with CAS. Outline/chapter events expose a confirmation envelope only when exact persisted transition edges reach current selected heads, the confirmed outline is based on the selected baseline, every leaf chapter is selected against that outline with all required responses resolved and no missing/unresolved items, and the latest manuscript inputRefs exactly match the selected outline and chapter heads. GET remains read-only; reconfirmation appends an immutable event revision. Historical outline/chapter revisions remain NEEDS_RECONFIRMATION; no review/artifact state is revived.

Fixed the outline replacement hook: editable outline candidates move the edit head before confirmation, so the old confirmed revision is now found independently of that head, including revisions already invalidated by a baseline transition. Replacing an outline marks the previous confirmed revision NEEDS_RECONFIRMATION. Human edit/adoption now likewise marks the replaced selected chapter revision NEEDS_RECONFIRMATION after recording its transition and invalidating descendants.

Added persisted regressions for successive baseline 1→2→3 confirmation, actual outline candidate save/confirm → chapter edits → manuscript assembly → GET confirmation → explicit command confirmation, and an assembled manuscript with MISSING_MATERIAL remaining blocked. The chapter-flow test uses actual writing edit/assemble service commands, HTTP read/confirm, and verifies prior revision state. Existing source/sourceSet v1→v2 evidence chain checks remain covered.

Verification (Temurin Java 21):
`JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" mvn -pl mateclaw-server -am -Dtest=BiddingSourceTest,BiddingAnalysisTest,BiddingOutlineTest,BiddingWritingTest,BiddingChangeImpactTest -Dsurefire.failIfNoSpecifiedTests=false test`
Result: BUILD SUCCESS; 43 tests, 0 failures, 0 errors (Source 17, Analysis 6, Outline 5, Writing 3, ChangeImpact 12). `git diff --check` passed.

Files changed in this follow-up: BiddingDependencies.java, BiddingOutlineService.java, BiddingRepository.java, BiddingWritingService.java, BiddingChangeImpactTest.java, and this report. No UI/controller files were staged or changed by this follow-up. The independently committed manuscript public-read fix remains preserved.

Remaining conservative limits: unknown dependency records, incomplete evidence, unresolved or missing required responses, and non-exact transition chains do not receive confirmation envelopes and remain blocked. Broader final repository gates and Sol review are intentionally left to the controller.
