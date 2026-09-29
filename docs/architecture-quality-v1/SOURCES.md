# 核验来源与性质

仓库代码引用均固定为 `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，与2026-09-29本次分支读取一致。实现前仍需刷新核验。上一轮审查文件为本次提供的 `MateClaw_Module_Architecture_Style_Audit_2026-09-29.md`；其推断与未测项不被当作已复现故障。

| ID | 来源/路径 | 用途 |
|---|---|---|
| R1 | GitHub `repos/chrisopal/mateclaw/branches/dev` | 当前提交与可见分支保护状态 |
| R2 | `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`；`agent/execution/ProjectToolPolicy.java`；`bidding/BiddingEmployeeRuntime.java` | 具体售前依赖与已有通用执行接口 |
| R3 | `mateclaw-ui/src/features/{presales,bidding}/api/*Api.ts`；`features/semantic/api/ontologyApi.ts` | scopedConfig跨域归属与请求契约 |
| R4 | `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java` | Controller任务读取中的数据库和授权编排 |
| R5 | `presales/PresalesAccess.java`、`bidding/BiddingAccess.java`、`wiki/service/WikiKnowledgeBaseService.java`、`presales/PresalesContextProvider.java` | 主体、角色和资料路径 |
| R6 | 根 `pom.xml` | Java21、现有多模块与ArchUnit版本 |
| R7 | `mateclaw-ui/package.json`、`eslint.config.mjs`、`vitest.config.ts` | 既有真实构建/测试/规则，不据旧README猜测 |
| R8 | `scripts/check-snowflake-precision.sh` | 已存在的数据库ID字符串检查；本包继续调用 |
| R9 | GitHub `repos/chrisopal/mateclaw/rulesets`；固定提交`.github/workflows`读取 | 本次可见规则集为空；workflows路径返回404，不推断所有外部CI |

仓库路径可在以下固定前缀查看：
`https://github.com/chrisopal/mateclaw/blob/ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93/`

| ID | 官方文档 | 用途 |
|---|---|---|
| E1 | https://git-scm.com/docs/githooks | hooksPath、可执行位、pre-commit绕过与pre-push输入 |
| E2 | https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches | required checks、管理员及绕过政策、最新base |
| E3 | https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners | Code Owner审查、base版本和控制面保护 |
| E4 | https://www.archunit.org/userguide/html/000_Index.html | 编译产物依赖检查，不替代运行时安全测试 |
| E5 | https://developers.openai.com/codex/guides/agents-md | 仓库层级Agent指令；入口可能重定向至官方Learn |
| E6 | https://developers.openai.com/codex/skills | Skill指令与工程脚本边界；入口可能重定向至官方Learn |

Actions的固定提交已通过GitHub对应tag ref核验：checkout v4.2.2 `11bd71901bbe5b1630ceea73d27597364c9af683`；setup-java v4.7.1 `c5195efecf7bdfc987ee8bae7a71cb8b11521c00`；setup-node v4.4.0 `49933ea5288caeca8642d1e84afbd3f7d6820020`；upload-artifact v4.6.2 `ea165f8d65b6e75b540449e92b4886f43607fa02`。这是可重现固定，不是“最新安全版本”声明；启用前按组织政策复核。

本包所有工具阈值、整改包名和任务号为拟定设计。没有声称仓库已有同名服务、上述formatter已安装、分支保护已启用或应用测试已通过。
