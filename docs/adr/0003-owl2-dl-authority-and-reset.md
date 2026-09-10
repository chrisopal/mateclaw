# ADR-0003：以完整 OWL 2 DL 文档替换轻量本体权威

日期：2026-09-08。状态：OWL-01 设计决定；尚未实施。基线：`fa8217cd`。
范围：承接用户确认的整替换方案，取代 ADR-0002 中旧格式兼容与轻量差异分类的相关决定。

## 决定

1. **唯一语义权威是版本化 OWL 文档。** 持久化经标准解析器处理的 Functional Syntax 文档；保留全部公理、声明、注释、匿名个体和 imports。RDF/XML 必须支持导入导出；同时支持 Functional Syntax。Turtle/OWL/XML 可以另加，但不是本轮完整性承诺的替代。导入原文件可作为不可变附件保存，不参与另一路语义写入。
2. 采用独立 `mateclaw-semantic-owl` 适配模块封装标准库。core 保留 JDK 类型的文档封套、身份、治理对象和接口，application 编排事务；不自行再造 OWL AST，不向这两个模块暴露第三方类型。server 装配适配器。具体候选见依赖报告，尚未添加依赖。
3. `OntologyDocument` 最小字段：`ontologyId, revisionId, ontologyIri, versionIri?, syntax=FUNCTIONAL, documentText, documentDigest, importLockDigest, modelSchema=owl-document-v1`。摘要是保存的 UTF-8 字节摘要，**不是语义等价哈希**。库升级不重写已发布文档。结构等价 round-trip 由标准库解析比较，允许顺序和前缀变动，不能仅比较字符串或总公理数。
4. `OntologyRevision` 继续拥有 draftVersion/CAS、发布操作者、发布说明和不可变历史。发布包同时固定文档、imports lock、业务策略版本、定义来源绑定和校验报告。列表、关系图、搜索索引均由该版本派生，禁止独立修改它们。缓存键必须包含工作区、修订、文档及 imports 摘要；包含权限范围的数据另加授权摘要。
5. 完整编辑入口是标准文档编辑器（可复用已有 Monaco）。简单表单提交“在 expectedDraftVersion 上添加/删除指定公理”的命令；服务端读取完整文档进行公理级变更，再重新解析校验。不能把表单可见子集序列化为完整文档。复杂公理可以只读结构预览，但必须可在文本入口编辑；不能声称所有复杂公理都有图形化编辑器。

## IRI、个体与公理身份

- IRI 是语义身份；显示名称和别名不是标识，不自动合并同名个体。生成身份使用 `urn:mateclaw:workspace:{workspaceId}:graph:{graphId}:individual:{entityId}`；外部 IRI 保留原值并限定授权图检索。同一个 IRI 出现在不同图不意味着跨图自动查询或授权。
- 允许多个显式类断言及推导类型；退役单一 `Entity.typeKey` 的权威地位。内部 EntityId 保留作治理引用。`sameAs` 形成有推导来源的语义等价结果，不能自动合并/删除内部实体、证据或历史。
- 公理标识为 `revisionId + axiomId`，axiomId 是解析后为本修订分配的不可变标识，不假设跨版本一致。存储索引含公理种类、标准库渲染、实体签名、精确定位与注释。M7/M8 的跨版本对应由显式 lineage 映射确认，不能靠自然语言摘要猜测。匿名节点保留文档范围身份，不转成全局业务实体。
- imports 采用闭包锁：每条含请求 IRI、解析出的 ontology/version IRI、内容摘要、本地制品 ID、依赖边。发布时完整闭包可得、无身份冲突、DL 合法；允许有界循环并用已访问集合终止。运行时只使用已锁制品，禁止自动抓取 latest。更新 import 是新草稿/新版本。网络抓取仅在受控导入流程执行并限制协议、地址、大小和递归深度；解析禁用外部 XML 实体。

## OWL 语义、业务事实、策略的边界

