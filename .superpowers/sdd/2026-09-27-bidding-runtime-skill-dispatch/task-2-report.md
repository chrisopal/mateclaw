# P2 fixed output schema support

Status: DONE
Verification: PASS
Plan deviations: none

Actual DeepSeek retry read the pinned skill and produced parseable outline JSON, but the runtime schema subset rejected the approved bundled schema before payload validation. Added strict local $defs/$ref, exactly-one oneOf, uniqueItems checks. Only single-name local definitions resolve; external/missing/cyclic refs fail closed. Schema traversal has depth32 and node8192 limits. Existing unknown-key/type/field/range/receipt/config/completion/size checks remain.

Bundled outline and writing schema regression failed before implementation; it now accepts valid shape and rejects duplicate IDs, negative order, unknown block types, empty paragraphs, external/missing/cyclic references, ambiguous oneOf and unsupported keywords. Existing unsupported keyword regression uses anyOf because oneOf is now supported. Fresh runtime8/task21/presales1 =30 tests PASS, zero failures/errors/skips. git diff --check PASS. No Java LSP available; fresh Maven compilation is type evidence. /tmp/mateclaw-p2-runtime-schema-red.log and -green.log.

Actual accepted retry remains pending reviewed deployment. No business skill files or frozen task inputs changed.
