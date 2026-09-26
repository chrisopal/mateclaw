Spec: FAIL
Quality: REQUEST CHANGES
Base: 6bdcf1b8
Head: 5d3793c3
Reviewer: GPT-6 Sol

HIGH: Previous selected chapter is kept as a current dependency; adopting its replacement immediately makes the newly selected candidate stale. Preserve historical provenance separately from current dependency validation and retain frozen head guard. Regression rewrite/adopt/read/assemble/replay.
HIGH: Assembly only accepts SELECTED although EDIT selects HUMAN_EDIT. Include both permitted statuses and regress human-edited assembly.
HIGH: Image material membership does not classify content; require server-authorized image metadata, reject wiki/presales text.
MEDIUM: Apply full output size limit to EDIT evidence arrays/envelope.
MEDIUM: Add persisted task completion/STALE and successful adoption workflow regressions rather than only direct sequential handler calls.

Root gate passed 32 tests; reviewer did not repeat suite. Java LSP unavailable, Maven compilation evidence only. Fix assigned to original Luna implementer; Task4 not started.
