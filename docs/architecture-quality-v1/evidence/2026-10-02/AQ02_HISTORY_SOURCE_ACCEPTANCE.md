# AQ-02 历史/成果员工来源权限工程验收

Base `233fccecd21a1c5dc5ba8e967af236cee6585a3d`，独立工作树开始时干净。计划见 `../2026-10-01/AQ02_HISTORY_SOURCE_PLAN.md`。本批未修改 UI、批准政策、依赖、迁移、门禁或生产数据。

## 缺口与边界修复

真实 HTTP/Flyway 发布回归先复现：绑定员工 wiki_disabled 后，项目历史读取预期403却返回200并含原快照，日志 `/tmp/mateclaw-aq-history-red.log`。最初测试编译失败因错误的 Agent 包名，修正夹具导入后才得到上述业务负例。

PresalesService 历史读取对当前材料复用 ProjectSourceAccess.canEmployeeReadKb；持久 baseline/task 来源复用新增 canEmployeeReadSource，由宿主公共服务按数据库实际 raw→KB 归属复核，不信任历史快照的 kbId。get/getForExecution 及复用 get 的 preview/download/handoff 保持原 Workspace 校验，新增当前项目员工范围校验。无 agentId 的旧人工项目保持原有来源政策；不改变列表元数据可见性。

第一次 dev 扫描拒绝了新增跨域 raw SQL（AR-005，报告 mateclaw-quality-nrbpqd6f）；已把实际来源归属查询收回现有宿主来源服务，售前仅调用公开能力。未改baseline、指纹规则或阈值。只读失败不渲染、不改写或删除项目/回执/修订/成果。回退会恢复已复现的员工撤权后历史读取缺口。

## 工程证据

最终定向47项通过，0 failures/errors/skipped，日志 `/tmp/mateclaw-aq-history-final.log`。命令：

```sh
mvn -B -pl mateclaw-server -am \
  -Dtest=PresalesIntegrationTest,PresalesSourceScopeTest,PresalesRuntimeTransactionIntegrationTest,PresalesAtomicResultAcceptanceTest,ProjectAuthorityFenceDatabaseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

扩展既有完整发布流程：wiki_disabled、员工停用、员工删除、KB grant停用四类撤权，对detail/latest handoff/精确 handoff/发布下载均拒绝；恢复后真实下载与原始发布字节一致，冻结 handoff JSON一致。拒收前后项目JSON、operation数量、revision数量和artifact字节均不变；保留已有来源withdrawal下载403断言。新增基线/任务历史来源负例：没有当前material绑定，快照伪造已授权kbId，真实raw属于另一KB仍403；恢复grant可读，移除agentId的旧人工项目可读。

现有 Context/tool 来源交集、真实 runtime/代理事务与数据库 fence 回归一起运行。逐用例结果和源码/XML/日志哈希见 `history-source-test-results.json`，原始XML不入仓。显式离线格式化仅使用缓存工具，测试和完整门禁不跳过。

独立 `/root/source_history_audit` 对最终五个Java文件进行限定只读审阅，零阻断；计划的延期边界已补齐。LSP/AST工具返回Transport closed，未报告为通过，实际Maven编译/测试提供Java证据。最终dev为SCAN_PASS（mateclaw-quality-sotkpzw0），不代表submission_ready。

## 验收边界

只覆盖 H2/Flyway/真实HTTP与服务链；本批未在MySQL/PostgreSQL或浏览器重跑，未证明读取和并发撤权具备线性一致性。完整图/工具/cache、冻结release独有来源、material解绑、员工重绑定政策、异步重启、真实模型/客户样本、正式QA及远端required CI仍未完成。独立限定审阅结果与真实commit/push tree报告在交付/PR登记，不作为正式业务签收。AC-07/08仅追加工程证据，状态保持NOT_RUN。
