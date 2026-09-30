# AQ-02 撤权并发工程验收

本切片从 `6a28a13132b51292d3c3130bc8036523ce78d737` 开始；工作区起初干净，初始 dev 为 SCAN_PASS。实现计划见 `AQ02_AUTHORITY_PLAN.md`，可保留的逐用例结果、源码哈希和原始日志/XML 哈希见 `authority-test-results.json`。

## 发现与修复

1. JDBC 权威锁不会使同事务中的 MyBatis SESSION 缓存失效。Workspace 删除或成员降为 viewer 在结果等待锁期间提交后，原来的 mapper 复核仍使用初读缓存。完整 service 夹具在修复前实际接受候选，预期拒收的两个断言失败。
2. 语义修订按 graph→KB/raw 锁行，原结果 fence 按 KB/raw→graph 锁行，存在相反锁序。修复前竞争用例在 KB NOWAIT 失败。

修复复用现有依赖与权威行：`ProjectAuthorityFence` 的 graph 锁提前到 KB/raw 前；全部权威锁成功后清除共享 `SqlSessionTemplate` 的当前事务一级缓存，再执行既有领域复核。没有新增表、依赖、全局缓存配置或修改 Flyway。模型调用仍在结果短事务外。

## 执行证据

JDK：Temurin 21。命令均在架构质量工作区执行；Maven reactor 中未包含指定测试的上游模块使用 `surefire.failIfNoSpecifiedTests=false`，目标测试非零且零跳过。

```sh
mvn -B -pl mateclaw-server -am \
  -Dtest=ProjectAuthorityFenceDatabaseTest,PresalesAtomicResultAcceptanceTest,PresalesSourceScopeTest,PresalesIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终 H2：31 项通过、0 failures/errors/skipped；其中新增并发夹具 19 项。日志：`/tmp/mateclaw-aq-fence-h2-final.log`。

MySQL 使用同一新增测试，指定 `-Dtest=ProjectAuthorityFenceDatabaseTest`。外部 JDBC 环境仅指向本机临时容器的空白 `mateclaw_aq_acceptance_20260930` 库；URL 限定本机与该命名，建夹具前检查空库，每次清理仅自建表。没有使用项目/客户数据库。版本为 MySQL 8.0.46；镜像 digest 见 JSON。

最终 MySQL：19 项通过、0 failures/errors/skipped。日志：`/tmp/mateclaw-aq-fence-mysql-final.log`。测试后数据库表数为 0；临时容器按任务所有权删除。

修复前 H2 完整 service 夹具：19 项中 16 通过、3 errors、0 skipped，两个撤权拒收断言与一项锁序断言失败。MySQL 修复前的行锁/cache 夹具亦为 16 通过、3 errors；该早期夹具在 runtime 边界复核，不能将它描述为最终 service 持久化路径。

## 覆盖与限制

- 16 项覆盖 actor、Workspace、成员、员工、模型、KB、raw、graph 权威行的双顺序竞争；只证明行锁与提交后状态可见性。夹具 graph 的 archived 字段不是生产图授权语义。
- 两项缓存回归使用真实 PresalesService、Workspace/member mapper 与共享 SqlSessionTemplate；撤权后的拒收检查项目版本、RUNNING 任务、receipt 和 revision 不变。service 直接构造后运行于 READ_COMMITTED TransactionTemplate，runtime mock 转调真实 PresalesAccess.requireActor；不是 Spring REQUIRES_NEW 代理或完整 runtime 政策验收。
- 一项锁序回归观察结果线程真实进入 graph SELECT 后检查 KB NOWAIT，消除仅依赖线程启动信号的调度假阳性。writer 使用语义修订相同的 graph→joined KB/raw 顺序；没有调用完整 SourceChangeService。
- 独立审阅 `/root/authority_writer_audit` 先提出两项阻断，修复后复核未发现新生产阻断；其时序改进已落地并复核。该结论仅为本次代码与测试审查，不是业务/QA 签收。

正式 AC-06–10 仍为 NOT_RUN。待办包括完整角色矩阵、真实 runtime 与代理事务、prompt/工具/历史/导出的一致来源范围、业务样本与独立 QA、生产同构数据库及全部撤权写入路径、远端 required CI 实际生效。

## 交付与回退

dev 只证明扫描；提交与推送必须由统一门禁对实际 tree 输出 PASS、submission_ready=true 后进行，报告路径随 Git 操作记录于交付回复。不得以本记录替代门禁。

回退为撤销本切片提交，无数据库回退步骤；回退会恢复已复现的缓存与锁序风险，应暂停结果接纳或安排替代修复。新增授权写入路径必须继续复核父行锁顺序与事务缓存，不能只依赖项目行锁。
