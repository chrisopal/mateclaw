# Requirement response identity clarification

Status: DONE
Verification: PASS
Plan deviations: none

Actual writer produced 5 accepted chapter candidates; scoring-only chapter failed RESPONSE_INVALID twice because it used a criterion ID as requirementRef. The runtime now explains the existing business contract: responses identify only assigned requirements, an empty requirements list means empty responses, and scoring references belong in criterionRef citations/unresolvedItems. All skills/schemas, input snapshots, validator and receipts are unchanged.

Captured actual ChatModel request regression failed before this instruction was supplied, then fresh runtime8/task21/presales1 =30 tests PASS. git diff --check PASS. Java LSP unavailable; fresh Maven compile supplies type evidence. Logs /tmp/mateclaw-p2-runtime-response-red.log and -green.log. Live isolated retry and sibling hashes remain pending reviewed deployment.
