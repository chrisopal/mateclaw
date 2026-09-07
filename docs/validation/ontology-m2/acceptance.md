# 企业语义核心 M2 验收记录

日期：2026-09-07
范围：SEM-04～SEM-08，分支 `codex/enterprise-semantic-core`，工作树 `/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`。

## 已实现

- 单知识库单图，绑定精确已发布本体修订；ENABLE、DISABLE、REBIND 使用图版本锁。实体、来源、任务或事实存在时图不再视为空。
- 实体按绑定本体类型登记；名称不是身份键。
- 纯 Java 事实/证据/冲突核心：作用域与本体版本一致性、值类型/固定单位、UTC 半开区间、UNKNOWN、BigDecimal 等价、单值冲突及 Unicode code point 证据核验。
- Wiki 原始资料导入为不可变快照；持久任务含 QUEUED/RUNNING/SUCCEEDED/FAILED、attempts、租约和重试链。执行及恢复时重新检查操作者和工作区权限。
- 事实候选、不可变修订、修改提案、证据链接、OPEN 冲突和幂等命令均持久化。候选不会进入 trusted 视图。
- 事实确认/拒绝/撤回、修改审核、冲突显式 winner 决策、来源撤回和快照排除均在图锁事务中执行。关键治理命令支持相同 operationId 回放，变更 payload 返回 409。
- trusted/review/mine 列表、修改提案列表/详情、冲突列表、受限文本搜索、最多两跳邻域、证据片段和治理历史读取。trusted 同时要求 ACCEPTED 与 SUPPORTED。
- Wiki 管理页提供本体版本绑定面板，显示实际固定版本、启停状态和非空图换绑限制。

## 自动化证据

- `mvn -pl mateclaw-semantic-core clean test`：25 tests，0 failures。
- 语义后端回归：`SemanticAuthorizationTest`、`SemanticConfigurationTest`、`SemanticCoreArchitectureTest`、`SemanticDisabledTest`、`SemanticGraphBindingTest`、`SemanticM2IntegrationTest`、`SemanticMigrationTest`、`SemanticOntologyIntegrationTest`，共 31 tests，0 failures。
- M2 数据链覆盖：旧快照在 Wiki 文本更新后保持不变；emoji 证据按 code point 精确回读；审核前 trusted 为空；确认后搜索和证据可回读；来源撤回后 trusted 为空但 ACCEPTED 修订仍在；两项互斥候选必须显式处理冲突；失败导入可重试且 operationId 回放一致。
- 前端 `vue-tsc --noEmit` 和所改文件 ESLint 通过；全量 Vitest 61 files / 378 tests 通过。
- enterprise 与 classic 两种 Vite 构建通过。构建仅保留仓库既有的大 chunk 警告；Vitest 仍出现与 M1 基线一致、未导致失败的 ECONNRESET 日志。
- H2 从空库迁移至 V193、从 V190 升级至 V193均通过；后端打包使用 `-Dmaven.compiler.proc=full` 通过。

## 运行态与边界

- 本次运行态使用独立 H2 文件，不连接或改写用户原工作区数据库。最终打包产物以 Java 21 启动成功，Flyway 回读当前版本 V193，`/actuator/health` 返回 `UP`。
- 通过浏览器新建“M2 验收知识库”，在 Wiki 管理页选择已发布的“设备维护 · v2”并建立图谱绑定；界面回读“已启用 / 当前固定版本 v2”。重启后端后再次打开同一管理页，知识库及绑定状态仍然存在，证明 API 写入和 H2 落盘回读链路成立。
- MySQL 与 Kingbase 已提供 V192/V193 方言迁移，但本次没有连接真实实例执行，不能据此宣称数据库兼容已完成。
- M2 文本搜索是有界字面检索，不是向量语义检索。完整候选/审核/冲突/图谱管理工作台及 Agent 工具属于 M3。
- 生产级高并发压力、真实 MySQL/Kingbase 和部署环境权限联调仍由 SEM-11 收口。
