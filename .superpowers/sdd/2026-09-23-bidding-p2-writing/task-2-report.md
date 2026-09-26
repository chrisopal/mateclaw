# Task 2 report — P2 outline

Status: DONE
Verification: PASS
Plan deviations: none

## Delivered behavior

- Added the outline planning skill with `SKILL.md`, `input.schema.json`, and `output.schema.json`. The immutable package carries all three files into `ProjectExecutionOptions`; the employee runtime reads the pinned output schema for structured output, and the result handler enforces the outline fields and business gates.
- Added scoped outline read, dispatch, save, and confirm flows. The empty edit token is server-issued and scoped to workspace/project. Human saves advance the edit head; employee results remain append-only candidates. First employee candidates can be confirmed against the empty token without an earlier human save.
- Outline comparison reads use a separate historical-origin authorization traversal so a still-readable confirmed outline remains available for comparison after a referenced source set head advances. Existing P1 `validateForRead` continues to enforce current selection for execution and other read paths.
- The candidate gate permits incomplete mandatory/technical coverage; confirmation requires every mandatory outline item and every TECHNICAL requirement to be mapped onto leaf chapters. Commercial requirements remain follow-up items. Unknown fields (including model approval/status fields), invalid references, duplicate IDs/orders, cycles, excessive depth, and oversized trees are rejected.
- The baseline projection reads the four stable P1 skill results. Mandatory IDs use their ordered index and canonical-content SHA-256. Outline dispatch resolves the writer's numeric pinned skill ID and enqueues the exact immutable skill package.
- Dispatch and save freeze selected readable material references through `BiddingMaterials.snapshot` for the actual assigned writer. Task input uses `materials: { items: [...] }`, with each item carrying its fixed material `ref`, snapshotted `content`, `source`, and `validity`. Baseline and materials are also fixed task dependencies and are recursively revalidated.
- `CONFIRM_ANALYSIS` only derives outline dispatch from explicit `autoPlanOutline: true`; the decision records that intent, dispatch is idempotent, and missing writer/skill configuration is visible through `GET /projects/{id}/outline` as `dispatchTodo`.

## Response shapes

Empty `GET /projects/{id}/outline`:

```json
{"baselineRef":null,"editExpectedRef":{"kind":"outline","id":"current","version":0,"digest":"<server-issued-scoped-digest>"},"candidates":[]}
```

With candidates, the response includes each readable candidate as `{ref,status,payload,inputRefs}`; the human edit token advances only after a human save. After confirmation, `confirmed` has the same envelope shape and status `CONFIRMED`, while the exact selected ref is the edit head. Candidate status stays `CANDIDATE` until confirmed.

For an opted-in analysis confirmation with missing writer/skill configuration, `GET /projects/{id}/outline` additionally contains:

```json
{"dispatchTodo":{"reasonCode":"OUTLINE_CONFIGURATION_REQUIRED","decisionId":"<persisted-decision-id>","status":"TODO","baselineRef":{"kind":"analysisBaseline","id":"current","version":1,"digest":"<digest>"},"action":"DISPATCH_OUTLINE"}}
```

## Verification

Ran with Java 21:

```text
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -pl mateclaw-server -am -Dtest='BiddingOutlineTest,BiddingOutlineValidatorTest,BiddingAnalysisTest,BiddingEmployeeBindingsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
BUILD SUCCESS — Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

The suite covers HTTP read scoping, empty-head CAS, baseline gating, incomplete candidate versus complete confirmation, first employee candidate confirmation, persisted opt-in todo, numeric pinned skill dispatch and frozen task input, analysis idempotency, and employee binding regression. Both JSON schemas parse successfully and `git diff --check` is clean.

## Sol review round 1

Addressed all four requested items:

- `dispatch` now uses a read/write transaction; the service lifecycle test reads back both the queued task and its persisted operation row.
- Legacy free-text and null `CONFIRM_ANALYSIS.reason` values are treated as no auto-dispatch intent. An HTTP regression verifies both return 200 without a `dispatchTodo`.
- Empty chapter lists are rejected in the JSON Schema and server validator before save/confirm.
- Save checks the nested payload is an object before casting. The server validator now enforces required root/chapter fields and their JSON types, including nullable-string `parentId`, integer `order`, textual `title`/`instructions`, and string-array references. HTTP tests verify missing payload, empty chapters, and malformed chapter fields return 422 and do not advance the edit head.

Round 1 used the same Java 21 command above and passed 24 tests with no failures or errors. Test authentication/model values are synthetic fixture values; no production secret or signing key was added or changed. Existing test-default JWT/crypto warnings remain fixture/runtime warnings, not production-key evidence.

Limit: this Task 2 slice verifies a real queued task and persisted input with a numeric pinned skill, but does not claim an actual model-generated outline or Task 3 writing delivery.
