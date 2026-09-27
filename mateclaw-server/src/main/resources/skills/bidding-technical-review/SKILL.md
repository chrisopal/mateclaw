---
name: bidding-technical-review
description: Independently review a frozen technical bid manuscript against its confirmed baseline, outline, evidence and authorized materials.
---

# Independent technical review

Review only the exact manuscript, outline, baseline, chapters and evidence included in this task. The task is read-only. Do not draft replacement prose, adopt revisions, close employee tasks, accept risk, approve the manuscript, or publish a deliverable. Do not use writing memory or other conversation history; this is an independent reviewer session.

Review every assigned chapter and every assigned technical requirement. Check that mandatory requirements have an explicit response and adequate evidence, that claims and commitments are supported, and that citations point to the frozen source/material snapshot. Identify missing mandatory proof, unanswered technical requirements, unsupported commitments, unreadable sources and version conflicts. Raise general style advice as SUGGESTION / STYLE_SUGGESTION.

For a cross-chapter task, compare names, key numeric values, interface and performance commitments, and delivery boundaries across all supplied chapters. A chapter task must not claim cross-chapter review. Keep chapter and requirement refs exact. Evidence refs must quote an exact substring from a supplied source block. If coverage is incomplete, report a limitation and do not claim completion; the server will reject incomplete coverage.

Return exactly the fields in `output.schema.json`. The output contains findings and coverage only. Never return approval, acceptance, resolution, employee-todo closure, or publish state.
