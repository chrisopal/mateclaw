# W3C OWL 2 DL harness

The opt-in test class
`mateclaw-semantic-owl/src/test/java/vip/mate/semantic/owl/W3cOwl2DlHarnessTest.java`
executes the 266 cases selected by `case-index.json`. It loads the pinned
`all.rdf` metadata with a secure JDK XML parser, sends each unchanged premise
ontology through `OwlDocumentAdapter`, and then sends the selected ontology
ABox through `HermitReasoningWorker` with `ONTOLOGY_ABOX`. Consistency is
checked before entailment queries.

Run a bounded sample from the repository root with:

```text
python3 scripts/semantic-owl-w3c/run.py --limit 10
```

Run the standard selected suite with:

```text
python3 scripts/semantic-owl-w3c/run.py
```

The launcher validates both archive hashes and that selected index entries are
still `NOT_RUN`, then invokes Maven with
`-Dsemantic.owl.w3c=true`. Normal module test runs do not execute this suite.
The Java worker timeout defaults to 15 seconds per case, concurrency is fixed
at one, and the child JVM is bounded to 512 MiB unless the launcher options
are changed. The report is written to `results.json` only after the explicit
launcher run; it contains the archive hash, per-case `PASS`, `GAP`, or `FAIL`,
duration, and sanitized error detail without ontology source text.

Selected import cases use the exact UTF-8 artifacts in
`docs/validation/semantic-owl-execution/w3c-import-fixtures.json`. The
fixture archive hash, per-artifact SHA-256, metadata artifact IDs, and the
adapter's locked import closure are checked before execution. The harness
recursively assembles that closure from the archived artifacts, de-duplicates
cycles by ontology IRI, and gives the resulting `LockedImport` list to the
adapter and worker with no network fallback. Cyclic import graphs remain
bounded by the worker timeout.

`GAP` is reserved for an explicit current-contract boundary such as a missing
locked import artifact, parse/profile rejection, unsupported axiom, timeout,
or resource limit. An expected consistency or entailment result that does not
match is `FAIL`. The harness never turns an unsupported case into a pass and
never edits the pinned archive or case index.

When a conclusion contains several logical axioms, the harness decomposes it
only when no anonymous individual is shared between those axioms. A positive
query passes only when every exact worker query returns `ENTAILED`; a negative
query passes when at least one exact query returns `NOT_ENTAILED`. Worker
`UNSUPPORTED`, parse, timeout, and other non-boolean results remain `GAP` and
are never interpreted as false. A rooted, tree-shaped ABox conclusion with
shared anonymous nodes is normalized to one class assertion: all types on a
node become one intersection filler, and each outgoing edge becomes a nested
`ObjectSomeValuesFrom` restriction. This preserves the same witness and chain
scope under the OWL direct semantics. Cycles, multiple parent edges, named
object targets, disconnected anonymous components, and other axiom shapes are
explicit `GAP` boundaries; they are never split into independent queries. A
conclusion with no logical axioms is a positive pass only when every axiom is a
`Declaration` or `AnnotationAssertion`; negative or other empty queries remain
`GAP`.

The archived `WebOnt-I5.26-009` conclusion is typed by OWLAPI as one
`EquivalentClasses` expression with empty functional rendering because its RDF
graph points `owl:equivalentClass` back to the same restriction node. The
harness applies the typed rule generally: zero class expressions are an
explicit `GAP`, one expression is rendered as `SubClassOf(C C)`, and two or
more expressions remain `EquivalentClasses(...)`. The directed result and a
non-singleton equivalence counterexample are recorded in
`i526009-directed.json`. The completed 266-case report includes this narrow
normalization. Directed evidence for the two shared anonymous cases, including
a real-worker two-witness counterexample and a multiple-parent rejection, is
recorded in `tree-anonymous-directed.json`.

The W3C OWL 2 testing specification distinguishes normative syntax, imports,
species/profile identification, and semantic tests. This harness therefore
reports bounded adapter/worker evidence only; a green report is not a claim of
full OWL 2 conformance.

The completed standalone run is recorded in `results-standalone.json`; its
command, read-only classpath source, code/archive hashes, bounds, category
counts, and import/query fixture hashes are in
`results-standalone.manifest.json`. The remaining query boundaries and their
minimal reproductions are recorded in `query-diagnosis.json`.
