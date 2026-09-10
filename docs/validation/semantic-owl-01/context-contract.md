# CTX-01：普通 Agent 的本体上下文契约

状态：OWL-01 契约，2026-09-09 补充可选推理接入。当前完成证据见 `../semantic-owl-execution/current-acceptance.md`；本契约本身不是验收通过声明。

## 入口与权限

新增只读工具 `semantic_context(graphId, question, entityIris?, asOf?, budget?)`，server 的 SemanticContextService 复用 SemanticQueryService 的事实可见性、证据回读和 Agent/KB 权限检查。Principal 与 workspace 从受信 ToolContext 获取，不接受模型传入授权范围。只取图绑定的已发布 revision，不能自动选择最新草稿或最新发布版。用户与 Agent 的 KB 授权取交集，每次请求及后续分页重新校验。禁用图、授权撤销、失效证据均不泄漏缓存内容。

服务端步骤：授权 → 固定 graphMutationVersion/ontologyRevision/importLock/policy → 术语/IRI 检索 → 相关公理及必要依赖扩展 → 当前有效已审核事实及证据 → 可选同快照推理结果 → 预算截断。分页 cursor 签名绑定工作区、用户/Agent 授权摘要、图、版本、query 和有效期；版本变化返回 CONTEXT_STALE，不能拼接不同版本。

## 返回契约

```typescript
interface SemanticContextV1 {
  schema: 'semantic-context-v1'
  traceId: string
  graphId: string
  graphMutationVersion: number
  ontology: { ontologyId: string; revisionId: string; version: number;
    documentDigest: string; importLockDigest: string; policyVersion: string }
  snapshot: { asOf: string | null; validityPolicy: string; capturedAt: string }
  terms: Array<{ iri: string; kinds: string[]; labels: string[];
    definition: string | null; axiomIds: string[] }>
  axioms: Array<{ id: string; originRevisionId: string; kind: string;
    functionalSyntax: string; signature: string[];
    origin: 'ONTOLOGY_ASSERTION'; sourceBindingIds: string[] }>
  facts: Array<{ statementId: string; revision: number;
    assertion: string; status: 'ACCEPTED'; evidenceIds: string[];
    validity: { kind: string; from: string | null; to: string | null } }>
  evidence: Array<{ id: string; snapshotId: string; digest: string;
    exactQuote: string; startCodePoint: number; endCodePoint: number }>
  reasoning: { scope: 'BUSINESS_PROJECTION' | 'TBOX_ONLY' | 'ONTOLOGY_ABOX' |
      'ACCEPTED_FACTS' | 'ONTOLOGY_ABOX_AND_ACCEPTED_FACTS';
    engine: string | null; engineVersion: string | null;
    status: 'NOT_RUN' | 'COMPLETE_FOR_REQUEST' | 'INCONSISTENT' |
      'TIMEOUT' | 'RESOURCE_LIMIT' | 'UNSUPPORTED' | 'STALE' |
      'PARSE_ERROR' | 'PROFILE_VIOLATION' | 'FAILED';
    task: string; supportedDatatypes: string[];
    outcome: string | null; inputDigest: string | null; explanationStatus: 'UNAVAILABLE';
    conclusions: Array<{ assertion: string; origin: 'INFERRED';
      premiseAxiomIds: string[]; premiseFactRevisions: string[];
      explanationStatus: 'AVAILABLE' | 'UNAVAILABLE' }> }
  coverage: { truncated: boolean; omittedAxioms: number | null;
    omittedFacts: number | null; omittedConclusions: number | null; reason: string | null;
    usedTokens: number; tokenEstimate: boolean;
    dependencyClosureComplete: boolean; nextCursor: string | null }
  warnings: string[]
}
```

ID 一律字符串，不把雪花 ID 转 JS number。来源字段中的工具检索引用必须可在相同授权范围下回读。解释引擎若无法给出最小证明，返回 UNAVAILABLE + 输入快照，不编造 premise 列表。

建议默认预算：上下文 6000 tokens，上限 12000；先术语、公理、事实与必要证据，再扩展。这是待 VAL-01 校准的产品参数，非性能承诺。按实际 tokenizer 统计或明确 estimate。只在完整枚举后填 omitted 数量，否则 null，不能用 0 假装无遗漏。完整原公理由分页/IRI 查询取得；切片缺依赖时 dependencyClosureComplete=false。

LLM 获得的是运行时检索上下文，不是训练后永久掌握领域知识。前端和提示词要求区分“资料/事实说明”“OWL 可推出”“缺少证据的假设”。矛盾本体不启用爆炸式蕴含输出；超时/不支持不输出否定答案。温度与测量误差有关的定义不能独自证明具体超差由温度造成。

