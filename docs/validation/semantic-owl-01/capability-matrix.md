# OWL-01 标准能力矩阵

状态：规格，不是测试通过清单。基线 `fa8217cd` 不具备这些完整 OWL 承载路径。逐行数据及测试 ID 见 [JSON](capability-matrix.json)。构造名用于对照标准；示例为带统一声明上下文的片段，不是每段都能独立加载的文档。

## 七列路径及失败行为

| 代码 | 拟实现路径 | 失败行为 |
|---|---|---|
| P1 解析 | semantic-owl/OwlDocumentAdapter，标准库读 RDF/XML、Functional | PARSE_ERROR + 行列；不回退旧 JSON；未知构造不忽略 |
| S1 保存 | OntologyApplicationService → 完整文档 + imports lock + digest | CAS_CONFLICT / DOCUMENT_TOO_LARGE；事务不产生半个版本 |
| E1 编辑 | OntologyEditor 完整文本；简单表单发公理变更 | INVALID_AXIOM_EDIT；保留未编辑公理与注释，版本不符拒绝 |
| X1 导出 | 标准库写 RDF/XML/Functional 后重新解析对照结构 | ROUND_TRIP_LOSS 阻断导出验收；禁止只比较计数 |
| V1 校验 | parse → imports → OWL2DL profile → 单独的逻辑/业务结果 | OUTSIDE_OWL2_DL / IMPORT_UNRESOLVED；草稿保留校验错误，发布阻断 |
| R1 推理 | 单独进程的 ReasoningPort，固定闭包、任务、datatype 能力 | UNSUPPORTED/TIMEOUT/RESOURCE_LIMIT/INCONSISTENT 均不是 false |
| R0 非逻辑 | 保存/展示；不提供新增逻辑蕴含 | 不把声明、注释当业务事实或推理规则 |
| A1 AI 使用 | published revision 公理查询 + task slice + 签名/来源 | AUTH_DENIED 或明确 TRUNCATED；不得把截断切片称为完整本体 |

所有行 P/S/E/X/V/A 均计划全覆盖，R1 逐项声明能力并实测；未通过项阻止“完整推理”宣传。P/N 是正常/反例，RT 是交换往返，EDIT 是修改一个公理其余结构保持，CTX 是原公理可追溯取回。JSON 中每行包含七条状态，不将 `planned` 计为 `passed`。

## 构造目录

