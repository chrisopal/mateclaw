# M6 领域本体建模师 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task-by-task; independently verify each boundary.

**Goal:** Built-in MateClaw digital employee uses a shipped skill and controlled ontology tools to generate, validate and deliver a persisted ontology version from domain materials.

**Architecture:** Skill owns modeling method; host adapter owns conversation identity, materials and employee provisioning; existing ontology application/core own draft CAS, validation and immutable publication. Reuse the existing publication UI as the explicit human authorization boundary, never treat model-supplied confirmation as authorization.

**Tech Stack:** Existing Java 21 / Spring / Vue / Element Plus; no new dependencies.

## Global Constraints
- Feature follows semantic.enabled; no autonomous publication or implicit graph rebinding.
- Workspace/actor comes from authenticated web origin, not model arguments. Validate active agent and current membership on every call.
- Source access must satisfy both user access and agent KB visibility. Model output and source text are untrusted.
- Generated definitions distinguish types from instances; no invented thresholds; uncertain business rules need expert confirmation.
- Built-in employee provisioning idempotent per workspace; preserve administrator edits and do not grant all KBs.
- Use existing draft and publish screens for review and authorization. Return canonical detail links and read back the published revision.

## Tasks
- [x] 1. Ship ontology-builder SKILL.md with trigger, business interview, exact schema, examples, source-citation rules, coverage checks, validation and publication workflow. Verify seed parser.
- [x] 2. Provision workspace employee and bind skill/tool through existing services, with idempotence tests and conditional feature registration.
- [x] 3. Add authenticated ontology tool adapter: list/read, create/update draft, validate, prepare publication link, read published revision. Reuse application transactions and CAS; test anonymous/cross-workspace/stale-draft rejection.
- [x] 4. Provide authorized source reading without requiring a preexisting graph. Preserve source references and business review report in the modeling deliverable; require missing references to be flagged by the skill (not a deterministic source validator).
- [x] 5. Integrate launch entry from ontology list using existing employee/chat UI; use existing publication dialog for human-confirmed publishing.
- [x] 6. Run skill, tools, provisioning and ontology regression checks, then a real materials -> agent -> draft -> validation -> user-confirmed version readback case. Record evidence and remaining environment limits.

## Acceptance
A domain expert can start the built-in employee, choose authorized materials and business questions, obtain a real draft with traceable modeling rationale, inspect the relationship map, run structural validation and publish through the authorized UI. The employee can read back and link the exact published revision. No claim of business correctness based only on structural validation. Existing graphs keep their binding.

## Verification completed 2026-09-08
See [acceptance evidence](../../validation/semantic-m6/acceptance.md). Local runtime updated; no remote deployment or Git commit is included. MySQL/Kingbase runtime validation remains outside the available local environment.
