# RESET：旧本体测试数据清理与 OWL 夹具重建

状态：执行规格，**本轮未连接数据库、未删除数据**。授权依据：用户允许旧本体数据清掉、重建测试用例；实际执行仅限明确识别的旧测试本体及专属依赖。执行阶段 OWL-02，先通过新 schema/导入/fixture 测试。

## 必填清单与停止条件

执行者从实际运行配置解析脱敏 datasource 标识、数据库类型、schema、工作区和连接目标；不能拿文档历史端口或 M6 截图当当前目标。生成 `reset-manifest.json`，包含 `runId, baselineCommit, datasourceFingerprint, workspaceId, ontologyIds, revisionIds, graphIds, expectedCountsByTable, retainedCountsAndDigests, backupPath, backupDigest, writerFence, plannedActions`。不记录密码、token 或完整连接密钥。不得默认枚举全库本体作为删除集合。

白名单来自已确认测试场景及逐条回读的所有权和依赖。发现非测试、跨工作区、共享引用、计数漂移、活跃 writer 或无法恢复的备份，暂停相应删除步骤并报告对象；不能扩大范围。无需再次请求已明确测试数据范围的授权。共享资源保留；不能证明独占则不删除。

## 执行顺序

1. 对目标闭包生成只读 dry-run，列出每表精确主键和预计数量，同时记录保留资料/配置的数量与摘要。外键之外也查 JSON payload 和逻辑引用，特别是 command result、extraction payload、governance detail。
2. 创建可恢复的目标记录导出、关联顺序和原 schema/应用版本记录；验证导出摘要及隔离恢复演练。备份文件留在受控路径；报告仅给路径和摘要，不贴原始业务文本。恢复必须使用匹配的旧应用/schema或明确恢复脚本，不能把旧 JSON 灌入新列。
3. 关闭目标语义写入口，取消/等待相关 extraction task，建立 generation/lease fencing，并阻止提交 receipt/review/save/publish。仅前端停按钮不足。核查其他实例无租约，重新读取 mutationVersion 和计数；不同则重做 dry-run。
4. DML 在可回滚事务中按下面依赖顺序删除 **manifest 明确的主键集合**。每一步断言受影响行数；任一步不符则事务回滚。DDL 与删除事务分开：MySQL 等 DDL 不能假设原子回滚，采用可恢复的 expand→验证→retire 维护窗口。
5. 用标准入口创建新 OWL 质量与库存本体、固定 imports、发布新版本，再建立新测试图和带证据的审核事实。新测试数据有独立 fixtureRunId/来源标记和清单。允许实测超差；来源文档保留，可重新捕获图内快照。
6. 回读删除集合无残留、引用完整、新文档可导出再导入且结构相同、图版本绑定正确、原资料/配置摘要不变；检查 command 重放不会返回旧 revision。恢复写入并验证一次新图查询/受控草稿保存。保留备份和审计报告。

## 实际表的依赖顺序（来自 V191–V198）

| 顺序 | 表或范围 | 选择依据与保护 |
|---|---|---|
| 1 | mate_semantic_extraction_receipt / submission_intent / action / edit / suggestion / attempt | graphId 与 taskId/suggestionId 闭包；先阻断 worker；edit 没有 graphId，不能误删其他任务 |
| 2 | mate_semantic_extraction_task | 已 fence 的目标 graphId；共享 extraction_gate 和全局任务机制保留 |
| 3 | mate_semantic_change_proposal / conflict / governance_event / mutation_command | 目标 graphId 和资源 ID；检查 conflict/command JSON 内引用 |
| 4 | mate_semantic_revision_evidence → statement_revision → statement | 精确 statementId/revision/evidenceId 链；所有历史修订都在本次测试闭包内 |
| 5 | mate_semantic_snapshot_exclusion / source_governance / import_job | 仅目标图的快照捕获和图级来源治理；不删除 KB 原资料 |
| 6 | mate_semantic_evidence → source_snapshot | 无其他引用且独占；共享者保留并从删除集合移出 |
| 7 | mate_semantic_entity → graph | 指定图；包含 KB 绑定但不删除知识库本身 |
| 8 | mate_semantic_governance_record / command_record | ontologyId/revisionId/resourceId 与工作区联合过滤；command_record 没有 ontologyId，必须解析确认 resourceId/结果引用；不删工作区所有命令 |
| 9 | mate_semantic_ontology_revision → ontology | 所有图及其他引用已解除；仅 manifest 中 ID；published 旧测试历史随明确测试清理删除 |
| 10 | 本体相关派生缓存、索引和临时制品 | 由精确 ontology/revision/graph 前缀清除；不清全局缓存或其他图 |

V194/V198 工具注册和员工配置不属于可删数据；新工具 schema 通过版本升级更新，不靠清空配置重建。后续 schema 新增的表需追加到 manifest 闭包，不可机械沿用本表顺序。

禁止 `DROP DATABASE`、整表 `TRUNCATE`、无白名单全表 DELETE、删除 Flyway history、修改已执行迁移。保留 WIKI_RAW/原知识库内容、用户/权限/工作区、模型/员工/技能配置、聊天及无关业务数据。全局治理 gate/注册记录保留。

## 验收与恢复

- RESET-DRY：仅计划无删除，主键数量与目标引用闭包一致；跨图共享引用仍存在。
- RESET-RACE：dry-run 后插入一个新任务或改 mutationVersion；实际删除拒绝并回滚。
- RESET-SCOPE：目标测试图删除；同 KB 原文、另一图、Agent 权限/配置及聊天的保留摘要一致。
- RESET-ROLLBACK：事务中间注入异常，全部目标记录恢复；DDL 阶段失败按备份和匹配版本恢复演练记录处理。
- RESET-REBUILD：新 OWL v1 可读回，复杂公理/注释保留；新审核事实引用有效来源；observedValue 超差仍可提交并审核。
- RESET-IDEMPOTENT：同 runId 重试不会扩大 ID 集合或删除后来创建的新数据；清单状态、已执行动作和摘要可审计。

本次仅核实迁移文件中的表和关系；未确认任何实际 datasource/测试 ID 白名单，故没有生成可直接执行的 DELETE SQL。