| ID | 分组 | 构造 | 测试前缀 |
|---|---|---|---|
| OWL-001 | 身份与文档 | `OntologyIRI` | OWL-001-P/N/RT/EDIT/CTX |
| OWL-002 | 身份与文档 | `VersionIRI` | OWL-002-P/N/RT/EDIT/CTX |
| OWL-003 | 身份与文档 | `Import` | OWL-003-P/N/RT/EDIT/CTX |
| OWL-004 | 身份与文档 | `Prefix` | OWL-004-P/N/RT/EDIT/CTX |
| OWL-005 | 身份与文档 | `DeclarationClass` | OWL-005-P/N/RT/EDIT/CTX |
| OWL-006 | 身份与文档 | `DeclarationDatatype` | OWL-006-P/N/RT/EDIT/CTX |
| OWL-007 | 身份与文档 | `DeclarationObjectProperty` | OWL-007-P/N/RT/EDIT/CTX |
| OWL-008 | 身份与文档 | `DeclarationDataProperty` | OWL-008-P/N/RT/EDIT/CTX |
| OWL-009 | 身份与文档 | `DeclarationAnnotationProperty` | OWL-009-P/N/RT/EDIT/CTX |
| OWL-010 | 身份与文档 | `DeclarationNamedIndividual` | OWL-010-P/N/RT/EDIT/CTX |
| OWL-011 | 身份与文档 | `AnonymousIndividual` | OWL-011-P/N/RT/EDIT/CTX |
| OWL-012 | 身份与文档 | `Punning` | OWL-012-P/N/RT/EDIT/CTX |
| OWL-013 | 身份与文档 | `TypedLiteral` | OWL-013-P/N/RT/EDIT/CTX |
| OWL-014 | 身份与文档 | `LanguageLiteral` | OWL-014-P/N/RT/EDIT/CTX |
| OWL-015 | 类表达式 | `ObjectIntersectionOf` | OWL-015-P/N/RT/EDIT/CTX |
| OWL-016 | 类表达式 | `ObjectUnionOf` | OWL-016-P/N/RT/EDIT/CTX |
| OWL-017 | 类表达式 | `ObjectComplementOf` | OWL-017-P/N/RT/EDIT/CTX |
| OWL-018 | 类表达式 | `ObjectOneOf` | OWL-018-P/N/RT/EDIT/CTX |
| OWL-019 | 类表达式 | `ObjectSomeValuesFrom` | OWL-019-P/N/RT/EDIT/CTX |
| OWL-020 | 类表达式 | `ObjectAllValuesFrom` | OWL-020-P/N/RT/EDIT/CTX |
| OWL-021 | 类表达式 | `ObjectHasValue` | OWL-021-P/N/RT/EDIT/CTX |
| OWL-022 | 类表达式 | `ObjectHasSelf` | OWL-022-P/N/RT/EDIT/CTX |
| OWL-023 | 类表达式 | `ObjectMinCardinality` | OWL-023-P/N/RT/EDIT/CTX |
| OWL-024 | 类表达式 | `ObjectMaxCardinality` | OWL-024-P/N/RT/EDIT/CTX |
| OWL-025 | 类表达式 | `ObjectExactCardinality` | OWL-025-P/N/RT/EDIT/CTX |
| OWL-026 | 类表达式 | `DataSomeValuesFrom` | OWL-026-P/N/RT/EDIT/CTX |
| OWL-027 | 类表达式 | `DataAllValuesFrom` | OWL-027-P/N/RT/EDIT/CTX |
| OWL-028 | 类表达式 | `DataHasValue` | OWL-028-P/N/RT/EDIT/CTX |
| OWL-029 | 类表达式 | `DataMinCardinality` | OWL-029-P/N/RT/EDIT/CTX |
| OWL-030 | 类表达式 | `DataMaxCardinality` | OWL-030-P/N/RT/EDIT/CTX |
| OWL-031 | 类表达式 | `DataExactCardinality` | OWL-031-P/N/RT/EDIT/CTX |
| OWL-032 | 数据范围 | `DatatypeReference` | OWL-032-P/N/RT/EDIT/CTX |
| OWL-033 | 数据范围 | `DataIntersectionOf` | OWL-033-P/N/RT/EDIT/CTX |
| OWL-034 | 数据范围 | `DataUnionOf` | OWL-034-P/N/RT/EDIT/CTX |
| OWL-035 | 数据范围 | `DataComplementOf` | OWL-035-P/N/RT/EDIT/CTX |
| OWL-036 | 数据范围 | `DataOneOf` | OWL-036-P/N/RT/EDIT/CTX |
| OWL-037 | 数据范围 | `DatatypeRestriction` | OWL-037-P/N/RT/EDIT/CTX |
| OWL-038 | 类公理 | `SubClassOf` | OWL-038-P/N/RT/EDIT/CTX |
| OWL-039 | 类公理 | `EquivalentClasses` | OWL-039-P/N/RT/EDIT/CTX |
| OWL-040 | 类公理 | `DisjointClasses` | OWL-040-P/N/RT/EDIT/CTX |
| OWL-041 | 类公理 | `DisjointUnion` | OWL-041-P/N/RT/EDIT/CTX |
| OWL-042 | 对象属性 | `ObjectInverseOf` | OWL-042-P/N/RT/EDIT/CTX |
| OWL-043 | 对象属性 | `SubObjectPropertyOf` | OWL-043-P/N/RT/EDIT/CTX |
| OWL-044 | 对象属性 | `ObjectPropertyChain` | OWL-044-P/N/RT/EDIT/CTX |
| OWL-045 | 对象属性 | `EquivalentObjectProperties` | OWL-045-P/N/RT/EDIT/CTX |
| OWL-046 | 对象属性 | `DisjointObjectProperties` | OWL-046-P/N/RT/EDIT/CTX |
| OWL-047 | 对象属性 | `InverseObjectProperties` | OWL-047-P/N/RT/EDIT/CTX |
| OWL-048 | 对象属性 | `ObjectPropertyDomain` | OWL-048-P/N/RT/EDIT/CTX |
| OWL-049 | 对象属性 | `ObjectPropertyRange` | OWL-049-P/N/RT/EDIT/CTX |
| OWL-050 | 对象属性 | `FunctionalObjectProperty` | OWL-050-P/N/RT/EDIT/CTX |
| OWL-051 | 对象属性 | `InverseFunctionalObjectProperty` | OWL-051-P/N/RT/EDIT/CTX |
| OWL-052 | 对象属性 | `ReflexiveObjectProperty` | OWL-052-P/N/RT/EDIT/CTX |
| OWL-053 | 对象属性 | `IrreflexiveObjectProperty` | OWL-053-P/N/RT/EDIT/CTX |
| OWL-054 | 对象属性 | `SymmetricObjectProperty` | OWL-054-P/N/RT/EDIT/CTX |
| OWL-055 | 对象属性 | `AsymmetricObjectProperty` | OWL-055-P/N/RT/EDIT/CTX |
| OWL-056 | 对象属性 | `TransitiveObjectProperty` | OWL-056-P/N/RT/EDIT/CTX |
| OWL-057 | 数据属性 | `SubDataPropertyOf` | OWL-057-P/N/RT/EDIT/CTX |
| OWL-058 | 数据属性 | `EquivalentDataProperties` | OWL-058-P/N/RT/EDIT/CTX |
| OWL-059 | 数据属性 | `DisjointDataProperties` | OWL-059-P/N/RT/EDIT/CTX |
| OWL-060 | 数据属性 | `DataPropertyDomain` | OWL-060-P/N/RT/EDIT/CTX |
| OWL-061 | 数据属性 | `DataPropertyRange` | OWL-061-P/N/RT/EDIT/CTX |
| OWL-062 | 数据属性 | `FunctionalDataProperty` | OWL-062-P/N/RT/EDIT/CTX |
| OWL-063 | 数据属性 | `DatatypeDefinition` | OWL-063-P/N/RT/EDIT/CTX |
| OWL-064 | 数据属性 | `HasKey` | OWL-064-P/N/RT/EDIT/CTX |
| OWL-065 | 个体断言 | `SameIndividual` | OWL-065-P/N/RT/EDIT/CTX |
| OWL-066 | 个体断言 | `DifferentIndividuals` | OWL-066-P/N/RT/EDIT/CTX |
| OWL-067 | 个体断言 | `ClassAssertion` | OWL-067-P/N/RT/EDIT/CTX |
| OWL-068 | 个体断言 | `ObjectPropertyAssertion` | OWL-068-P/N/RT/EDIT/CTX |
| OWL-069 | 个体断言 | `NegativeObjectPropertyAssertion` | OWL-069-P/N/RT/EDIT/CTX |
| OWL-070 | 个体断言 | `DataPropertyAssertion` | OWL-070-P/N/RT/EDIT/CTX |
| OWL-071 | 个体断言 | `NegativeDataPropertyAssertion` | OWL-071-P/N/RT/EDIT/CTX |
| OWL-072 | 注释 | `OntologyAnnotation` | OWL-072-P/N/RT/EDIT/CTX |
| OWL-073 | 注释 | `AxiomAnnotation` | OWL-073-P/N/RT/EDIT/CTX |
| OWL-074 | 注释 | `NestedAnnotation` | OWL-074-P/N/RT/EDIT/CTX |
| OWL-075 | 注释 | `AnnotationAssertion` | OWL-075-P/N/RT/EDIT/CTX |
| OWL-076 | 注释 | `SubAnnotationPropertyOf` | OWL-076-P/N/RT/EDIT/CTX |
| OWL-077 | 注释 | `AnnotationPropertyDomain` | OWL-077-P/N/RT/EDIT/CTX |
| OWL-078 | 注释 | `AnnotationPropertyRange` | OWL-078-P/N/RT/EDIT/CTX |
| OWL-079 | 全局与交换边界 | `SimpleRoleRestriction` | OWL-079-P/N/RT/EDIT/CTX |
| OWL-080 | 全局与交换边界 | `RegularRoleHierarchy` | OWL-080-P/N/RT/EDIT/CTX |
| OWL-081 | 全局与交换边界 | `ReservedVocabulary` | OWL-081-P/N/RT/EDIT/CTX |
| OWL-082 | 全局与交换边界 | `DeclarationClosure` | OWL-082-P/N/RT/EDIT/CTX |
| OWL-083 | 全局与交换边界 | `DatatypeMap` | OWL-083-P/N/RT/EDIT/CTX |
| OWL-084 | 全局与交换边界 | `RDFXMLMapping` | OWL-084-P/N/RT/EDIT/CTX |
| OWL-085 | 全局与交换边界 | `ImportClosure` | OWL-085-P/N/RT/EDIT/CTX |
| OWL-086 | 全局与交换边界 | `QualifiedAndUnqualifiedCardinality` | OWL-086-P/N/RT/EDIT/CTX |
| OWL-087 | 全局与交换边界 | `DatatypeArity` | OWL-087-P/N/RT/EDIT/CTX |

