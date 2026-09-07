# Ontology management (M1)

This feature maintains ontology identities, a single editable draft per ontology, validation,
immutable published revisions, persisted revision differences, publication operation recovery,
and availability for future bindings. Graph binding, fact storage/query, semantic tools,
and Wiki indexing are later milestones and are not represented by sample counts or placeholder pages.

The server's authenticated `/semantic/status` and workspace `/access` capabilities gate the
menu and routes. Role policy remains on the server. API requests reuse the host `http` client
and pin their captured workspace in an Axios request transform, after the global interceptor.
Resource IDs remain strings. Draft concurrency counters come only from server responses.

The editor protects route departure, browser unload, and the host's workspace switch via a
removable store guard. Request abort and generation checks prevent old workspace results from
updating current state. A 409 keeps user input. Any edit invalidates the validation report.
Publication requires a saved, freshly validated draft and a nonempty note. An uncertain
publication keeps its original operation ID/payload and blocks mutations until recovery;
a definitive publication 4xx releases the pending operation so corrected drafts can retry.

Tests use Vitest, Vue `createApp`, happy-dom, Element Plus, and the existing dependencies.
No new framework or HTTP client is installed. Theme surfaces use existing `--mc-*`/`--el-*`
tokens and inherit classic/enterprise plus light/dark profiles.
