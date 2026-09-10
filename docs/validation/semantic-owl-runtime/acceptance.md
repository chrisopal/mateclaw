# Isolated OWL runtime verification (2026-09-09)

Genuine Spring Boot application, empty dedicated H2 file `/tmp/mateclaw-owl-runtime/database`; API 18109, enterprise Vite UI 5189. Existing user databases untouched. No configured model providers and extraction disabled; no real external model calls.

Browser used normal login form and real authentication. Created `OWL runtime browser QA`, edited Functional Syntax to four OWL axioms, saved draft version 2, validated, published v1 with note, and reached immutable version screen showing four parsed axioms and canonical document digest. Both export buttons clicked. Screenshot `browser-published.png`; API independently downloaded/read actual `published.ofn` and `published.rdf`.

API script verifies new graph binding to the browser-published OWL revision, entity IRI/asserted OWL type, decimal DataPropertyAssertion proposal and subsequent database-backed GET. A proposal is not claimed as accepted/trusted fact. M7 exact source binding and review creation verified; detailed snapshots route currently absent from existing packaged jar despite being present in source, pending refreshed package.

Artifact `api-readback.json` contains IDs, document, graph/entity/statement and persisted GET evidence. Script creates disposable synthetic KB/graph fixtures on each run. Runtime is local verification, not production deployment or complete OWL certification.

Remaining acceptance: final packaged build restart; M7 detailed snapshot + decision/change review; post-restart readback. Current success is scoped to the existing jar and current UI source.

Authenticated graph UI also verified typed entity IRI and asserted OWL type, exact decimal DataPropertyAssertion in knowledge records (PROPOSED), and empty trusted view for evidence-free unaccepted proposal. Screenshot `browser-assertion.png`.
