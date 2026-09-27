Status: DONE
Verification: PASS
Plan deviations: none

# Task 5 engineering evidence

The controlled full-application test exercises a fresh authenticated workspace through source confirmation, four analysis packages, rejected cross-project evidence plus a valid retry, analysis confirmation, outline, chapter writing/adoption, manuscript assembly, independent review, finding disposition, targeted revision/adoption, a new manuscript and review, controlled export, approval context, approval, and candidate/formal byte downloads. Only the provider is faked; HTTP authentication, database writes, task claims, pinned tool callbacks, validators, DOCX generation, approval, and download paths are real. This verifies engineering behavior, not production model quality or procurement accuracy.

The test writes a sanitized runtime record to `mateclaw-server/target/bidding/task5-run-evidence.json`. It records persisted task/attempt/package rows, role and numeric skill IDs, package versions/digests, observed `load_skill` and `readSkillFile` callback responses per attempt, durable statuses, revision IDs, and artifact download/read-back evidence. It excludes prompts, response bodies, credentials, and tokens. The final run recorded 12 tasks, 13 attempts, and 20 persisted revisions. Candidate and formal downloads both returned HTTP 200 and matched stored artifact bytes and digest. Candidate: `mateclaw-server/target/bidding/task5-candidate.docx`, 3,618 bytes, SHA-256 `15cba78cd59f89bd2351ad0654b4a0bc82e687dfe5f7b039c7c5f4ebb10b0a32`.

## Verification performed

- Final scoped backend gate passed **443 tests, 0 failures, 0 errors, 0 skipped** across `Bidding*Test,BundledSkillSyncerTest,PresalesIntegrationTest,PresalesEmployeeRuntimeTest,ToolRegistryProxyTest,ToolExecutionExecutor*Test,ToolResultStorage*Test,ConversationWindowManager*Test,RestrictedProjectObservationTest`. Command: `env JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -q -pl mateclaw-server -am -Dtest='Bidding*Test,BundledSkillSyncerTest,PresalesIntegrationTest,PresalesEmployeeRuntimeTest,ToolRegistryProxyTest,ToolExecutionExecutor*Test,ToolResultStorage*Test,ConversationWindowManager*Test,RestrictedProjectObservationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full -Dmateclaw.skill.workspace.root=/tmp/mateclaw-task5-gate-skill-root test`.
- The end-to-end suite includes the stage-skip negative case and the full positive HTTP-controlled-provider chain above. The source out-of-scope rejection is asserted as `EVIDENCE_OUT_OF_SCOPE`, then the retry succeeds; every attempt observes its actually pinned `SKILL.md` and output schema through runtime callbacks.
- DOCX tests verify cached TOC ordering, duplicate headings, heading levels, bookmark uniqueness, internal hyperlink targets, strict ordered body content, table structure, and image authorization/format/size behavior. Tampered TOC labels/anchors are rejected, and mandatory directory page-number requirements fail closed as `EXPORT_FORMAT_UNSUPPORTED`.
- Image sizing semantic RED: the old 160 mm extent was 80,000 EMU versus the independent expected 5,760,000. The fixed 160 × 80 mm image is 5,760,000 × 2,880,000 EMU. `BiddingDocxRendererTest` passed 8 tests after the change.
- Root independently inspected the generated synthetic layout DOCX read-only in Office: 8 pages; the 160 × 80 mm image is visible at the expected 2:1 ratio with caption and no clipping; a 49-row table repeats its header across pages; heading keep-with-next, Chinese text, and page fields render. This is `PASS_SYNTHETIC_RENDERER_LAYOUT_ONLY`. Root also inspected the generated 3,618-byte candidate read-only: cached TOC directory, body, and footer are readable (`PASS_MINIMAL_CANDIDATE_LAYOUT_ONLY`). These are renderer/layout checks, not human review or real-tender acceptance.
- `git diff --check` passed.

## Acceptance limits still open

- The provider used in the engineering E2E is controlled and fake. The separate live-model run remains NOT_PASSED; consult the root-owned [acceptance record](../../../docs/bidding/acceptance/2026-09-23-full.md) for the observed outcomes.
- Approval inspection fields in the automated flow model an approver-submitted inspection record; they do not constitute human visual approval.
- The synthetic golden is not procurement-expert reviewed and is not a real-bid gold set. The controlled positive flow is not proof of scoring quality across all eight packages.
- Browser acceptance covers the recorded empty desktop dark and narrow light states only; populated review/export interaction remains NOT_RUN.
- MySQL evidence is limited to isolated migration/schema and storage/BLOB backup-restore probes recorded by root. Full workflow on MySQL and Kingbase validation remain NOT_RUN.
- Root ownership gate and independent scoped review are pending this commit.
