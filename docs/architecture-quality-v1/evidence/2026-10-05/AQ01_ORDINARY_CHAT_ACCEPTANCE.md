# 普通聊天全应用链路与无 usage 模型归属修复

本片对应 AQ-01 / AC-01，并扩展上一片 AC-05 启动矩阵。工程回归通过；46 项正式 AC 仍为 NOT_RUN，需要领域开发/QA、维护人等相应角色签收。没有执行提交、推送、部署或修改远端保护。本次不把本地检查改写成远端强制生效。

## 身份与范围

工作树 `/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`，HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `origin/dev=ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。沿用累计未提交改动，原项目工作区未动。五个本批源码摘要、22 个实际运行类、八变体结果见 [ordinary-chat-results.json](ordinary-chat-results.json)；最终 dev task、真实受检文本 tree identity、归档双 SHA-256 见 [final-manifest](ordinary-chat-tests/final-manifest.json.gz)。

[先行计划](AQ01_ORDINARY_CHAT_PLAN.md)记录夹具失败、生产缺陷、复审及隔离调整，保留全部失败证据，不以最终绿色覆盖失败历史。此前 [startup-only 证据](AQ01_FULL_CONTEXT_ACCEPTANCE.md)保持原有范围与身份；本片使用真正 `MateClawApplication.main`，补上此前绕过 main 时遗漏的 JDK HTTP 初始化。

## 改动与边界

- `StateGraphReActAgent.java`：普通/审批回放两条流，以每次流独立的响应观测标记补发零 usage 的最终归属事件；必须 count>0 且已有工具调用响应或正常答案。仅失败/停止尝试不算模型响应；每轮 count 显式归零。原 token>0 与委派用量路径保持，未编造 token，也未修改权限、业务适配、数据库或事务。
- `StateGraphReActAgentAttributionTest.java`：16 项，覆盖普通/回放、有/无 usage、零调用、失败/停止、同会话连续两轮，以及工具已响应但下一次调用停止时保留响应模型归属。
- `ModuleStartupProbe.java` / `ModuleStartupMatrixTest.java` / `OrdinaryChatProbe.java`：复用独立 JVM/H2 隔离矩阵，执行真入口、登录、普通聊天 SSE、真实时间工具、数据库落库和 HTTP 历史回读。没有替换应用内部业务/认证/工具/持久化 bean。

生产 Java 文件首次进入本地强制 Spotless 格式化，整体 diff 包含大量既有代码换行，不能将总 diff 宣称为几行。行为差异集中在两条流的响应标记/事件条件及输入 count 初始化；格式化前源码与行为草稿均已独立归档供审阅。没有新依赖、通用框架或额外业务层。

每个新库先验证 Flyway V219、真实模块 bean、待初始化状态和启用的外部任务配置为零，再创建只在本次进程生效的合成 owner/workspace、本地 provider/model/agent/时间工具目录项；创建后本地 provider 已启用，不能继续宣称所有 provider 为零。模型边界使用 loopback OpenAI-compatible 服务，故意不返回 usage，先要求实际 `tool_call` 桥调用 getCurrentDateTime，收到真实日期结果才返回唯一回答。`/v1/models` 探测与两次推理分别计数。

## 实际验证

生产缺陷原始 RED：HTTP 已聊天成功但历史模型/供应商为空；最初 8 项单元测试中 2 项失败。复审指出尝试次数包含失败，新增后 14 项中 6 项真实失败；收紧条件后通过。最后增加两项独立工具响应锁存断言，最终 **22 类 136 项，0 失败/错误/跳过**，其中八种语义/售前/投标模块开关组合均通过。

每个变体完成：真实 HTTP 登录 → SSE 两轮模型请求 → 真实工具开始/成功事件 → 单一 completed/persisted done → 同身份 HTTP 回读一条 user 和一条 assistant，内容、工具成功元数据及 done/history 消息 ID 一致。单一 loopback provider 的 runtimeModel/runtimeProvider 回读匹配。项目工具检查同时覆盖顶层 schema 与 messages 中渐进目录中的已知项目专用名称。

SQL 观察从实际 H2 schema 取得 16 张售前/投标表；先用售前/投标 SELECT 正例证明观测器可见，再清空统计，在聊天/回读后等待两次间隔 1 秒的形状及计数稳定（最多 5 秒）。所有变体观测非空且包含会话 SQL、远低于容量上限，售前/投标表查询为零。完整字段及 SQL 形状摘要在结果 JSON；不保存 SQL 载荷/凭据。

中间 134 项复查曾有 1 项隔离失败：101 已完成聊天与保存，但恰逢 21:30 事实投影 cron，SQL 静默未满足。保留该次 22 类 XML 和日志。隔离 profile 使用既有配置关闭 fact projection / wiki chunk token backfill 两个半小时 cron；项目表零访问、SQL 正例、静默、消息和工具断言均保留，未改变生产调度代码。该夹具不验收后台 cron，其余调度仍可能导致静默检查 fail-closed。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am \
  '-Dtest=ModuleStartupMatrixTest,StateGraphReActAgent*,ProjectConversationToolBoundaryTest,ProjectExecutionRevalidatorDispatcherTest,ProjectExecutionWiringTest,ToolExecutionExecutor*,AgentStreamAccumulator*,ChatControllerPersistStatusTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
git diff --check
```

