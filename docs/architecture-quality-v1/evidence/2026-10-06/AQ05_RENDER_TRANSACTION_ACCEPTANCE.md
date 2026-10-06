# AQ05 发布渲染短事务工程证据

状态：实现与针对性工程验证完成；完整提交门禁由正常 hook 检查确切暂存 tree。正式 AC、维护人/QA、Kingbase、生产上线/回退仍 NOT_RUN，不能由本页自动升级。

起点 HEAD `e5ccc86c1c591104123ee9ee3a2809abf92f969d`，tree `a86c7a4ccbb0275bbbb88eb4bf0b830a9b219af0`；dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。只修改隔离工作树 `codex/aq01b-execution`；原项目目录只读。方案与取舍见同目录 PLAN 及 ADR-AQ-048。

## 改动与验证范围

- PresalesService 复用统一命令准备/持久化，发布入口交给 PresalesRenderExecution；普通命令仍加入调用方事务。新增任务在准备事务先提交，转换期间挂起外层事务，接纳为独立 READ_COMMITTED 事务。
- PresalesArtifacts 拆分固定输入捕获、无数据库调用的转换/摘要/编码、接纳事务内存储；PPT 实际字节锁定捕获，保留 textual/blank 模板分支。没有保留旧物化入口或复制最终回执。
- PresalesReleaseAuthority 覆盖当前与历史材料、来源、历史 fit 的真实 evidenceId、历史标量来源继承 graph。通用 ProjectAuthorityFence 新人工命令入口允许未绑定员工，但既有 AI 接口仍必须员工与模型；锁成功后重新授权。
- 主体/工作区行锁协调全部命令与创建请求的 operationId；回执、锁后 actor/workspace/member 使用当前锁定读取，避免 MySQL 外层 REPEATABLE_READ 快照漏回执或旧角色。宿主公共读取能力不依赖售前域。
- 新 V221 h2/mysql/kingbase 任务表只保存固定输入及活动 attempt/租约。十分钟租约内同键返回 RENDER_IN_PROGRESS；FAILED/过期可 CAS 重试，不改输入；旧批次不能覆盖新结果或终态。最终业务回执仍是唯一响应权威。

Service 原 711 行，当前约 850 行；新增事务/权限/任务实现增加生产代码，本片收益是消除渲染长事务和明确接纳协议，不能说整体减行或全部 Service 结构整改完成。

## 实际验证

统一 Maven 前缀为 JDK21 `mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full`，测试指定类并使用 `-Dsurefire.failIfNoSpecifiedTests=false test`；没有 skip、禁用或删除断言。

| 验证 | 结果 | 证据 |
|---|---|---|
| 原实现真实 RED，含外层 Spring 事务 | 2/2 失败：renderer 事务及连接仍在、并发编辑被阻塞 | `/tmp/aq-render-boundary-real-red.log` |
| 初次新生命周期/仓库 | 10/10 PASS | `/tmp/aq-render-first-green.log` |
| 扩展回归首轮 | 831 项，6 failures/2 errors/1 既有环境 skip；旧锁观察点、空 fence fixture、旧 PPT 渲染前后顺序/异常适配 | `/tmp/aq-render-regression.log`；修正同步点及实际新顺序，保留原语义/原子性断言 |
| 修正后针对性回归 | 242/242 PASS，包含生产字节码架构检查、公共围栏、来源、快照、仓库、生命周期及真实 runtime | `/tmp/aq-render-reviewed-green.log` |
| 最后一项历史标量 graph 反例 | 9/9 PASS | `/tmp/aq-render-final-races.log` 的 BoundaryTest；同轮新增 RR 测试暴露 H2 方言差异，不将该轮整体记为通过 |
| MySQL 8 空库全 Java/Flyway runtime | 214 项迁移验证并应用至 V221；40/40 PASS | `/tmp/aq-render-mysql-runtime.log` |
| MySQL 8 加入外层 RR 撤权与回放 | 43/43 PASS，含 workspace/member 撤权及快照后新回执重放 | `/tmp/aq-render-mysql-rr.log` |
| MySQL 8 发布全生命周期 | 14/14 PASS；同键跨命令/跨项目并发、重试/接管、响应丢失、五处真实 SQL 写入后故障回滚、普通调用方回滚 | `/tmp/aq-render-mysql-lifecycle.log` |
| H2 外层 RR 最终定向验证 | 43/43 PASS，无跳过；包括三项 RR 撤权/回放场景 | `/tmp/aq-render-h2-rr-corrected.log` |
| dev / 显式格式化 | SCAN_PASS；格式化是独立开发操作，check 不写源码 | `/tmp/aq-render-dev-final.log`；最终完整门禁另见 hook 报告 |

H2 的 REPEATABLE_READ 在快照建立后锁定已变行会以 SQLState 40001 回滚，不能伪称与 MySQL 当前读取相同。测试明确断言序列化拒绝、无新业务写入及回滚后精确回放；MySQL 单独断言同事务读到当前权限/回执。本片未修改普通命令外层事务传播，数据库返回的可重试序列化冲突仍需调用方重试。

收尾门禁真实失败保留：`7utkmbx6` 因清理后遗留的局部变量引用编译失败；改为读取已解析命令的 raw 值。随后 `67ujp7mq` 仅三项 H2 RR 断言失败：最深层 cause 是 MVStoreException，不能当作 SQLException。修正为沿 cause 链定位 SQLException，保留外层 CannotAcquireLockException、精确 SQLState 40001、无写入和精确回放断言；独立审阅确认没有放宽覆盖，43/43 定向重跑通过。两轮失败均无提交，不冒充完整门禁通过。

独立只读技术审阅先发现 RR 普通读取及历史 fit 来源两项 P1，再发现历史标量 graph 漏锁 P2；均修复并复核无剩余具体 P1/P2。测试环境和 H2 差异单独复核。技术审阅不是维护人批准。

原提交 e5ccc86c 的远端 run37399009807 已 SUCCESS：merge `b8d9a9f11fadaa9ec7123ea900c8cd145618ab5f`，tree 与 e5 相同，parents 为 PR base `68d8fc536b6d3a4b9c9064e7322060bd78869faf` 与 e5；Java6712/UI1227/cost16、15 日志 SHA 核验。该远端报告不覆盖本片未提交代码。

## 限制与回退

临时 MySQL 8 仅用隔离 loopback 空库；未碰运行中的项目数据库。Kingbase、真实 Office/浏览器/模型、生产备份与切换、AQ10 性能、正式 AC 签收未测。没有后台排队、自动重试或续租，调用方必须保留原 operationId；租约过期拒收，HTTP 断开不取消。冻结 JSON/PPT 增加存储及内存成本，尚未做大文件负载验收。

上线需停止旧 writer，不能与忽略 render_task 的旧二进制混跑。新任务产生后，回退必须先停止提交、处理活动 attempt 并保留幂等占用；不能直接删任务表或忽略回执协议。没有执行合并、部署或生产数据回退。
