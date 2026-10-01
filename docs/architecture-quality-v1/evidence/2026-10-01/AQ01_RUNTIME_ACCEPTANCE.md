# AQ-01/02 真实 runtime 与代理事务工程验收

起点为 `05bb9f5c646b5cea408c81cc045200c9f16f204e`，工作树干净，初始 dev SCAN_PASS。计划见 `AQ01_RUNTIME_PLAN.md`。本批新增集成测试和验收记录，没有修改生产代码、现有断言、门禁、依赖或迁移。

## 真实路径与替身边界

Spring 装配真实 PresalesService 事务代理、PresalesEmployeeRuntime、ProjectExecutionRevalidatorDispatcher、PresalesExecutionRevalidationProvider、PresalesAccess、PresalesContextProvider、ProjectSourceAccess、AuthService、ModelConfigService 及实际数据库 mapper。已有的用户/Workspace fixture 每次建立独立身份与工作区；项目与 RUNNING 任务由服务创建，任务 ID 由服务器产生。

AgentService 仅作为图/模型外部边界替身；getAgent 转调真实 AgentMapper，而不是返回固定员工。模型流、会话接口、provider/capability 为受控替身。没有执行完整 Agent 图、真实工具或真实模型网络请求；未声称模型质量或会话历史验收。

## 本批用例

- 6 项执行前拒绝：actor 停用、员工停用、模型配置 pin 变化、成员变为 viewer、任务取消、项目变化；检查实际错误码/状态，模型流和会话均未调用，完整持久项目与 receipt/revision 不变。
- 6 项生成后拒收：先完成受控 runtime 输出，再发生上述变化；候选不能覆盖当前持久状态，不新增 receipt/revision，也不再次调用模型流。
- 1 项有效结果：真实 service 是 Spring AOP 代理；调用者事务为 SERIALIZABLE，结果 fence 内事务为 READ_COMMITTED，连接资源不同。调用者回滚后，其对另一个工作区的写入回滚，而 SUCCEEDED 结果、版本和结果正文仍持久保留。
- 2 项并发撤权：writer 先更新 Workspace/成员并持锁；结果初读旧有效状态、进入真实 fence 后等待；writer 提交撤权，真实 runtime/策略链拒收，完整项目/receipt/revision 保持原样。两个线程异常均传播，身份在每个线程与用例结束后清理。

这补齐了 2026-09-30 夹具中的 runtime mock 与直接构造 service 的限制；仍未补齐来源材料/UI/历史/导出/异步调度和所有角色矩阵。

## 执行结果与复跑

JDK 为 Temurin 21。最终 H2 定向回归 30 项通过，0 failures/errors/skipped；其中本批 15 项，另含 runtime、provider、原子结果和分派器装配回归。日志 `/tmp/mateclaw-aq-runtime-h2-final.log`。

```sh
mvn -B -pl mateclaw-server -am \
  -Dtest=PresalesRuntimeTransactionIntegrationTest,PresalesEmployeeRuntimeTest,PresalesExecutionRevalidationProviderTest,PresalesAtomicResultAcceptanceTest,ProjectExecutionWiringTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

仅上游模块没有指定测试时使用 surefire.failIfNoSpecifiedTests=false；实际目标测试非零，未跳过断言。

同一个本批类在 MySQL 8.0.46 通过 15 项，0 failures/errors/skipped。使用 `-Dtest=PresalesRuntimeTransactionIntegrationTest` 并指定 MATECLAW_ACCEPTANCE_JDBC_URL/USER/PASSWORD；日志 `/tmp/mateclaw-aq-runtime-mysql-final.log`。空白 `mateclaw_aq_acceptance_runtime20261001` 数据库成功执行 209 个现有 MySQL Flyway 迁移，达到 v216；不是手工简化的权威表夹具。

外部配置只允许 127.0.0.1 及专用验收库名，连接后检查真实 catalog 匹配且无 TABLE/VIEW，随后才执行 Flyway。第一次命名不符合格式的空库被保护拒绝，没有放宽规则；按合规名称新建本任务容器后完成实跑。测试自身不清库；父任务创建和删除带任务标签、tmpfs 数据目录的独立容器，未连接业务数据库。

最初的两轮失败来自新增夹具装配/任务创建错误（Spring 对 @Bean mock 的字段注入、创建任务错误地提前提供 ID）；按现有 @MockBean 方式和服务端 ID 规则修正后通过，没有修改生产实现或放宽业务断言。格式化第一次等待 Maven 元数据，最终使用已缓存工具离线显式格式化成功；正常测试与工程门禁未切换为跳过模式。

首次完整 commit 门禁的 Java 回归出现两项错误：SecurityAsyncDispatchTest 使用的本任务临时 H2 文件库报 MVStore shrinkStoreIfPossible AssertionError 并关闭，WebChatStreamE2ETest 出现 HTTP header EOF。新增 15 项全部通过，其他门禁均通过；该次提交被正常 hooks 拒绝，报告 `mateclaw-quality-wxm5cw8r/report.json` 保留。随后用独立内存库复跑这两个类，14 项零失败/跳过（`/tmp/mateclaw-aq-runtime-recovery.log`）；SSE 错误未再复现，未把它归因为已确认的业务缺陷，也未修改测试或断言。完整门禁后续使用独立内存库消除这次文件存储故障的影响，仍执行全部检查。

逐用例结果、最终测试源码与日志哈希见 `runtime-test-results.json`；原始 XML/日志位于运行主机，不包含在仓库内。

## 独立审阅、未测与回退

独立 `/root/runtime_acceptance_review` 对测试/夹具和临时库配置进行只读审阅，未发现业务测试阻断；空库视图检查建议已落实。审阅工具未提供有效 Java LSP/AST，Java 类型与行为证据以实际 Maven 编译及工程门禁为准。该审阅不是正式业务 QA。

AC-06/08/09/21 仅新增工程证据，正式状态保留 NOT_RUN。其余缺口：完整角色和批准政策、真实 API/UI，Agent 图/工具/异步协调器及重启，来源撤回与历史/导出一致性，真实模型质量与授权业务样本，全部权威写入路径/生产同构验证，远端 required CI/维护人和业务 QA 签收。

提交与推送必须对真实 tree 执行统一门禁并得到 PASS、submission_ready=true；结果在交付回复和 PR 中登记，本记录不替代门禁。回退仅撤销新增测试和记录，无生产或数据库变更。


### 2026-10-02 隔离说明更正

上述历史叙述中“临时 H2 文件库”和“完整门禁后续使用独立内存库”的说法不准确。`scripts/quality/verify.py` 的 `clean_test_env` 会过滤 `SPRING_*`，因此完整 hooks 没有接收当时设置的 datasource 环境变量，未显式配置 datasource 的测试实际使用默认文件库。原完整门禁的 PASS 与测试计数仍有效，但不能证明数据库隔离。直接 Maven 两类恢复测试的环境变量不经过该过滤，14 项通过的证据仍有效。

本次 AQ-02 完整提交门禁再次出现 SecurityAsyncDispatchTest 默认文件库 MVStore AssertionError，提交被正常拒绝（`mateclaw-quality-b972d10h`）。改用测试类内显式 TestPropertySource 为 SecurityAsyncDispatchTest 和 OpenApiExposedAccessTest 分配唯一内存库，保持完整启动和原断言；没有调整门禁过滤规则或操作默认数据库文件。其他默认文件库上下文仍需单独处理，不能由这两个类的隔离推断全套测试均已隔离。
