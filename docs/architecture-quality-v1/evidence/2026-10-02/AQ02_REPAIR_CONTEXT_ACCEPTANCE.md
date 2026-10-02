# AQ-02 安全修复读取工程验收

基于 HEAD `21c5a68413bafcc3bcbf74b3f1844f292aff6c9d`，增量基线 `origin/dev`。计划见 [AQ02_REPAIR_CONTEXT_PLAN.md](AQ02_REPAIR_CONTEXT_PLAN.md)。本次为工程切片，正式 AC-07/08/09 保留 NOT_RUN。

## 行为与边界

完整项目读取仍对撤销的来源返回 403。新增 member 修复读取仅返回显式元数据、空来源集合和不透明绑定 ID/闭合角色枚举。旧材料和员工失效时，仅严格形状的绑定、解绑和 agentId-only 员工替换可绕过旧来源复核；新目标权限、成员权限、Workspace、CAS、归档和不可变回执继续复核。未知旧角色归一 UNKNOWN，避免名称泄露。UI 只在实际 403 且成员有写权限时读取修复契约，拒绝额外字段、非空来源和过期响应；归档、普通编辑、生成和批准仍禁用。

Controller 只委派服务；无新增依赖、迁移或门禁变更。回退为撤回本切片，恢复旧 403 行为；回执与数据库结构不变。

## 验证证据

- Java：`JAVA_HOME=<本机 Temurin 21> mvn -pl mateclaw-server -am -Dtest=PresalesIntegrationTest,PresalesSourceScopeTest,PresalesRuntimeTransactionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，退出 0，26 tests，0 failure/error/skip。H2、真实 HTTP/JWT/成员边界和 Spring service/mapper；Wiki/模型外发使用替身。
- UI（mateclaw-ui）：`pnpm exec vitest run src/features/presales/__tests__`，退出 0，35/35；`pnpm exec vue-tsc --noEmit` 退出 0。
- `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`：SCAN_PASS，报告 `mateclaw-quality-b2_yxr78`；工具链未由此执行。
- 保存修复前角色泄露失败和 UI 失败，见 [机器结果与日志摘要](repair-context-test-results.json)。所有日志保留原文 SHA256。
- 独立安全审阅修复后无阻断项；此前归档操作、修复 DTO 与未知角色泄露均已修复并回归。审阅不是正式 QA 或合并授权。
- 实际 Chrome/Vue/HTTP 客户端的模拟 API 流程通过：403 刷新、解绑、员工替换；[浏览器覆盖台账](repair-context-browser/coverage.json)为 INCOMPLETE（10 PASS、6 NOT_RUN、errors=[]），保留截图、流与失败夹具日志。

## 未测与风险

真实身份浏览器全流程、完整角色矩阵、生产同构数据库/全部方言、异步重启、并发历史导出和真实模型质量仍 NOT_RUN。浏览器模拟恢复不证明真实后端业务验收。售前聚合职责拆分与正式 QA 仍待后续推进。提交/推送精确树门禁以工具生成报告为准，本文不自签 PASS。