## 必须展开的参数维度

- 递归嵌套、n-ary 操作数、正反属性表达式、公理上的零/多/嵌套注释、匿名个体复用、空白与转义、Unicode IRI、语言标签及无标签字符串。
- 所有基数同时测试 0/1/2、无 filler 与 qualified filler；“只有一条已知边”不等于最大基数证据。“要求至少一个”不等于数据库必须已有一行。
- datatype map 分别覆盖 owl:real/rational、xsd:decimal/integer 及有符号/无符号派生整数、float/double、string 及派生字符串、boolean、hexBinary/base64Binary、anyURI、dateTime/dateTimeStamp、rdf:PlainLiteral/XMLLiteral；按规范及所选引擎文档区分必须支持与扩展，不凭 Java 字符串能保存就宣称推理支持。展开每个合法 facet/类型组合及非法组合、边界值、NaN/INF、时区、值等价、语言范围。实际清单在 OWL-02/03 依赖探针生成并审阅，当前未执行。
- 完整性不仅靠单构造：跨公理 simple role、regularity、声明/punning、import closure、datatype definition 循环与映射必须有组合反例。
- OWL Full/RDF 中无法映射回合法 DL 的图须明确拒绝发布；保留用户原导入文件供修复，不转换为较弱的成功结果。业务必填/单位策略与 OWL profile 是两套错误类型。

依据：[结构规范](https://www.w3.org/TR/owl2-syntax/)、[RDF 映射](https://www.w3.org/TR/owl2-mapping-to-rdf/)、[Direct Semantics](https://www.w3.org/TR/owl2-direct-semantics/)。这是工程覆盖索引，不能替代 W3C 测试集或库兼容性验收。
