# 售前 V2 新数据工程验收

任务 `AQ03-V2-newdata-20261006`。用户明确排除旧数据/旧消费者兼容，允许新数据验证；Kingbase 延后 TODO。此记录区分代码工程验证、真实模型质量和正式业务签收。方案见 [新数据设计](../../../plans/2026-10-06-presales-v2-newdata-design.md)。

| 本轮事项 | 工程结论 | 证明 |
|---|---|---|
| AC-18 无关档案修改不使任务失效 | 已完成 | 精确输入依赖；收尾使用最新项目 CAS，保留无关修改 |
| AC-19 实际对象/来源变化使旧任务失效 | 已完成 | 对象 ID/业务修订/内容摘要/集合成员及来源摘要；排队、执行、工具、收尾核验 |
| AC-27 V2 单一写入权威 | 已完成 | 新项目 metadata + 独立对象/章节不可变修订；全部业务/工具通过 Repository 组装；项目没有第二份可写聚合 |
| AC-28 新写入故障与回退 | 已完成 | 同事务回滚、拒绝降级、历史精确回放、重启/撤权失败收尾；禁止旧二进制接管 V2，采用前向修复 |
| AC-24 新 Delivery 消费 | 已完成 | 精确 project/release/digest 接收、持久化回读、并发幂等、错摘要与跨 Workspace/撤权拒绝；真实审批发布 HTTP 主线 |
| AC-45 真实生成质量 | 阻塞 | DeepSeek 真探测 HTTP 401；本机 Ollama tags 为空，两个模型清单各缺 4/4 blobs；没有模型成果可验收 |
| AC-26 Kingbase | TODO | 用户明确延期；对应 SQL 文件仅满足迁移入口完整性，不代表运行通过 |
| AC-23 历史迁移兼容 | 本轮排除 | 用户范围调整；不将排除记作 PASS |

## 已执行验证

- JDK 21，隔离 Skill 目录：`mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dmateclaw.skill.workspace.root=<fresh> '-Dtest=Presales*,Delivery*,ToolExecutionExecutorPresalesScopeTest,ProjectConversationToolBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false test`。929 项，928 执行通过，0 failure/error；1 项既有 PresalesPresentationCompilerTest 环境条件跳过。
- 同一候选在临时 MySQL 8.0.46 空库执行 `PresalesRuntimeTransactionIntegrationTest`：50/50 通过。实际 Flyway 初装包含 V223/V224。容器只绑定 loopback，独立 tmpfs，测试后核验并移除本次容器；没有复制旧业务数据。
- 先在真实 MySQL 新增 3 项 RR 回归，确实复现 2 个 Missing pinned revision 和 1 个重复修订主键失败；锁定 manifest/receipt/history 后的对象和章节读取、当前指针读取及删除重加版本分配改为 current read，原断言不变后 50/50 通过。H2 对旧 RR 锁定冲突断言 SQLSTATE 40001，没有跳过。
- H2 真实数据库覆盖后台无调用方事务的重启恢复、撤权失败收尾、SQL 异常整体回滚及加入外层事务回滚；独立技术复核关闭原 HIGH finding。
- Delivery 的 H2 双线程相同 operation 返回相同 receipt，异请求拒绝，竞争失败后重新核验来源权限；跨模块公开接口传递错误，Adapter 不再引用 semantic 内部异常。
- `PresalesIntegrationTest.g1RequiresAcceptedFactsAndReleasePublishesExactBytes` 从新项目、真实证据/需求/基线到不同角色评审、审批、发布，再调用真实 Delivery HTTP preview/receive/replay/get 并检查 SQL 单条持久化；后续澄清不改变已接收快照。身份服务、售前 port 和数据库均为实际应用组件；内容是合成测试数据，不是模型生成或客户批准。
- 首次全量门禁定位到真实重启验收探针仍直接读取旧聚合 body。探针改用 Repository 回读，并新增 V2 引用结构、对象/修订数量和重复启动原始 manifest 字节断言；独立复核确认保留原有崩溃及两次重启断言。`ModuleStartupMatrixTest#presalesRecoveryAcrossProductionProcessRestarts` 在 3 个真实 JVM、持久化 H2 上执行通过（1/1，0 跳过）。
- dev 静态增量与 guard 自测通过；最终精确暂存树 commit/push 门禁和远端 CI 的实际报告以本次提交/PR交付记录为准，不能从这里推断未运行结果。

测试输入源码 SHA-256、测试数和完整日志 SHA-256 见 [机器结果](v2-newdata-test-results.json)。本地原始日志目录：`/Users/guojiexie/.codex/artifacts/mateclaw/v2-newdata-20261006/`；失败证据保留，没有删断言、降低门槛、重写旧迁移或基线。

## 仍然未完成的边界

真实模型长文、输出质量复核尚未执行通过；新编 19,508 字符材料和 12 条定位事实、正式 S5 运行器已备好。需要有效供应商凭证或完整可用的本地模型；不下载新模型、不复制旧业务库、不以模拟输出替代。Kingbase、旧兼容、生产部署、远端分支保护及正式业务签收不由此记录声明完成。
