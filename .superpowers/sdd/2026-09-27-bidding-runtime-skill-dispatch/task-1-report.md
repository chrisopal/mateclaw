# Runtime skill-dispatch repair

Status: DONE
Verification: PASS
Plan deviations: none

The actual production graph model prompt now receives the exact fixed package name/id, load_skill SKILL.md arguments and readSkillFile output-schema arguments. Task input and references are preserved. Shared scoped system text is domain-neutral without changing authorization or read-only boundaries.

Regression asserts the captured actual ChatModel prompt outside the stream callback so assertion errors cannot be swallowed as runtime stream failures. Before implementation, the focused regression failed on missing skillName. After implementation, BiddingEmployeeRuntimeTest (7), BiddingTaskTest (21), PresalesEmployeeRuntimeTest (1) passed: 29 tests, zero failures/errors/skips. Logs: /tmp/mateclaw-p2-skill-runtime-red.log and /tmp/mateclaw-p2-skill-runtime-green.log. git diff --check passed. Java LSP is unavailable; fresh Maven compilation provides type verification.

Live retry is pending reviewed deployment; this engineering report does not claim actual DeepSeek acceptance.
