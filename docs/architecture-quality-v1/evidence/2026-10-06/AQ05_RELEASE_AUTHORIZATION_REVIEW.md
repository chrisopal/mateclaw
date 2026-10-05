# AQ-05 发布实时授权独立审阅

审阅者：Codex独立只读子代理 `/root/release_authorization_review`；原始角色约束来自`/Users/guojiexie/.codex/prompts/code-reviewer.md`。仅审本片Service相对before快照、新策略、新测试及计划；累计WIP不是本片全部审查范围。

结论：**COMMENT**，0项可操作缺陷（CRITICAL/HIGH/MEDIUM/LOW均0）。不是维护人/QA签收、提交批准或发布权限。

第一阶段规格符合性通过：检查顺序保持模块可用性→baseline/需求revision→绑定graph→当前ontology→当前statement/revision→evidence/source→fit-gap→独立人工评审。每次动态读取provider，无授权缓存；query仅实际读取证据时要求存在。Service保留PresalesRejected/来源Denied的status/code/message转换，以及角色前置、事务、CAS、文件持久化和发布状态转换。既有61项Service发布合同和新增8项策略测试覆盖精确revision、独立评审、动态可用性、事实变化、来源短路和fit-gap。

第二阶段复核定向118/118、扩展721项720通过/0失败/0错误/1既有PPT环境skip、Spotless exit0、dev 2fg_vsnu SCAN_PASS/submission_ready=false及git diff --check。限定范围检查未发现空catch、日志残留或疑似硬编码凭据；无修改门禁、测试配置或削弱断言。

工具限制：Java LSP **NOT_RUN**，工具实际返回`tsc skipped: no tsconfig found`，不能以0 diagnostics宣称Java诊断通过。ast-grep未安装，采用限定范围rg补查。JDK21 Maven实际编译通过单独记录。审阅不会把工程检查变成正式业务验收。

最终文档/证据归档后的dev由主任务另行运行并核对工作树identity；commit精确暂存树、远端required CI、维护人/QA和真实环境仍开放。
