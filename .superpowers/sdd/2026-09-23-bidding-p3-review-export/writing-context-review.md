# Writing-context repair review

Range: `5e36127a..18651718`

## Spec compliance

**PASS.** `ConversationWindowManager.pruneOldToolResultsForModelInput` now checks `isProtectedObservation(response, conversationId)` in the pass-through branch before duplicate replacement and spill (`ConversationWindowManager.java:1002-1011`). This preserves the exact protected body while retaining the existing `seenLargeOutputs` behavior.

The new regression models an older protected pinned-schema response followed by the same later response and, in parallel, an ordinary duplicated response (`RestrictedProjectObservationTest.java:240-264`). The protected body must remain byte-exact; the ordinary older body must still become the existing duplicate placeholder. Against `5e36127a`, the protected assertion is necessarily RED because the older body reaches the duplicate branch and is replaced. No separate RED commit or saved RED log was found; commit `18651718` records RED then GREEN execution.

The diff changes only the pruning guard and its regression. It does not change model/window limits, schema validation, tool disclosure/callbacks, `read_file` availability, authorization, or protection registration/lifecycle.

## Quality

No CRITICAL, HIGH, MEDIUM, or LOW findings. The fix reuses the established conversation-scoped protection predicate and is ordered before both lossy rewrite paths. Adjacent request paths already pass the conversation id, and existing storage logic keeps protection scoped by `(conversationId, toolUseId)`.

`git diff --check` passed. LSP diagnostics and AST-grep were unavailable in this environment. Maven was intentionally not rerun per review scope; the root agent's fresh compiler/full gate remains the type and runtime-test evidence.

**SPEC PASS**

**Quality APPROVE**
