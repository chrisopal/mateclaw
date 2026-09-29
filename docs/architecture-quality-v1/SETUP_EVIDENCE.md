# 项目检查接入与当前基线证据

日期：2026-09-29。本节记录首次接入时的原始快照；提交准备的修复见后文，不将初次失败冒充最终结果。

## 范围与身份

- 本地分支 dev；HEAD 与本地 origin/dev：`ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。没有 fetch、提交、推送、部署或更改 GitHub 管理设置。
- 初始 tracked 文件无修改；已有未跟踪 .omx、浏览器记录、规范包、docs、output、tools 等均保留，没有自动暂存、stash、reset 或批量忽略。
- 原始包 MANIFEST 的 43 个文件 SHA-256 一致；安装器先 dry-run，再无冲突复制 overlay/docs/integration。原始输入包没有修改。
- 门禁脚本沿用原包，不改阈值或扫描排除项。原始说明保留；新入口/设计/台账明确原文是规范来源，并非额外业务实施授权。

## 实际接入

| 项目 | 当前状态 |
|---|---|
| 根 AGENTS | 新建全项目开发/审核约束、统一命令和设计入口；落实用户“后续默认使用，可按实际调整但必须审核”的要求 |
| 项目 Skill | `.agents/skills/mateclaw-engineering-gate/SKILL.md`，默认适用所有开发，不仅架构专项 |
| 检查代码 | `scripts/quality/{gate,verify,pre_push,install_hooks}.py` 与 67 项自测 |
| 规则配置 | `.quality/policy.json` + `.quality/prettier.json`；初始 zero_tolerance 为空，仅禁止存量新增 |
| 本地钩子 | `.githooks/pre-commit` / pre-push，已设 executable；`core.hooksPath=.githooks` 写入 `.git/config.worktree` |
| worktree 隔离 | 仓库原本已启用 extensions.worktreeConfig；另一个 `.worktrees/presales-v1` 的 hooksPath 仍未设置，没有改共享配置 |
| Java formatter | 根 POM 合入 Spotless 3.5.0 / google-java-format 1.22.0 AOSP；由 verify 显式传入 immutable quality.base；3.5.0 已在 Git worktree 中实跑通过 |
| UI formatter | Prettier 3.6.2 开发依赖；新增非修复 lint:check 与 typecheck，原有 build/test/lint 保留 |
| 工具版本 | Java 21.0.7；Node 22.22.2；pnpm 12.4.2；CI 对齐 Node/pnpm 精确版本 |
| 锁文件 | 使用 pnpm 生成并 frozen-lockfile 安装通过；642 个已有 packages 和 snapshots 对象逐项一致，仅应用依赖图新增 Prettier。pnpm 12 同时新增包管理器自身锁定段 |
| 远端配置文件 | engineering-gate workflow（全部 PR + merge_group）、CODEOWNERS、PR 模板已写入；未在 GitHub 启用/验收 |
| 架构字节码检查 | ArchUnit 仅保留 integration 模板；AQ-01/03 清零后接入，不虚称已执行 |

包中 pnpm 10.10.0 / Node 22.16.0 是候选提案。实际 node_modules 标明 pnpm 12.4.2，10.10 会丢失既有 lock 的 libc 元数据，因此采用本机已有 12.4.2 版本，保留所有旧依赖信息，重新生成包管理器锁定段并完成 frozen 安装。此调整不升级应用依赖。第一次从仓库根用 `pnpm --dir` 时 Corepack 按根环境解析为 12.6.0，版本检查失败；正式命令改在 mateclaw-ui 目录执行，最终固定为 12.4.2。

## 实跑结果

原始日志在 `/tmp/mateclaw-aq-toolchains-20260929/`；日志摘要、SHA-256 和命令持久保存于 [evidence/2026-09-29](evidence/2026-09-29)。这些是本机证据，不能代替远端 CI 回执。

| 命令/检查 | 结果 | 证据与界限 |
|---|---|---|
| `python3 -B scripts/quality/verify.py --mode dev --base origin/dev` | SCAN_PASS | 67 自测通过；0 新违规；2,921 存量命中；不是完整工程 PASS |
| `python3 -B scripts/quality/verify.py --mode commit` | BLOCKED，exit 2 | PARTIAL_STAGING；如实拒绝当前未完整暂存工作区，submission_ready=false |
| 直接执行 `.githooks/pre-commit` | BLOCKED，exit 2 | 实际钩子同样拒绝部分暂存；未执行 git commit |
| `pnpm install --frozen-lockfile`（UI 目录） | PASS | 仅新增 Prettier；未使用跳过检查的安装参数 |
| `mvn -B -Dquality.base=<base SHA> spotless:check` | PASS | 增量 Java 格式；没有 Java 源码变更，不代表全仓已格式化 |
| `pnpm exec prettier --check --config ../.quality/prettier.json package.json` | PASS | 本次唯一 formatter 适用的 UI 修改文件 |
| `mvn -B -Dmaven.compiler.proc=full -Dmateclaw.skill.workspace.root=<临时目录> clean verify` | FAIL，exit 1 | XML 汇总 5,873 tests，21 failures，0 errors，70 skipped；实际执行 5,803，成功 5,782；server 失败后后续 reactor 模块未验证 |
| `pnpm exec vue-tsc --noEmit` | PASS | 静态类型，不替代运行时 JSON 验证 |
| `bash scripts/check-snowflake-precision.sh` | PASS | 真实现有脚本 |
| `pnpm exec vitest run --reporter=json --outputFile=<report>` | FAIL，exit 1 | 703 tests，702 passed，1 failed，0 pending |
| `node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts` | PASS | 4 tests，0 skipped |
| `pnpm build --mode enterprise` / `--mode classic` | PASS / PASS | 两个 mode 实际构建；保留 chunk 体积 warning，不代表真实浏览器主题验收 |
| ESLint 修改文件检查 | NOT_APPLICABLE | 本轮没有适用的 UI 源码 lint target；不是全仓 ESLint PASS |
| Python AST、JSON、POM XML | PASS | 配置与脚本语法可读 |
| YAML workflow 解析与 required job 断言 | PASS | 事件包括所有 PR/merge_group，always 汇总且只接受 success；不是远端运行 |
| `git diff --check` | PASS | whitespace 检查 |

完整检查模式因工作区不是精确暂存树而先阻断。工具链结果是在同一源码工作区独立调用 wrapper 的固定命令取得，没有冒充 commit/CI PASS；使用清理过的测试环境和临时 Skill 目录。不得因为单独命令通过而设置 submission_ready=true。

最终 dev 报告输出位置：`/tmp/mateclaw-aq-final-20260929/report.json`（最终交付前重跑；未写入项目避免报告自变更循环）。

## 首次接入时的失败与后续问题

| 问题 | 观察 | 处理入口 |
|---|---|---|
| AQ-BASE-01 | SemanticContextReasoningIntegrationTest 的 20 个用例及 SemanticReasoningSnapshotGuardTest 的 1 个用例失败。发布返回 LOGIC_CHECK_ERROR：ReasoningResult 为 null，预期 200 实际 409 | 单独定位 fixture/推理契约与发布校验；保留断言；修复后重跑失败组及相关语义回归，随后完整 gate |
| AQ-BASE-02 | ontologyManagement.test.ts:214，viewer 修订比较用例期待 ADDED，实际渲染文本不包含 | 单独排查异步渲染/fixture/差异状态；不得删断言或改成宽松包含 |
| AQ-SETUP-01 | 当前工作区存在未暂存改动和用户未跟踪输入，完整 commit 模式拒绝 | 后续获授权提交时组织独立干净工作区与明确文件范围；不清理用户材料凑通过 |
| AQ-SETUP-02 | trusted base 尚未合入新 runner；远端 required check/审批保护没有本轮生效证据 | 维护人受控 bootstrap 与独立审核后，再按 CI_SETUP 实测故意违规 PR/新 push/拒绝绕过 |

本轮未修改相关业务源码与失败测试，应用旧依赖锁定内容也保持。但没有另建干净 base 做同环境重复运行，**不能据此断言两个失败的历史根因已经确证**。这里记录的是整改前当前代码实测问题，不能自行豁免。

## 提交准备补充

用户随后授权提交推送及启动业务重构。AQ-BASE-01 的两个测试夹具在发布前为 mock 推理端口提供有效临时结果，随后重置 mock，使各用例对真实场景调用的断言继续有效；定向语义用例 21/21 及相邻回归 20/20 通过。AQ-BASE-02 改为断言当前英文界面的实际本地化文案 `Added`，定向 Vitest 10/10 通过；没有删除用例或放宽包含条件。这些是提交候选修复，不回写上面的首次快照。

Spotless 2.43.0 在 `.git` 为文件的 Git worktree 中无法识别仓库。将 Maven 插件升至 3.5.0 后，使用 Java 21 在管理型 worktree 对改动的 Java 测试文件执行格式化并通过 `spotless:check`。本机 Maven 默认 Java 25 与 google-java-format 1.22.0 不兼容，提交检查固定 `JAVA_HOME` 为项目 Java 21。完整提交门禁及推送结果以当次外部报告和 Git 远端回读为准，不能从本段定向结果推导。

## 存量观察与未测范围

| 规则 | 当前存量词法命中 |
|---|---:|
| STYLE-001 | 2,008 |
| UI-001 | 825 |
| AR-002 | 37 |
| AR-005 | 17 |
| AR-004 | 14 |
| AR-001 | 9 |
| TS-001 | 7 |
| AR-003 | 4 |

总计 2,921；不同词法命中可能指向同一职责问题。此统计仅供实施排序，**不是门禁可加载的 baseline**；真正比较仍使用 Git 提交。

NOT_RUN：整改后的 AC-01–46 全量验收、真实 MySQL/Kingbase 迁移恢复、真实模型/客户样本质量、浏览器/Office 成稿验收、故意违规远端 PR、分支保护/独立审批签收。业务功能和 AQ-01–10 没有在本轮实施。不可将自测、H2 回归、构建、文档或配置存在性换算成业务完成率。

## 交付文件与简化

- 根 AGENTS、项目 Skill、PR 模板、CODEOWNERS：统一开发和审核入口，避免为不同 Agent 维护分叉规则。
- scripts/quality、.quality、.githooks、workflow：复用规范包现有实现，没有再建第二套检查器。
- 根 POM、UI package/lock：只接入检查所需 formatter、只读脚本与版本锁定，既有业务源码未重构。
- README、IMPLEMENTATION_DESIGN、REVIEW_CHECKLIST、acceptance-register 及本证据：形成需求→任务→检查→证据链。

剩余风险：首次全量失败需由提交门禁复验、词法扫描盲区、远端保护尚未启用、独立审核尚未签收、AQ-06 所需正式业务规格未找到。设计 ADR 均为 Proposed，不能由实现 Agent 自签 P0。
