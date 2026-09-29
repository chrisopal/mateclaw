# MateClaw 项目开发与审核约束

## 默认适用于全部后续开发

本节根据项目负责人 2026-09-29 的要求设定，覆盖新增功能、修复、重构、文档、配置、数据库迁移及依赖变更；不是仅在架构整改任务中启用。既有用户指令与更具体目录约束同时遵守。

1. 开始前阅读 `docs/architecture-quality-v1/README.md`、`RULES.md`、`REVIEW_CHECKLIST.md` 及相关设计；记录当前 HEAD/base 和工作树状态，执行 dev 检查。
2. 方案和实现必须交代职责边界、公共能力复用、权限/来源/事务/兼容影响；每个职责切片后重跑 dev 和适用回归。清理重构先写计划，缺少行为保护时先补刻画测试。
3. 提交前执行统一 commit 门禁并核对真实 tree；未完整暂存或有未跟踪输入时不得假称通过，不自动清理或覆盖其他工作。
4. 每次交付/PR 按 `.github/pull_request_template.md` 提供检查证据、未测项、风险和回退；审阅人按 `REVIEW_CHECKLIST.md` 核查。没有 PR 的本地交付也遵循同样证据标准。
5. 允许按实际演进检查项，但必须记录原因、影响范围、正反例/验证结果、存量与新增的区别，并更新规则、脚本、测试、台账和审核模板中受影响的内容。门禁/授权/迁移/控制面调整由独立审阅人审核，不能以让失败变绿为理由降低规则或删除断言。
6. 文档等不适用检查应由固定影响规则判为 NOT_APPLICABLE 并说明；desktop/webchat 等未映射组件必须补正式测试适配后再交付，不能静默当作已测。SCAN_PASS、工程通过、业务验收、远端强制生效分别报告。
7. 不默认提交、推送、部署、改分支保护或生产数据；这类动作遵循用户实际授权。当前安装并不代表远端 required CI 已启用。

<!-- MATECLAW-ENGINEERING-GATE:START -->
## MateClaw mandatory engineering gate

Before editing code, read `docs/architecture-quality-v1/RULES.md`, the relevant spec,
and `.agents/skills/mateclaw-engineering-gate/SKILL.md`. Preserve all existing
repository and directory-specific instructions. This section grants no permission
to commit, push, change production data, or change repository administration.

1. Record the actual HEAD/base and dirty worktree; run
   `python3 -B scripts/quality/verify.py --mode dev --base origin/dev` before edits
   and after each coherent change. Missing base/tool is BLOCKED, never PASS.
2. Keep runtime → public interface → business adapter dependency direction.
   No Controller SQL/DAO dependency, business-specific core hooks, cross-feature
   private helper imports, widened authority, mutable published artifacts, or
   edits to existing Flyway migrations.
3. Keep IDs as strings, typed stable DTOs, existing i18n/theme/Workspace behavior,
   source authorization, exact revisions, cancellation and approval semantics.
4. Before any authorized commit, review and stage only intended files; run
   `python3 -B scripts/quality/verify.py --mode commit`. Require exit 0 AND
   `submission_ready=true` for the exact staged tree. Partial staging is rejected;
   do not stash/reset other people's changes to make it pass.
5. Never use --no-verify, skip tests, remove assertions, rewrite the baseline,
   weaken checks, --fix inside check commands, or claim unrun checks passed.
6. Changes to gates/rules/hooks/workflows/tests/tool versions require explicit
   control-plane review. Self-written PASS reports are not merge authority.
7. Report actual commands, logs, task ID, checked tree, limitations and unverified
   environments. SCAN_PASS is not permission to submit.
<!-- MATECLAW-ENGINEERING-GATE:END -->

## Architecture quality task entry

Read `docs/architecture-quality-v1/README.md` and `IMPLEMENTATION_DESIGN.md` in that directory before architecture work. Use `REVIEW_CHECKLIST.md` to map findings to AQ tasks and AC evidence. The imported CODEX_START text is a sample workflow, not authorization to start business refactors. Current design decisions remain Proposed until stakeholder review.
