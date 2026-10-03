---
name: mateclaw-engineering-gate
description: Default engineering and review workflow for all MateClaw development, documentation, configuration, dependencies and migrations. Use before edits, during implementation, before commit/push, and when reviewing architecture, permissions, migrations or quality-control changes. Requires real scripts and evidence; never replaces CI with self-review.
---

# MateClaw 工程门禁

## 使用步骤

先读取仓库根目录和目标子目录的 AGENTS 指令，以及
`docs/architecture-quality-v1/RULES.md`、`ARCHITECTURE_SPEC.md`、`CODEX_TASKS.md`。
确认本次任务及允许的文件范围。不要从聊天中的旧 HEAD 推断当前状态。

执行：

```bash
git status --short
git rev-parse HEAD
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

保留初始存量问题与新问题的区别。没有 origin/dev 时记录阻断，由操作者按既有流程准备基线。

写一个短实施计划：用例、依赖方向、数据权威、权限、兼容、事务边界、回归。复杂变更按
AQ-00→AQ-01→AQ-02→AQ-03 的优先级，不先扩大工作台功能。

实现每个职责切片后重跑 dev 检查。先补行为刻画测试，再搬移权限、执行策略或数据库逻辑。
格式化与行为修改分开。初始上下文、工具、历史和导出均复核权限，不只检查页面按钮。

Java Flyway 入口必须与 h2/mysql/kingbase 同名版本对应，并在
`.quality/frozen-migrations.json` 登记入口及共享算法/工厂源码 SHA-256。
既有入口、基线冻结源码及摘要不可删改；新语义使用新版本。初装与追加闭包必须独立核对
已发布源码、完整依赖、嵌套类型和编译/JDK/Jackson兼容。清单不自动发现依赖，不替代实际
Flyway checksum/validate、方言实测或远端 required CI；不得重写摘要绕过失败。

提交前检查 diff，仅暂存本次授权修改，执行：

```bash
python3 -B scripts/quality/verify.py --mode commit
```

读取真实 report.json。退出码非零、缺工具、全跳过/零测试、失败日志、检查后代码变更都阻止提交。
不改 skip 标记，不重新生成存量基线，不删除断言。没有 commit 权限时仅提供补丁和结果，不自行提交。
Java 门禁使用项目 JDK 21；macOS 未显式设置 `JAVA_HOME` 时，检查器会寻找已安装的
JDK 21 并验证 Maven 实际运行版本。找不到时按 `MAVEN_JDK21_REQUIRED` 阻断。

SSH 远端推送使用 `scripts/quality/push-checked.sh -u origin <branch>`；它保留正常
pre-push 钩子并设置连接保活。全量钩子可能运行数分钟，直接 `git push` 的空闲 SSH
连接曾在检查全绿后被远端关闭。若传输失败，先核对该次 push 报告的 target SHA、
base、`submission_ready` 与工作区，再重连；不得把传输失败记为远端已推送。

最终回报：任务 ID、实际 base/HEAD/tree、修改职责、命令及结果、日志路径、已执行测试、
NOT_RUN 项与风险。不得将此 Skill 已被加载当作实际代码检查证据。

## 安装边界

本 Skill 是指令，不是安全边界。根 AGENTS 引用它；Git hooks 与 CI 执行真实工具。
其他 Code Agent 若不支持 `.agents/skills`，仍必须通过自己的项目规则引用同一 RULES 和命令，
不可为每种 Agent 复制一份渐渐不同的规范。

## 全项目默认规则与调整

每次开发和审核默认使用，不以是否涉及本体/售前/投标为启用条件。按根 AGENTS 的全项目约束和 `docs/architecture-quality-v1/REVIEW_CHECKLIST.md` 执行；交付使用 `.github/pull_request_template.md` 的证据字段。允许根据实际修改检查，但必须说明原因、覆盖变化、正反例和验证结果，并经独立审核控制面变更；不得降低标准以掩盖失败。未映射模块必须补适配，不得假称已测试。
