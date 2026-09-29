# 来源与阅读边界

规格日期：2026-09-18。当前轮通过 GitHub MCP 查询分支并读取权限服务和 package.json；其余仓库文件承接同一提交在本对话前序的源码阅读。未本地克隆或运行 MateClaw。

## [R1] 分支基线（本轮重新查询）

`https://api.github.com/repos/chrisopal/mateclaw/branches?per_page=100`

仅证明查询时的分支提交，不证明任何构建/运行通过。

## [R2] SemanticContextDtos 与 SemanticContextService

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-server/src/main/java/vip/mate/semantic/query/SemanticContextService.java`

同一提交的源码阅读：版本绑定、时间过滤、事实/推理、覆盖与权限；不是全业务 Context 已实现的声明。

## [R3] SupportEvaluator

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-server/src/main/java/vip/mate/semantic/statement/SupportEvaluator.java`

WIKI_RAW 支持性判断。查询来源判断同样见 SemanticQueryService。

## [R4] SemanticAccessService（本轮重新读取）

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-server/src/main/java/vip/mate/semantic/security/SemanticAccessService.java`

现有 Workspace 角色、功能开关和系统管理员路径；不能据此宣称完整项目级 ACL 已存在。

## [R5] GraphApplicationService

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-server/src/main/java/vip/mate/semantic/graph/GraphApplicationService.java`

知识库与固定本体修订绑定、实体登记和并发控制。

## [R6] PluginContext

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/PluginContext.java`

AI 能力注册接口；不是完整业务模块生命周期接口。

## [R7] 前端 package.json（本轮重新读取）

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-ui/package.json`

实际脚本与依赖清单；build/lint 引用精度脚本，是否存在/通过需实施时核验。

## [R8] 现有前端路由

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-ui/src/router/index.ts`

semanticRoutes、Workspace/capability 门禁与主布局接入方式。

## [R9] 现有 WorkflowRunContext

`https://github.com/chrisopal/mateclaw/blob/d45d4abb64b46f248550e713ff77633f7c362bd6/mateclaw-server/src/main/java/vip/mate/workflow/runtime/WorkflowRunContext.java`

一次运行的输入/输出，不等于持久业务基线。

## [W1] OWASP LLM01:2025 Prompt Injection

`https://genai.owasp.org/llmrisk/llm01-prompt-injection/`

外部不可信内容、最小权限、高风险人工确认。只作安全设计参考，不声称能彻底消灭提示注入。

## [P1] 既有售前业务规格

Library 文件：`PI_Solution_Agent_Spec_v1.0.md`，基线日期 2026-08-21。
本轮通过 Files 检索到目标、工作对象、端到端流程、Skills 和 G1/G2 片段。该文件基于 PI SDK、Node/Fastify、SQLite；此包只复用其业务方法，不移植技术栈，不声称重新审阅过它的全部代码和可执行契约。

## 本包的原创设计部分

新增售前模块、对象、任务、场景包和交接示例是根据用户本轮“先做售前解决方案”收敛的设计，不是仓库已有功能清单。示例企业、产品与所有演示数值为虚构。JSON 格式文件的语法可以校验，但这不能证明 MateClaw 已实现加载器、API 或运行行为。
