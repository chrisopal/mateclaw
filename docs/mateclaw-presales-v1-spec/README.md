# MateClaw 售前解决方案工作台 V1.0｜实施规格包

- 规格日期：2026-09-18。
- 本轮优先级：**先做售前解决方案**；投标、合同、项目交付不在本轮实现。
- 目标仓库：`chrisopal/mateclaw`。
- 审阅分支：`codex/enterprise-semantic-core`。
- 本轮 GitHub MCP 查询到的提交：`d45d4abb64b46f248550e713ff77633f7c362bd6`。
- 交付状态：**设计与开发任务规格，不是应用源代码或已经部署的产品**。本轮未写入远端仓库、未运行 MateClaw 构建或业务验收。

## 产品目标

在现有 MateClaw 中新增售前工作台，让售前人员从客户材料出发，形成有出处、能澄清、可评审、能复用的方案。AI 结果落到业务对象，不只保留在聊天中。

`售前项目 → 资料 → Context 卡 → 需求/澄清 → G1 需求基线 → 能力/案例匹配 → 方案 → 独立评审 → G2 客户输出 → 冻结版本/交接包`

## 文件入口

| 文件 | 用途 |
|---|---|
| SPEC.md | 范围、用户流程、页面、对象、上下文、权限、接入与交付规则 |
| SKILLS.md | 主助手及独立评审的工作契约，8 个 Skill 规格 |
| CODEX_TASKS.md | 按依赖拆分的开发任务，每项含完成条件 |
| CODEX_START.md | 可交给 Codex 的启动任务说明 |
| ACCEPTANCE.md | 功能、安全、权限、恢复与回归验收场景 |
| SAMPLE_CASE.md | 完全虚构的制造业 MES 试点案例及验收预期 |
| examples/scenario-manifest.proposed.json | 拟新增场景包契约示例，不是现有 MateClaw 配置格式 |
| examples/handoff-package.proposed.json | 后续投标等模块可以接收的版本化交接示例，不会触发业务写入 |
| SOURCES.md | 代码审阅、既有业务规格和外部安全参考的来源与边界 |

## 使用顺序

先读 SPEC，再按 CODEX_TASKS 从 P0 开始。CODEX_START 可复制给 Codex。实施时重新读取分支 HEAD、实际代码和已有测试；本规格不是强制回退到审阅提交的指令。

既有 PI 售前方案规格用于复用业务流程、Skills 与 G1/G2 思路；**不照搬其 Node/Fastify/SQLite/PI 技术栈，不再建立第二套 Agent 运行时、身份系统或数据库**。

没有配置模型、没有证据、未经过确认、无权访问、任务中断等情形，都必须有真实状态。示例数据必须显式标识为演示，不可用于填充生产空白或伪装真实结果。