- OWL 文档可以完整包含 ABox；导入时保留而不把它写成已审核业务事实。文档公理来源标记 `ONTOLOGY_ASSERTION`，业务记录标记 `ACCEPTED_FACT`，推导标记 `INFERRED`。三个标签不互换。
- **完整本体推理**使用锁定的整个本体闭包，包括 ABox，输出只说明该本体的逻辑结果。**业务图推理**默认使用剔除 ABox 的显式派生 schema view + 当前授权图、时间窗口中已审核且证据有效的事实。输出明确 `scope=BUSINESS_PROJECTION`，不声称等于完整本体推理。需要文档 ABox 参与业务推理时，必须通过既有候选/审核流程转为带来源的业务事实；不隐式提升信任。
- 业务事实权威仍是 StatementRevision/审核链。增加受治理的 `ClassAssertion`、正负对象/数据断言、Same/Different 个体断言负载，predicate 使用 IRI；保持修订、证据、Validity、操作幂等和权限。不得另建一个可写 RDF 事实仓库旁路审核。复合匿名类断言可以保留公理负载，其签名与绑定个体由适配器校验。
- OWL 无唯一名称假设，缺失信息不等于否定，domain/range 主要用于类型推导。对象属性功能性可能推出个体相等；数据属性冲突按数据值语义判断。不能把原 `Multiplicity.SINGLE` 的业务冲突逻辑直接翻译成通用 OWL 拒绝规则。
- `BusinessPolicySet` 独立版本化：必填、录入格式、单位、业务唯一性、审核冲突与公差判定；不假装是 OWL 公理。允许以后 SHACL 适配，但不在 OWL-01 引入 SHACL 依赖。OWL profile 检查、逻辑一致性检查、数据策略检查各有独立结果。
- 质量样例：Measurement 保存 `observedValue=0.026 mm`，ToleranceSpecification 保存 `upperLimit=0.010 mm`，二者通过 evaluatedAgainst 关联。超差结果由显式比较策略得出。测量值不能被限定为“必须小于公差”而无法入库。测针磨损、温度漂移等是待验证原因，OWL 类型/关系不构成物理因果证据。

## 退役与保留

| 范围 | 处理 |
|---|---|
| core ontology/OntologyDefinition、EntityTypeDefinition、PropertyDefinition、RelationDefinition、旧约束/变更分类 | 替换为文档封套、适配接口和公理差异；删除旧格式定义与测试 |
| server OntologyDefinitionCodec、OntologyDtos.Definition、definitionFormatVersion、OntologyWireMapper | 删除旧 codec；更新全部读写和工具 schema；不实现 format1/2 兼容分支 |
| ontology application/repository/package/impact | 复用权限、CAS、发布和历史机制；替换 definition_json、包格式、差异及影响分析实现 |
| Entity.typeKey、PredicateRef 旧 key、StatementValidator 精确类型判断 | 多类断言、IRI 谓词、分离语义检查与业务策略；原证据/审核/时间和幂等机制保留 |
| authoring tool、ontology-builder skill、M5 model adapter | 使用新文档/公理提案；保留员工配置、来源权限及人工发布边界 |
| UI types.ts、ontologyApi.ts、useOntologyDraft、三个旧编辑表单、结构图 | 接新契约；复用布局、版本页、工作台、对话入口和控件；复杂公理显示“完整文档可编辑” |
| 旧 JSON fixtures/format 测试 | 重建 OWL fixtures；权限、并发、证据、审批等行为测试改夹具后继续存在 |
| 旧测试本体及专属数据 | OWL-02 RESET 白名单清理，保留原资料与无关数据；不迁移旧图 |

## 存储切换

增加一次新的 Flyway 迁移（实施时确认序号，当前 V198 之后）。新增 OWL 文档/import lock/公理来源索引和策略字段或表，三种数据库同步。旧 `definition_json` 在过渡 DDL 中可暂存但新应用不读取；限定 RESET 完成后再用下一迁移删旧列和废弃约束。DDL 的 expand/retire 顺序不是双读双写兼容方案。切换期间关闭语义写入，不能让旧/新服务同时写。

若发现范围外旧本体占用旧列，暂停删列/对应数据项并报告真实阻碍；不能为了满足整替换擅自删业务数据。新 OWL fixtures 验收前保留可恢复导出。已发布新 OWL 版本永不原位重写。

## 后续 M7/M8

定义来源绑定独立结构：`revisionId, axiomId, sourceSnapshotId, exactQuote, start/endCodePoint, digest, origin=EXTRACTED|EXPERT|INFERRED, reviewState`。不再从 description 字符串提取来源。M7 检测摘要变化，只产生复核候选，不改已发布公理。

M8 只处理新 OWL 修订间迁移：固定 from/to 修订及 imports/策略，先对非空图 dry-run，列出公理/IRI/策略变化、受影响事实、丢失或新增推导、未解决项；人工审核后以图 mutationVersion CAS 原子切换。历史事实仍保留原解释版本，回滚以新治理事件恢复绑定，不能改写历史或原地重编码。

## 依据与未验证项

设计依据：[OWL 2 结构规范](https://www.w3.org/TR/owl2-syntax/)、[Direct Semantics](https://www.w3.org/TR/owl2-direct-semantics/)、[Conformance](https://www.w3.org/TR/owl2-conformance/)。上述是本项目架构选择；尚无生产实现、语义 round-trip 或推理运行证据。
