# AQ-02 来源授权职责拆分工程验收

起始 HEAD `38cedb589c7c9700c4ac1afa52bfeafbd4486528`，base `origin/dev`，独立工作树原本干净。计划见 [AQ02_SOURCE_BOUNDARY_PLAN.md](AQ02_SOURCE_BOUNDARY_PLAN.md)。本记录不是完整 AQ-02/P0 或业务签收。

## 改动与复用

PresalesSourceAuthorization 统一承担当前材料、历史任务/基线、冻结发布及来源摘要的领域复核。WikiSourceReadService/Repository 只读当前工作区原文事实，SourceGovernanceReadService/Repository 只读持久WITHDRAWN事实；无提取、模型外发、图写入或功能开关旁路。领域策略仍复用ProjectSourceAccess的员工KB交集和真实raw归属。PresalesService保留actor/member入口、命令事务/CAS、不可变回执、权威锁和旧异常适配，本片原跨域SQL已删除。

保留原始COALESCE(NULLIF(extracted_text,''),original_content)、deleted=0、UTF8摘要、空白文本、SQL null转空、材料null-deleted旧行为、human-only旧项目、跨source_kind撤回、baseline/task空graph差异、历史忽略旧digest、冻结候选与当前绑定交集。领域拒绝在应用服务转换为旧SemanticApiException，类型/状态/代码/消息保持；不增加新语义web依赖。仅构造器/测试imports适配，原权限断言全部保留。

## 真实证据

- 搬移前：50 H2 HTTP/JWT/事务/权威锁回归，0失败/错误/跳过。
- 固定Spotless格式后：71项，0失败/错误/跳过，BUILD SUCCESS。其中新领域7、新Wiki10、新治理4；原50继续执行。
- 命令：`JAVA_HOME=<Temurin21> mvn -pl mateclaw-server -am -Dtest=PresalesSourceAuthorizationTest,WikiSourceReadServiceTest,SourceGovernanceReadServiceTest,PresalesIntegrationTest,PresalesSourceScopeTest,PresalesRuntimeTransactionIntegrationTest,PresalesAtomicResultAcceptanceTest,ProjectAuthorityFenceDatabaseTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`：SCAN_PASS，`mateclaw-quality-ui8h67cs`。Quick scan应用工具链为NOT_RUN。
- 新契约测试在类缺失时编译失败；之后修正测试夹具JdbcTemplate重载推断错误。两者均保留日志，不称为已复现生产缺陷。最终71项来自根代理同一完整运行，不拼接子代理未执行的结果。
- 独立只读 `/root/source_boundary_review` 对移动前后、入口、错误适配及71项日志审核，未发现阻断；增量风险低。无依赖变更，依赖审计不属于本片。

逐类结果、源码与日志SHA256见 [机器证据](source-boundary-test-results.json)。固定formatter检查13文件，10修改、3已符合；均属本片。提交/推送精确tree结论以正常门禁生成报告为准。

## 风险与回退

本片测试为H2及受控外部替身；未重跑MySQL/PostgreSQL、真实角色浏览器、异步重启、并发导出、真实模型或正式QA。没有UI变更，前端是否适用由固定影响规则决定，不能称本片浏览器已验收。无迁移、依赖或门禁改动。正常提交钩子发现独立 WebChat 测试文件积累问题，按[隔离验收](WEBCHAT_ATTACHMENT_ISOLATION_ACCEPTANCE.md)修复测试存储；这是经独立审核的测试配置增量，未修改生产行为或降低断言。回退可revert此片，数据库/发布字节不需改写；后续新增来源类型必须同时接入策略及回归。售前应用服务仍有命令、发布、文件读取和整聚合持久化职责待拆分。
