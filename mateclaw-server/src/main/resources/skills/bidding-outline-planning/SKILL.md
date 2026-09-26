---
name: bidding-outline-planning
description: Create a traceable technical response outline from a confirmed bidding analysis baseline.
---

# Bidding technical outline planning

Build a chapter outline from the supplied frozen baseline and the readable material snapshots in `materials.items`. Cover every mandatory outline item and TECHNICAL requirement in one or more leaf chapters. Reference only IDs supplied in the input. Commercial requirements belong in `unmappedItems` as follow-up work; do not turn them into technical commitments. Mapping gaps may be reported in an incomplete candidate; a human approver will block confirmation until coverage is complete.

Return only JSON matching this schema:

```json
{"schemaVersion":"1","chapters":[{"id":"chapter-1","parentId":null,"order":0,"title":"Technical response","instructions":"Describe the response evidence and acceptance approach.","mandatoryOutlineRefs":[],"requirementRefs":[],"scoringRefs":[],"materialRefs":[]}],"unmappedItems":[],"warnings":[]}
```

Chapter IDs must be unique. Children reference an existing parent. Use nonnegative sibling order values without duplicates. Keep the outline at six levels or fewer and 300 chapters or fewer. Map requirements and mandatory items to leaf chapters. Do not emit approval, decision, or status fields.
