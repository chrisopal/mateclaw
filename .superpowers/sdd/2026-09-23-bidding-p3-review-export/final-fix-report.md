Status: DONE
Verification: PASS
Plan deviations: none

Baseline-scoped HUMAN_TODO revisions use an identity derived from the exact analysis baseline and requirement ID. Immutable stored dependency refs include that baseline plus original source refs; the todo payload's sourceRefs remains sources-only. Read and both approval predicates select only current-baseline todos and fail closed when the current todo or provenance is missing. Classify and resolve also require a bound, currently selected baseline, so pre-migration todos without baselineRef remain readable as history but cannot be changed. Same-baseline manuscript revisions reuse the current todo and decisions.

Regression coverage reuses a resolved, nontechnical legacy COMM-1 todo in a fresh baseline and confirms the new todo is OPEN/UNCLASSIFIED, its stored refs include the exact baseline, and both approval paths block it. A legacy unscoped OPEN todo whose source remains selected is rejected for both classify and resolve with no new revision and unchanged stored payload. Source-replacement coverage seeds a selected baseline and confirms the old source is no longer selected. The fresh-baseline scenario advances the same `analysisBaseline/current` head from version 1 to 2, asserts exactly one head update and the old/new selected states, and runs the real dependency validator on the new baseline. Existing reviewer access revocation assertions remain intact.

Focused verification: `mvn -pl mateclaw-server -am -Dtest=BiddingReviewTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full -Dmateclaw.skill.workspace.root=/tmp/mateclaw-review-skill-root test` with JAVA_HOME and PATH set to `/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home` passed; 7 tests, 0 failures, 0 errors, 0 skipped. `git diff --check` passed.

Acceptance remains incomplete for fresh-workspace full actual-model execution, human golden review, and Kingbase validation.
