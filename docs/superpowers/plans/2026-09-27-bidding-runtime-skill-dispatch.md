# P2 live runtime skill-dispatch repair

## Global Constraints

Independent bidding remains a MateClaw module with no bid-agent dependency. Preserve active-claim, actor, model/config pin, fixed package, tool allowlist and successful contract-read receipt checks. Never loosen JSON validation, auto-adopt, or mutate frozen retry inputs. The runtime must provide its trusted package identity instead of asking the model to discover or guess it.

### Task 1: Make the fixed skill identity available at the actual model request

**Files:**
- Create: `docs/superpowers/plans/2026-09-27-bidding-runtime-skill-dispatch.md`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeRuntimeTest.java`

**Interfaces:**
- Consumes: immutable Claim.skill manifest, fixed input and references; existing ProjectExecutionOptions.
- Produces: server-owned execution metadata in the actual model user request: exact skillName/skillId, load_skill arguments, readSkillFile arguments for output.schema.json and explicit contract JSON requirement.
- Preserve the task snapshot and digest, authorization and tool-read receipts. Make shared scoped system text domain-neutral while retaining read-only scope and no approval/publication constraints.
- Regression captures the actual production-graph ChatModel prompt, rather than testing an unused template. Run focused red test before implementation, then BiddingEmployeeRuntimeTest, BiddingTaskTest and available presales runtime regressions.
- Read-back/retry the existing failed synthetic outline task after reviewed deployment. Record actual model success/failure separately from engineering gates.

### Task 2: Validate the actual P2 fixed output schemas

**Files:**
- Modify: `docs/superpowers/plans/2026-09-27-bidding-runtime-skill-dispatch.md`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeRuntimeTest.java`

**Interfaces:**
- Support the local $defs/$ref, exactly-one oneOf and uniqueItems constructs present in the approved outline/writing schema files, preserving all current strict checks.
- External, missing and cyclic references, unknown keywords, invalid variants and duplicates remain rejected. Bounded recursion prevents cyclic schemas from exhausting the runtime.
- Red/green regressions use both bundled output schemas and malformed variants. No schema-file edits, dependencies, receipt changes or frozen-input changes.
