# Task 2 review 1

Reviewer: GPT-6 Sol, p3_export_preflight. Range: 7c6cc5cf..c92a2d5a.
Spec: FAIL. Quality: REQUEST CHANGES.

Root verification: 178 tests, zero failures/errors/skips. Initial source ownership passed for 7c6cc5cf..33839d95; after controller-only migration ownership declaration 367010eb, fix ownership and full tests passed for 367010eb..c92a2d5a. No live model, Office pagination or MySQL/Kingbase execution claimed.

- HIGH: ArtifactService.verify passes native manuscript to collectExpectedText, which never traverses chapters/chapter/blocks; expected text is empty. Substring presence also cannot verify ordered chapters, text multiplicity or table cell structure. Verify the exact controlled render DTO against ordered POI body elements and add negative read-back tests.
- HIGH: Renderer.configureStyles is empty. Heading1/2/3 references alone do not define styles with outline levels, so heading hierarchy and TOC membership are not established. Define paragraph styles with outline levels 0–2 and font settings; inspect actual style definitions/linkage.
- MEDIUM: First template read lacks a fixed Ref; dispatch silently creates/substitutes missing refs. Establish authorized materialization and pre-dispatch discovery with explicit exact refs.

Controller clarification 0e81d3df: PREPARE_EXPORT is an authorized CAS/idempotent write; project-scoped template GET is read-only and exposes prepared refs/status. Dispatch requires all exact refs. Luna fix round 1 starts at 0e81d3df; Task3 remains pending.
