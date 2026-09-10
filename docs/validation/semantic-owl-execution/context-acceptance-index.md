# CTX 八类场景逐项核对

来源：`../semantic-owl-01/context-contract.md`。此索引不将部分覆盖视为全量完成。

| 场景 | 当前直接证据 | 缺口或限制 |
|---|---|---|
| CTX-AUTH | pinnedVersionKeepsOntologyAboxSeparateAndRechecksAgentAuthorization；signedPaginationCannotMixGraphVersionsOrQuestions；revokedAgentDuringWorkerRejectsResult | KB 可见性部分采用 mock；实际 Agent 工具授权仍需完整清单验证 |
| CTX-VERSION | pinnedVersionKeepsOntologyAboxSeparateAndRechecksAgentAuthorization 新增发布 v2 后再开草稿，确认仍返回 v1；signedPaginationCannotMixGraphVersionsOrQuestions 检验并发变化后的旧 cursor | 新增发布场景 H2 与临时 MySQL 均通过（context-readonly-unicode.json） |
| CTX-TRACE | evidenceIsReadBackAndMutuallyExclusiveTimesNeverMix；OntologySourceReviewIntegrationTest 精确区间与授权读回 | 尚需 CTX 返回引用与来源工具逐项读回的一体化断言；现已加入 😀 并断言 code point 区间（H2/MySQL） |
| CTX-BUDGET | signedPaginationCannotMixGraphVersionsOrQuestions 120 公理完整分页、逐页预算、不重复；complexAxiomsSurviveOrdinaryAgentContextPaginationWithoutFlattening 保留嵌套语义 | 特定夹具不等于 87 行全部构造覆盖 |
| CTX-INFERENCE | ordinaryToolReturnsRealTransitiveInferenceWithoutPromotingItToFact 真实 HermiT；SemanticContextReasoningIntegrationTest 全状态、分页、缓存边界、撤权和输入变化 | 大部分异常采用 worker mock；未执行真实 LLM 实验 |
| CTX-ABOX | pinnedVersionKeepsOntologyAboxSeparateAndRechecksAgentAuthorization 与 complexAxiomsSurviveOrdinaryAgentContextPaginationWithoutFlattening 将 ABox 保持为 ONTOLOGY_ASSERTION，facts 为空 | BUSINESS_PROJECTION 的隔离证据需与推理作用域测试统一关联 |
| CTX-TIME | evidenceIsReadBackAndMutuallyExclusiveTimesNeverMix 验证边界时间只取当前区间，UNKNOWN 排除 | 推理快照时间隔离需补齐与实际 worker 输入的证据关联 |
| CTX-WRITE | SemanticContextTool 仅 semantic_context 与 semantic_context_source 两个回调 | 已修复默认工具继承导致的建模入口暴露，真实注解工具回调过滤及桥接测试 11/11 通过（context-tool-scope.json）；真实 Spring ToolRegistry 与数据库授权/撤销 34 项测试通过（context-real-tool-registry.json）；已编译对话的刷新边界仍待核实；真实推理上下文请求前后所有 mate_semantic_* 表逐行不变已通过 H2/MySQL。类内无写方法本身不足以证明整个 Agent 无写权限 |

本轮 H2 `SemanticContextIntegrationTest` 5/5 通过，见 `context-version-published.json`。上下文推理既有证据见 `context-inference-semantic-regression.json`、`context-inference-mysql.json` 和 `context-inference-expiry.json`。新增版本断言只改变测试，未修改生产运行包。

本体级注释：新增独立 ontologyAnnotations 页项目；根本体 12 条嵌套注释分页与遗漏数、锁定导入 artifactId/嵌套来源及读取无写入均有直接 H2 证据（context-ontology-annotations.json、context-import-annotations.json）。不是公理或事实。

逐构造发布上下文：OWL-005～078 的冻结正常示例共 74 项通过保存→发布→绑定→分页→完整公理/本体注释比对（context-matrix-published.json）。不等于全部参数维度或 OWL-001～004/079～087 已覆盖；新增参数化用例已通过临时 MySQL，整套 140/140（context-matrix-mysql.json）。

基数参数：36 种对象/数据、Min/Max/Exact、0/1/2、有无 filler 组合经发布上下文完整取回验证（context-cardinality-dimensions.json），未由存在约束生成业务事实；推理负例和全局属性限制仍独立验收。
