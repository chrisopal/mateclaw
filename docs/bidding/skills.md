# 固定技术标技能包

技术标工作流固定使用下表八个技能包。数据库中的 `skillId`、`version`、包摘要、任务 attempt 和实际加载记录是项目级证据，应从项目只读任务与 attempt 查询导出；不能用本表的名称或“配置成功”代替运行时加载证明。

| Skill name | Role | Contract | Runtime tools |
| --- | --- | --- | --- |
| `bidding-tender-profile` | analyst | `output.schema.json`, schemaVersion `1` | `bidding_read_sources`, `bidding_read_source` |
| `bidding-elimination-analysis` | analyst | `output.schema.json`, schemaVersion `1` | `bidding_read_sources`, `bidding_read_source` |
| `bidding-requirement-analysis` | analyst | `output.schema.json`, schemaVersion `1` | `bidding_read_sources`, `bidding_read_source` |
| `bidding-scoring-analysis` | analyst | `output.schema.json`, schemaVersion `1` | `bidding_read_sources`, `bidding_read_source` |
| `bidding-outline-planning` | writer | `output.schema.json`, schemaVersion `1` | no source tools |
| `bidding-technical-writing` | writer | `output.schema.json`, schemaVersion `1` | authorized source/material tools only |
| `bidding-technical-review` | reviewer | `output.schema.json`, schemaVersion `1` | read-only evidence tools |
| `bidding-document-export` | writer/export | `output.schema.json`, schemaVersion `1` | `bidding_export_document` |

The authoritative bundled contracts are in `mateclaw-server/src/main/resources/skills/<name>/`. A dispatched task pins the exact files and digest. Never update a task's schema by reading a later mutable package; new work must be dispatched from the current approved package and old attempts remain auditable.

## Role boundary

The analyst extracts source-backed facts and candidate requirements. An authorized project approver confirms the analysis and outline. The writer receives only those frozen references and its assigned chapter. The reviewer receives an independent read-only claim with chapter and whole-book coverage obligations; reviewer and writer must be distinct employees. Only a real project approver can classify technical impact, resolve mandatory human follow-up, approve exact candidate bytes, or publish a formal artifact.

## Evidence manifest

For each acceptance run record one row per attempted package: role, skillId, package version, pinned digest, schemaVersion, taskId, attemptId, result status, `project_skill_loaded` digest, and whether the relevant restricted reader/export callback actually ran. A persisted employee binding or readiness response proves configuration only. It does not prove a package was loaded or used.

The synthetic fixture oracle in `mateclaw-server/src/test/resources/bidding/golden/technical-tender.json` is independently authored but not reviewed by a procurement expert. It is a deterministic engineering check, not a human-annotated gold set and not evidence about any real tender.

## Controlled runtime proof

Run the full application test with an isolated skill workspace so bundle synchronization cannot alter an operator's installed skill directory:

```sh
env JAVA_HOME=/path/to/temurin-21 mvn -q -pl mateclaw-server -am \
  -Dtest=BiddingEndToEndTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.compiler.proc=full \
  -Dmateclaw.skill.workspace.root=/tmp/mateclaw-bidding-e2e-skills test
```

The test also assigns a unique temporary skill root to its full-application Spring context. After a passing run, `mateclaw-server/target/bidding/task5-run-evidence.json` records the actual task/attempt/package rows, observed pinned `SKILL.md` and `output.schema.json` callback responses, durable revisions, and candidate/formal download equality. It contains identifiers and digests for engineering audit; it is generated build evidence, not a substitute for retaining the corresponding database and attempt history. The fake provider makes this a deterministic runtime-path proof only; it does not establish live model quality or human review.