Spotless 与最终 dev 的真实退出码/报告见归档。dev 的 SCAN_PASS 仅对应文本增量扫描，submission_ready=false；没有完整暂存，未执行 exact staged-tree commit 门禁。本片没有前端变更，未重跑前端 lint/typecheck；当前 Java 变更已编译、Spotless 与 ArchUnit 回归，不能套用旧版全量 verify 结果证明当前全部工程。

## 独立技术审查

通过本地 [ask-claude Skill](/Users/guojiexie/.codex/skills/ask-claude/SKILL.md) 执行只读 CLI 审查（禁用 tools/MCP），在原生子代理配额耗尽后作为替代。复审发现并推动修复失败请求归属、渐进目录检查等问题；第三轮结论 no blocking findings，条件为最终回归通过；第四轮专门复核仅工具响应测试与 cron 隔离变化，同样无阻断项；额外控制组合及调度注册表断言属未实施的非阻断建议，保留为后续强化项。原文及完整提示见归档。这是基于所提供源码的技术意见，不具有维护人批准、正式 QA 或合并权限。

## 限制、风险与下一步

- 真实入口和生产 classes 使用测试依赖 classpath，排除其他 test-classes 并只注入探针/ReadyListener；不是发布包启动或生产环境验收。成功运行的临时根均自动删除，失败根保留诊断。正常 context 关闭之后进程显式退出，不能据此证明所有后台线程自然退出。
- H2 的有限窗口只证明被观察的已完成 SQL，不覆盖未来延迟作业、失败 SQL、视图/触发器/FK 间接访问。正例仅 SELECT；不能宣称已专项验证 DML 观测器。关闭的后台任务不在范围。
- 工具名称检查是已知项目工具集合的负例，不是对未来别名的自动证明；权限、项目来源与工具执行相关回归提供补充，不能用允许的时间工具代替全部工具策略验收。
- 归属证明仅限单一实际返回的 loopback provider。既有 failover helper 未将实际备用 provider/model 回写图状态，备用模型归属仍需后续修复；plan/execute、summary、无 usage 的其他终止形态不宣称全部修复。已有成功工具响应后后续请求失败/停止，会保留本轮已响应模型的归属（已单测），这不改变消息终止状态。
- token 字段原本在缺事件时也存零；本片保持此兼容，不解决未知用量和真实零用量的计费区分。
- 真实供应商、MySQL/Kingbase 同链路、默认完整 seed、多角色浏览器、旧历史样本、正式需求版本、hash V2 兼容决策、提交及远端 required CI 仍开放。

回退：按职责撤回本片流式归属改动及测试探针即可；不回退或改写历史迁移，不动累计其他职责切片。回退会重新暴露无 usage 回复缺模型归属的问题。
