# M5 实施审查及修复记录

审查对象：JDK-only 应用模块、宿主适配与任务存储、Vue 核对流程。保留原有语义 core 的事实与审核权威。

| 问题 | 修复 | 验证 |
|---|---|---|
| 编辑与提交预留竞争可能让旧内容被提交 | 建议编辑与 submission intent 使用同一数据库 gate；版本 CAS | SemanticExtractionLeaseIntegrationTest 编辑/预留竞争 |
| 应用检查后换绑可绕过旧修订校验 | 现有 propose 同事务获取图锁并重查修订 | SemanticExtractionSubmissionIntegrationTest binding race |
| 响应丢失后刷新生成新 operationId | 稳定建议+版本 operationId；服务端返回 pendingOperationId | suggestionRecovery.test.ts 重挂载重试；服务端 receipt gap |
| 模型不响应中断导致超时仍等待 | 有界 daemon 调用池+独立超时；无工具 | MateClawModelAdapterTest 不可中断替身 |
| 浏览器 PATCH 保存被跨域配置拒绝 | 现有 allowedMethods 增加 PATCH，来源规则不变 | WebMvcCorsTest 允许来源200/其他来源403；真实浏览器保存200 |
| 任务表单挤压核对内容、日期暴露原始精度 | 查看已有任务时折叠新任务表单；本地化日期 | 桌面截图 |
| 关联对象提示挤压选择框 | 提示独占一行 | 根因核对截图 |
| 深色原文高亮对比不足 | 高亮文字使用深色 | 深浅主题截图 |

真实模型失败没有通过放宽证据/本体/权限校验绕过：10份资料结果逐项保留于 model-evaluation.json。尚未验证 Kingbase 实机；模型精度需要后续积累业务样本持续评估。