## 验收场景

| ID | 检验 |
|---|---|
| CTX-AUTH | 用户可读、Agent 无 KB 权限；跨工作区；已撤销授权命中缓存；均不泄漏 |
| CTX-VERSION | 图绑定 v1、存在 v2/草稿；只返回 v1；并发修改后分页明确过期 |
| CTX-TRACE | 公理、statement revision、证据引用逐一回读一致；来源区间按 Unicode code point |
| CTX-BUDGET | 大型依赖闭包超预算：截断标记、实际遗漏未知、可继续取回；不谎称完整 |
| CTX-INFERENCE | 原断言和推导分开；timeout/unsupported/inconsistent 各返回不同状态 |
| CTX-ABOX | 导入 ABox 可读为 ontology assertion；默认不混进业务已审核事实或 BUSINESS_PROJECTION |
| CTX-TIME | UNKNOWN validity 不假装当前有效；互斥时间窗口事实不混入同一推理快照 |
| CTX-WRITE | 普通 Agent 无 create/save/publish 工具；上下文请求本身无写操作 |

VAL-01 对照：同模型、温度/seed（若支持）、资料与题目，A=资料检索，B=资料+自然语言领域摘要，C=资料+本契约。至少重复 3 次，人工盲评领域概念准确率、证据引用正确率、无依据原因断言率、缺信息追问、tokens/p50/p95。以质量 12 题（设备/测针/程序/环境/校准/超差反例）及库存 6 题验证通用性。C 证据与版本错误必须为 0，禁止比 A/B 更多的无依据根因断言；若成本增加而正确性没有改善，先调检索/切片，不靠扩大本体包装解决。题目、预期和评分标准在运行前冻结；人工复核全部差异。


## 可选推理调用与续页（2026-09-09）

`semantic_context` 新增可选 `reasoning` 对象：`{scope?, task, individualIri?, axiomFunctionalSyntax?}`。task 与独立推理工具共用 CONSISTENCY / CLASSIFICATION / INSTANCE_TYPES / AXIOM_ENTAILMENT。scope 省略时采用 ACCEPTED_FACTS（TBox + 当前有效已审核事实，排除本体 ABox）；另外三种作用域必须显式指定。整个对象在续页时必须原样保留。

默认不提供该对象时仍返回 NOT_RUN，不启动 worker。提供时采用短事务读取、事务外 worker、短事务复核；推理使用固定 asOf、revision、document/import digest 和图版本。推理覆盖选定作用域的完整输入，而问题匹配只裁剪返回的上下文。每页额外复核完整推理输入（包括未出现在问题切片中的事实证据）。

结果的 status 表示执行状态，outcome 保留原始 CONSISTENT / ENTAILED / NOT_ENTAILED 等结果；COMPLETE_FOR_REQUEST 不等于所有命题为真。失败、超时或矛盾不输出结论。结论以 INFERRED 单独分页，计入序列化预算并报告 omittedConclusions；不写入业务事实。inputDigest 标识 worker 输入，连同 ontology 和 snapshot 定位本次输入；当前没有最小证明，前提列表为空且 explanationStatus=UNAVAILABLE。supportedDatatypes 为空表示本接口未枚举能力，不能据此推断引擎不支持 datatype；具体不支持由 UNSUPPORTED 返回。

推理续页在进程内保留最多 64 个结果，每个序列化状态上限 256 KiB，有效期 10 分钟。容量淘汰、过期或服务重启后的旧 cursor 返回 CONTEXT_STALE，要求重新开始，不会悄悄重新推理。每页都重新授权和校验签名查询、输入快照；缓存不构成授权。超出单结果容量返回 CONTEXT_REASONING_LIMIT，单条内容无法进入预算返回 CONTEXT_ITEM_EXCEEDS_BUDGET。

## 本体级注释（2026-09-09）

返回新增独立 `ontologyAnnotations` 数组，元素包含 `originRevisionId`、可空 `artifactId` 和保留嵌套结构的 `annotation`（propertyIri / valueRendering / nestedAnnotations）。本体级注释与公理、事实和推导分开，作为独立项目参与预算与同快照分页。`coverage.omittedOntologyAnnotations` 表示尚未返回的注释数量；不得将其计为遗漏推理结论。根本体与锁定导入均收集注释，导入用 artifactId 定位。注释不是指令或逻辑公理。

## 标准本体身份（2026-09-09）

`ontology` 新增 `ontologyIri` 和可空 `versionIri`，直接取图固定的已发布文档解析结果。内部 ontologyId/revisionId 保留用于平台定位，不充当标准 IRI。文档未声明 versionIRI 时返回 null；发布新版本不自动改变图固定的身份。两个字段纳入响应预算计算。
