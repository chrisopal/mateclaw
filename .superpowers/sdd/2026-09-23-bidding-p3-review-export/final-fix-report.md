Status: DONE
Verification: PASS
Plan deviations: none

Baseline-scoped HUMAN_TODO revisions now use an identity derived from the exact analysis baseline and requirement ID. Their immutable stored dependency refs include that baseline plus original source refs; the payload's sourceRefs remains sources-only. Read and both approval predicates select only current-baseline todos, fail closed when the current todo or provenance is missing, and retain immutable history. Same-baseline manuscript revisions reuse the same todo and decisions.

The regression reuses a resolved, nontechnical legacy COMM-1 todo in a fresh baseline and confirms the new todo is OPEN/UNCLASSIFIED, its stored refs include the exact baseline, and both approval paths block it. Existing source replacement and same-baseline resolution coverage remains in place.

Focused verification: `BiddingReviewTest` passes in an isolated skill root with Java 21 (7 tests). The regression path verifies current-baseline blocking, exact baseline dependency persistence, and retained same-baseline decisions.

Acceptance remains incomplete for fresh-workspace full actual-model execution, human golden review, and Kingbase validation.
