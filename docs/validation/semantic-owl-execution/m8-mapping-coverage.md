# M8 role-mapping acceptance gap

Checked against docs/superpowers/plans/2026-09-09-m8-owl-revision-upgrade.md acceptance item 2.

The current OwlAssertionAdapterTest.roleMappingsPreservePunningNestedExpressionsAndLiteralText covers a nested ClassAssertion intersection/existential with class and property remapping, preserving the same IRI in its individual role. It separately verifies data-property/individual remapping does not rewrite literal text.

SemanticGraphMigrationIntegrationTest currently exercises accepted data facts, individual IRI swaps, wrong target property roles, atomic execution and append-only rollback. It does not yet prove nested class assertions and punned identifiers survive the entire HTTP migration and persisted history chain.

Required next acceptance: publish source and target with explicitly declared punned class/individual roles; accept a nested class assertion and a literal containing the mapped IRI; prepare typed mappings, approve, execute, inspect current assertion AST plus untouched literal/evidence references, then rollback and inspect both source restoration and retained target revision. Run H2 and isolated MySQL. Adapter coverage alone must not close this gate.

## H2 chain verified

The new SemanticGraphMigrationIntegrationTest.nestedPunnedAssertionsSurviveMigrationAndRollback now covers the HTTP prepare/approve/execute/rollback chain described above. Persisted canonical assertions and evidence references are asserted. H2 targeted 1/1 passed; m8-punning-h2.json records evidence. MySQL remains pending for this new test.

## MySQL chain verified

The same new case passed in real isolated MySQL, with 37/37 total regression tests passing and no skips. See m8-punning-mysql.json. This supersedes the pending MySQL note above for this fixture; it does not establish every OWL capability-matrix row.
