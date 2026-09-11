# T09 persistent API lifecycle acceptance

Run `python3 output/ui-acceptance/2026-09-11-t09/verify_lifecycle.py` from the semantic-m1 checkout. It authenticates through the existing controlled `runtime_client.py`; credentials remain only in memory. Runtime must listen on localhost:18109. Every invocation creates a new timestamped synthetic workspace, ontology, knowledge base and graph. Existing fixtures are never reset or changed. IDs are recorded in `lifecycle-results.json`.

Verified 2026-09-11: 12 checks PASS against the persistent runtime.

- Same display name retains two distinct entity IDs.
- Exact quotations read back from two separately imported sources.
- Two source-backed facts reuse the explicitly selected entity ID without creating more entities.
- Pending facts are absent from formal search.
- Accepted interval facts appear; accepted UNKNOWN-time facts do not.
- Facts outside their interval are absent.
- A stale review revision is rejected with HTTP 409.
- Retraction persists and removes a fact from formal search.
- A contradictory positive/negative assertion cannot be accepted (HTTP 409).
- Withdrawing a source produces SUPPORT_LOST and removes facts from search; evidence returns HTTP 404.

The source endpoint accepts synthetic wiki raw materials. These checks verify authenticated HTTP behavior and persisted readback, not model extraction or real model-generated answers. `semantic_context` exists as a Spring tool, but there is no direct HTTP context/tool-execution route. Its integration verification belongs to the parent task. `prepare_sample.py` separately creates and accepts a synthetic modeling proposal through its existing HTTP API. Its live source contains a repeated sentence in two different contexts; `sample-fixture.json` records IDs and exact quotations for browser testing. It does not create semantic facts automatically or inject database rows.

No product code changes, dependency installation, database reset, Maven execution or commit is performed by this script.
