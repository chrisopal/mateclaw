# T12 支持范围与复杂语义边界

源码版本：`6272f669ab597f2855d117053b8d82bc1c3b014d`。以下是本轮源码核对，不能替代端到端或模型调用验收。设计依据 `docs/plans/2026-09-10-ontology-business-lifecycle-design.md:3-21` 自称设计提案，本文只将源码中找到的行为列为已实现；不引用历史测试结果充当本轮证据。本轮 Maven 八类结果独立见 `runtime/maven-results.json`。

## 已有能力

| 领域 | 当前支持 | 源码定位 |
|---|---|---|
| 业务建模 | 对象类型、关系、属性；创建术语、替换定义、替换限制，按保存草稿版本执行；一次1..1000命令 | `mateclaw-server/src/main/java/vip/mate/semantic/ontology/OntologyWireMapper.java:120-153` |
| 限制表达 | 对象属性的 SOME/ALL/MIN/MAX/EXACT，命名对象类型作 filler，非负基数 | 同文件 `:283-315` |
| 智能体建模 | 幂等建模任务、续接、选取知识库资料、分段读取冻结摘要资料、提出变更与未决问题 | `mateclaw-server/src/main/java/vip/mate/semantic/authoring/OntologyAuthoringTool.java:88-115` |
| 人工确认 | 智能体提交 proposal，工具明确不直接应用；人由任务界面确认，不把模型文字当批准 | 同文件 `:101-103` |
| 专家 OWL | 完整文档草稿导入/维护、从发布版本派生草稿；Functional Syntax/RDF/XML格式，OWL2 DL profile校验，锁定导入内容 | 同文件 `:150-171`；`mateclaw-semantic-owl/src/main/java/vip/mate/semantic/owl/OwlDocumentAdapter.java:178,1466,1573-1597` |
| 具体实体事实 | 命名个体的属性/关系正负断言，同一/不同个体断言；提出、修改、审核与撤回，可信视图仅保留已接受且仍受证据支持的当前版本 | `mateclaw-semantic-owl/src/main/java/vip/mate/semantic/owl/OwlAssertionAdapter.java:210-246`；`mateclaw-server/src/main/java/vip/mate/semantic/statement/StatementApplicationService.java:37-104`；`StatementReviewService.java:44,131`（同 statement 目录） |
| 知识库证据 | 基于权限发现资料，固定摘要分段读取；导入生成带 capture_version 的文本快照；上下文来源读取再次验证当前用户/智能体权限 | `mateclaw-server/src/main/java/vip/mate/semantic/authoring/OntologyAuthoringTool.java:105-115`；`mateclaw-server/src/main/java/vip/mate/semantic/source/ImportJobRunner.java:78-87`；`mateclaw-server/src/main/java/vip/mate/semantic/tool/SemanticContextTool.java:31-32` |
| 智能体查询 | 检索已接受且受支持事实，返回版本/证据/追踪；时间有效性必须显式处理 | `mateclaw-server/src/main/java/vip/mate/semantic/tool/SemanticTool.java:25-27` |
| OWL推理 | 一致性、分类、个体类型、单公理蕴涵；默认仅TBox，可显式加入本体ABox、已审核业务事实；返回版本与来源，变化时结果可能判过期 | `mateclaw-server/src/main/java/vip/mate/semantic/tool/SemanticReasoningTool.java:31-38` |
| 有界上下文 | 固定版本、asOf、512..12000估算token与续页；可选推理，未运行标识 NOT_RUN | `mateclaw-server/src/main/java/vip/mate/semantic/tool/SemanticContextTool.java:20-26`；`mateclaw-server/src/main/java/vip/mate/semantic/query/SemanticContextService.java:112-113,259,279` |

## 需要专家或仍有明确限制

1. **业务表单不是完整 OWL 编辑器。** 业务命令仅三种操作，限制只接受对象属性与命名类 filler；复杂嵌套表达式、匿名属性表达式等不能假定均可通过业务表单生成。高级完整 OWL 文档维护可作为专家路径，但仍受解析/profile/推理器能力约束。依据 WireMapper `:149-153,290-312` 与 AuthoringTool `:150-158`。
2. **可保存、可推理、可视化是不同覆盖面。** 投影会明确返回 PARTIAL/NOT_RENDERED，导入可折叠，不能把画布未显示解释成公理不存在，也不能宣称全 OWL 无损图形编辑。依据 `mateclaw-semantic-owl/src/main/java/vip/mate/semantic/owl/OwlDocumentAdapter.java:865-947`。
3. **不承诺任意 OWL 推理查询。** 引擎不支持的蕴涵类型返回 UNSUPPORTED，未知命名个体也会拒绝；OOM、超时、并发满分别是资源结果而非逻辑真假。依据 `HermitReasoningWorker.java:143-186,579-595`（同 owl 目录）。
4. **本体ABox不是自动批准的业务事实。** 推理默认TBox，加入ABox/已接受事实要显式选择；事实还有证据和业务时间过滤。依据 SemanticReasoningTool `:31-38` 与 `mateclaw-server/src/main/java/vip/mate/semantic/reasoning/SemanticReasoningService.java:144-165`。
5. **逻辑一致不等于业务记录完整。** OWL数量限制不能直接替代“必须填写编号”式完整记录检查；需要选择对应业务策略并由业务方确认语义，不能从资料缺失推出否定或随意补齐规则。设计明确此区分：`docs/plans/2026-09-10-ontology-business-lifecycle-design.md:38`；业务命令实际输出为 OWL SubClassOf 限制：WireMapper `:298-315`。
6. **推理不提供物理因果证明或完整解释保证。** 上下文工具明确定义不能当作物理原因证明；解释无明确前提时为 UNAVAILABLE，NOT_RUN 不代表 false。依据 SemanticContextTool `:20` 与 SemanticReasoningTool `:31`。
7. **资料不是指令，也不是自动可信规则。** 固定摘要分段读取，需要报告未读覆盖、保留未决问题并人工确认提案；不能声明智能体已经理解整份任意长文档。依据 OntologyAuthoringTool `:101-115`；抽取上限100000码点、最多20块，超出明确拒绝：`mateclaw-semantic-application/src/main/java/vip/mate/semantic/application/extraction/SourceChunker.java:10-21`。
8. **不扩展为通用 ETL、工作流执行或独立图数据库。** 这是当前设计明确范围外的产品目标，不能用语义工具已存在推断此类能力。依据设计 `:21`。本说明子任务未调用模型；父任务两轮真实模型结果另见 REPORT.md。不能从源码、两个合成样本或隔离测试推断整体提案准确率、生产吞吐与所有复杂行业语义达标。
