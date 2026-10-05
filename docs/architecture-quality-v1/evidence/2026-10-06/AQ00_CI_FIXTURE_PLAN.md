# AQ-00 CI 测试生命周期修复计划

- 起点 HEAD：692c212a8124029d05ea0a9cb5cb2e97ee500f2b；工作树 clean。
- 初始 dev：p5dl_zv8，origin/dev，SCAN_PASS，submission_ready=false。
- 远端 run 37362577679 已执行工程检查；Java 32 errors，其他步骤通过。不是业务验收。

## 根因与范围

1. PresalesSqlListingTest 的 31 个用例均在清理阶段失败。DEBUG 开启时 JdbcTemplate 在 SHUTDOWN 后调用 Statement.getWarnings，H2 已关闭，抛出 90121。相同 DEBUG 条件本地复现 31 errors；另三个同型 fixture（ArtifactRepository、ProjectRepository、SourceAuthorization）复现 18 errors。直接用 JDBC try-with-resources 执行关闭，保留错误传播、唯一内存库、外部数据库 disposable/ownership 防护。
2. DelegateAgentToolTest.requiredFailureCancelsSibling 的慢任务可能尚未启动就被合法取消，导致 strict Mockito 的 stub 未使用。用启动/释放 latch 明确测试“已启动 sibling 被取消”场景，finally 释放阻塞；保留 2500ms、cancelled=1、已取消断言以及严格 stub 检查。无需更改生产取消逻辑。

仅修改上述五个测试和本次证据。无公共接口、权限、数据权威、事务、依赖或数据库迁移变更。没有新增抽象、异常吞没、关闭 DEBUG 或 lenient。

## 验证与审核

- 固化 RED 日志，显式 formatter 操作与行为 diff 分开核对。
- 相同 DEBUG 下四个数据库 fixture 49 个用例 GREEN；运行 DelegateAgentToolTest 全类及低 CPU 调度重复验证。
- 每片 dev；独立测试控制面审阅；正常 commit/pre-push 完整门禁；精确 HEAD 的远端 CI。
- 报告任务 ID/tree、日志摘要、未测环境。独立技术审阅不替代维护人批准；正式 AC、远端分支强制检查不在本修复中宣称完成。
- 回退：仅撤销这五个测试的清理/同步改动，生产行为没有变化。
