# B1：业务协议与可恢复建模基础

状态：已实施，范围内测试通过。

基线：19fa7558。范围对应生命周期计划 T01、T02。

## 业务命令

现有 `/draft/model-edits` 保留原响应；新增 `/draft/model-commands` 返回草稿和逐项结果。业务操作仍由同一 OWL 映射器生成权威文档。

操作可携带 `clientId`，其他操作通过 `$clientId` 引用本批次新建类型，允许前向引用。服务端生成稳定标识；请求重放返回已保存的结果。客户端不负责生成 IRI。

每项结果包含稳定目标标识和最终定义标识，用于后续依据绑定。名称修改不改变目标标识。版本不符、无效引用或不支持的操作使整个批次失败。

## 资料依据

新增 `/ontologies/{ontologyId}/source-evidence/resolve`，输入资料、预期摘要和原文摘录。服务端计算 Unicode 码点位置；同一摘录出现多次时必须提供从 1 开始的 occurrence。解析结果不是永久授权，应用建议时重新校验来源并持有资料行锁。

模型应用与来源绑定使用同一数据库事务；绑定失败必须回滚模型写入。资料来源保留原件和现有快照机制，不建立另一套资料主库。

## 交付边界

本批交付后端基础与接口，不表示真实智能体自然语言建模、建议确认页面、草稿推理或完整发布门禁已经完成。这些依次属于 B2、B3。具体实体样例仅保存在建议中，不写入语义图正式事实。

操作没有最终保留的定义时，暂不允许把资料绑定到该操作；系统明确拒绝并回滚，建议仍可查看。

## 建模任务

`/api/v1/semantic/modeling-tasks` 支持创建和查询，`/{id}` 回读任务，`/{id}/proposals` 保存建议，`/{id}/proposals/{proposalId}/decision` 接受或拒绝建议，`/{id}/stage` 记录运行、失败、恢复或取消。

任务固定目标草稿、用户目标和资料版本；建议保存业务操作、依据、问题回答及隔离样例。任务详情会重新检查来源和草稿并持久化 STALE；任务列表提供已保存快照，避免跨任务持锁。取消是终态，失败可以恢复。接受建议后的重复请求返回已提交结果，读取仍核对当前权限。

新增 V208 迁移保存任务及操作结果，沿用现有模型、来源快照及命令事务，不新增模型主库或运行依赖。创建通过唯一请求占位避免并发重试重复创建本体。

## 验证

- 后端联合回归 40 项通过：业务命令 3、任务 5、并发及权限 2、迁移方言 3、既有本体 15、来源 8、既有建模工具 4。
- 应用上下文重建后的恢复测试 2 项通过：从数据库回读，丢失响应后重试不再修改草稿。
- 来源绑定注入故障后，模型版本、绑定和建议状态均回滚；相同请求可成功重试。
- 前端本体测试 20 文件、106 项通过；vue-tsc 和修改文件 ESLint 通过。
- 独立代码审查未发现阻断问题，git diff --check 通过。
- JDK 21 后端 Maven package 构建通过；未重启用户当前运行实例。

可复跑：使用 JDK 21，运行 `mvn -pl mateclaw-server -am -Dtest=OntologyModelCommandIntegrationTest,OntologyModelingIntegrationTest,OntologyModelingConcurrencyIntegrationTest,OntologyModelingRecoveryIntegrationTest,OntologyModelingSchemaTest,SemanticOntologyIntegrationTest,OntologySourceReviewIntegrationTest,OntologyAuthoringIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

验证限制：MySQL、Kingbase 为 H2 方言兼容测试，没有连接原生数据库；恢复测试重建 Spring 应用上下文并保留数据库，不等同于生产进程/磁盘故障演练。B1 没有新增页面，未重复全量浏览器验收。真实模型、资料上传导引及确认页面留待 B2。
