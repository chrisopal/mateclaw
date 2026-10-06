# AQ-02 冻结发布/候选来源与响应权限工程验收

Base `a44f27f853dfb817561c69fbe5edd31a64041f5c`，独立工作树开始时干净；初始dev SCAN_PASS（mateclaw-quality-usyb_3ti）。计划见 AQ02_FROZEN_SOURCE_PLAN.md。未修改门禁、依赖、迁移、批准政策、生产数据或发布字节。

## 缺口与实现

真实HTTP发布流程负例先复现：冻结快照保留来源，当前材料/基线引用已移除后，原始来源已删除，项目仍返回200而非403（/tmp/mateclaw-aq-frozen-red.log）。该归档状态由测试直接准备，用于模拟历史引用整理/迁移，不声称实际产品提供删除基线接口。

get/getForExecution 现在复核每个对象型 handoffSnapshot，不限PUBLISHED，也覆盖PENDING/APPROVED。用深拷贝建立来源视图，复用现有材料/基线/sourceRefs的live来源、员工KB与graph撤回校验；绑定员工的项目要求冻结材料KB仍在当前材料中。标量/扁平澄清引用按真实raw ID复核，并继承冻结材料的graph范围。无员工的旧人工项目保持解绑政策，但冻结来源本身仍必须可读。缺少对象快照的旧release沿用已有门禁，精确handoff仍拒绝缺少快照。

独立审阅发现仅修GET不足：命令/幂等回放的完整JSON和候选快照也能暴露来源；旧回执还使用旧employee。最终命令响应在副本中按当前employee复核历史来源，再按当前材料校验冻结来源。403时成功响应仅返回既有项目summary并增加sourceAccessRestricted=true，材料/任务/基线/solutions/releases等来源集合返回空数组，保持工作台required数组契约；存储回执和项目仍为原始业务对象。这是明确的受限响应契约调整；可读状态的完整响应字段保持。绑定修复可以执行，恢复后再次返回完整对象。原始执行employee pin政策不由本片改变。

## 定向结果

JDK21，H2完整既有Flyway及真实MockMvc/服务路径，48项通过，0 failures/errors/skipped；最终日志 /tmp/mateclaw-aq-frozen-final.log。命令：

```sh
mvn -B -pl mateclaw-server -am \
  -Dtest=PresalesIntegrationTest,PresalesSourceScopeTest,PresalesRuntimeTransactionIntegrationTest,PresalesAtomicResultAcceptanceTest,ProjectAuthorityFenceDatabaseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实发布流程新增raw/KB删除、恢复；来源只在冻结快照时仍拒绝detail/latest handoff/精确handoff/download，PENDING/APPROVED候选detail也拒绝。治理withdraw通过实际语义API完成，当前引用移除后，结构化和标量冻结来源仍拒绝。标量-only历史夹具保留实际发布来源的字符串形状，不伪称新业务接口。

UNBIND_MATERIAL实际命令及同键重放、后续UPDATE_PROJECT返回受限摘要，基线/releases为空。BIND_MATERIAL修复后精确handoff JSON和真实HTTP下载字节与原发布一致。删除拒收不改项目JSON、operation/revision数量、存储成果字节。新增回放测试：原employee A可读历史来源，当前project改为B且当前引用清空后，旧回执返回摘要且不含来源文本；存储回执与当前项目不变；恢复A后回放返回原始完整JSON。

逐用例、源码/XML/日志哈希见 frozen-source-test-results.json；原始XML不入仓。显式离线格式化只复用已缓存工具，验收测试不跳过。

## 独立审核与边界

/root/frozen_source_review 最终限定审阅确认四项发现已关闭，无剩余阻断；Java LSP工具不支持、AST unavailable，未声明通过；类型/行为证据来自Maven。不是正式QA或合并批准。

本批未重跑MySQL/PostgreSQL/浏览器，未证明并发撤权与读取线性一致；缺少/畸形来源、缺graph的标量历史、多个release隔离、直接冻结-only getForExecution、原执行employee pin政策和完整来源交集仍未验收。受限摘要需后续UI真实操作验证。正式QA及远端required CI仍NOT_RUN；AC-07/08/09只追加工程证据。回退本片会恢复已复现的冻结来源和响应绕过风险，不涉及数据回滚/重渲染。

完整commit/push必须真实tree PASS且submission_ready=true，报告在PR/交付回复登记；dev和本记录不能替代门禁。


## 完整门禁恢复与测试隔离更正

首次完整 Java 回归报 SecurityAsyncDispatchTest 默认文件库 MVStore AssertionError；正常提交未成功。失败报告 `mateclaw-quality-b972d10h` 同时记录恢复编辑引起的 PARTIAL_STAGING，submission_ready=false，未当作通过。门禁过滤 SPRING_*，先前完整 hooks 的环境变量数据库隔离声明已在 AQ01_RUNTIME_ACCEPTANCE 中追加更正；没有改变过滤规则或操作默认数据库文件。

只为 SecurityAsyncDispatchTest / OpenApiExposedAccessTest 添加 TestPropertySource 的各自唯一 H2 内存库，保留完整应用启动、随机端口、Flyway和原断言。SecurityAsyncDispatchTest、OpenApiExposedAccessTest、OpenApiLockedDownAccessTest 三类定向回归通过，计数/哈希在结果 JSON，日志 `/tmp/mateclaw-aq-frozen-isolation-tests.log`。独立控制面工程审阅无阻断；不是正式维护人签收。其他默认文件库上下文仍待单独隔离，本片不声称全套数据库隔离。
