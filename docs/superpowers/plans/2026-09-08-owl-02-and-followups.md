# OWL-01 后续实施任务卡与测试规格

日期：2026-09-08。实施基线：`fa8217cd`；执行前核对更新。状态：可交接规格，尚未实施。
根目录为 semantic-m1 工作树。保留用户其他修改，不改已应用 V191–V198。不从本卡自动启动后续阶段。

## OWL-02：新模型、标准交换、编辑与 RESET

### 02-A：固定标准库并通过隔离探针

文件：根 `pom.xml` 新模块；新增 `mateclaw-semantic-owl/pom.xml`、`src/main/java/vip/mate/semantic/owl/OwlDocumentAdapter.java` 与 `src/test/java/vip/mate/semantic/owl/OwlDocumentAdapterTest.java`。

采用依赖报告精确的 OWLAPI 5.5.1；HermiT 1.4.5.519 在 03-A 引入。先用 Java21 验证 RDF/XML 与 Functional parse/write、完整公理集合比较、注释/匿名节点、profile violations、imports lock。以本轮矩阵为覆盖索引生成每行 P/N/RT/EDIT 测试；把本轮片段补齐声明，不能只做关键词存在断言。比较公理结构（匿名个体允许一致重命名）、ontology/version IRI、annotations、imports 及逻辑结构，不比较字节排序。

接口定义在 core `ontology/OntologyDocument.java` 与 `ontology/OntologyDocumentPort.java`：`parse(document, syntax, lockedImports) -> ParsedDocument`、`validateDl(document) -> ValidationReport`、`applyAxiomChanges(document, commands) -> ParsedDocument`、`export(document, syntax) -> byte[]`。JDK DTO，不暴露 OWLAPI 类型。adapter 内建唯一标准库 AST，core 不复制类型层级。

验收：依赖树与最终包核查、固定制品摘要、无忽略公理；生产采用项按已确定依赖范围执行，不擅自添加第二套 RDF/规则库。若精确候选不兼容，记录具体错误后评估修复，不能悄悄换成子集。

### 02-B：权威模型与事实契约替换

修改 core `ontology/OntologyRevision.java`、`fact/Entity.java`、`fact/PredicateRef.java`、`fact/StatementContent.java`、`fact/StatementValidator.java`、`conflict/ConflictDetector.java`。新增业务策略模型 `policy/BusinessPolicySet.java` 和 assertion payload（标准公理文本 + IRI 签名，交 adapter 验证）。删除旧 `OntologyDefinition`、types/properties/relations 定义、旧 format 约束和分类逻辑，同步全部编译引用，不保留 dead code。

替换 server `semantic/ontology/OntologyApplicationService.java`、`OntologyWireMapper.java`、`OntologyPackageService.java`、`OntologyImpactService.java` 及相关 repository/row；删除 `OntologyDefinitionCodec.java`；改 `semantic/web/OntologyDtos.java`。所有提交含 expectedDraftVersion 与 operationId，整文档或公理命令两种入口复用同一写事务。

新增迁移 `db/migration/{h2,mysql,kingbase}/V<next>__semantic_owl_document.sql`（实施时确定未占用编号）：revision 的 document_text/syntax/digest/model_schema、imports 制品/锁、公理索引/来源绑定、策略版本；实体 IRI；事实 assertion payload。来源索引能引用草稿或发布公理，校验 sourceSnapshot/quote/digest。列表视图非另一路权威。V<next+1> 清理旧列只在 RESET 后安全执行，未清范围外旧数据则暂停 retire。

验收：`SemanticOntologyIntegrationTest`、`SemanticOntologyM4IntegrationTest`、`SemanticOntologyPackageIntegrationTest`、`SemanticOntologyImpactIntegrationTest` 改用新 fixture；CAS、幂等、不可变历史、权限保持。旧 JSON 请求明确 4xx，不转换。三库迁移分别验证；环境未提供不得把 skipped 当通过。

发布阶段门槛：OWL-02 必须通过 parse/import/profile；逻辑检查单列 NOT_RUN（直到 OWL-03），UI 和报告不得标“逻辑一致”。治理策略可在 OWL-03 开启强制一致性门槛；校验结果与引擎版本附在发布记录，新结果不改历史记录。

### 02-C：UI、M5/M6 与调用方整体换契约

修改 `mateclaw-ui/src/features/semantic/api/{types.ts,ontologyApi.ts,workbenchTypes.ts}`、`ontology/{OntologyEditor.vue,OntologyVersions.vue,useOntologyDraft.ts}`、`ontology/components/{EntityTypeEditor.vue,PropertyEditor.vue,RelationEditor.vue,OntologyStructureGraph.vue,OntologyPackageDialog.vue,OntologyImpactPanel.vue}`。复用版本/工作台布局，新增 `OwlDocumentEditor.vue` 和公理列表组件；已有简单表单按适合的公理类型调整，不能假定所有公理都可画成边。

修改 server `semantic/authoring/OntologyAuthoringTool.java`、`src/main/resources/skills/ontology-builder/SKILL.md`、`semantic/extraction/MateClawModelAdapter.java`、application `ExtractionPorts.java`。模型输出标准文档或受校验的公理命令；抽取事实仍先候选、审核、证据验证。普通 Agent 不获得 authoring 权限。新 schema 注册保持已有用户配置覆盖规则。

验收：保留 `OntologyAuthoringIntegrationTest`、`OntologyBuilderSkillTest`、`OntologyBuilderProvisioningTest`、M5 extraction lease/submission/quality 测试并换夹具。UI 验证复杂嵌套公理导入→文本修改→保存→简单表单改 label→导出→公理无损；冲突草稿不能覆盖，未授权 KB 无来源内容，Agent 不直接发布。

### 02-D：RESET 与新用例

新建 `scripts/semantic-owl-reset` 的受控 dry-run/apply 工具及集成测试，按 [RESET 规格](../../validation/semantic-owl-01/reset-spec.md) 实现 manifest、writer fence、主键白名单、备份/恢复与回读。先隔离库演练，再对确认目标测试数据执行，不整库清空。

重建 `mateclaw-server/src/test/java/vip/mate/semantic/support/SemanticExtractionFixture.java` 及标准样例：质量模型 + 库存模型。质量：CMM、Probe、Controller、MeasurementProgram、EnvironmentObservation、CalibrationRecord、Measurement、ToleranceSpecification；库存：Item、Location、StockObservation、ReorderPolicy。领域只在 fixture 中，不写死在通用服务中。

验收：RESET-DRY/RACE/SCOPE/ROLLBACK/REBUILD/IDEMPOTENT；新本体发布回读、新图绑定、带有效证据的超差实测可入库。保留数据摘要对照、新版本导出与 fixtureRunId 清单归档。

## OWL-03：有边界的标准推理

03-A 新增 core `reasoning/ReasoningPort.java` 及 JDK 请求/结果 DTO，adapter `HermitReasoningWorker.java` 和 `HermitReasoningAdapterTest.java`；server `semantic/reasoning/ReasoningApplicationService.java`。采用报告中的 HermiT 候选，先排除旧 OWLAPI 并验证与 5.5.1 组合、JDK21、datatype 和嵌入类。

推理运行在受控本地子进程，使用版本化 JDK JSON 请求文件/响应文件接口（宿主已有 JSON 能力），无外部监听端口；timeout 后销毁进程并清理临时制品。规划默认 30 秒、512MiB 单 worker、最多 1 个并发，排队/上限明确返回资源状态。这些是初始保护参数，实测后才能定性能目标；不能只 Future.cancel 后让失控线程留在 web 进程。

03-B 请求固定闭包/事实快照/时间范围/任务/引擎；一致性、分类、实例类型、指定公理蕴含分别声明支持。先检查 inconsistent，不暴露其任意蕴含；unsupported datatype 不忽略。推导结果带输入摘要和 provenance，不写成 accepted fact。最小解释不是默认承诺。

验收：SEM-01 至 SEM-08 的全部变体、标准 datatype/facet 扩展、W3C Direct Semantics 测试；启动/销毁/超时/内存耗尽/重复请求、缓存失效、权限隔离。失败状态不能转 false。凡标准合法用例失败保留 gap，并阻止“完整 DL 推理已验收”。

## CTX-01：普通 Agent 只读上下文

文件：新增 server `semantic/query/SemanticContextService.java`、`SemanticContextDtos.java`、`semantic/tool/SemanticContextTool.java`；改 `SemanticTool.java` 共用权限入口，使用既有 `SemanticQueryService.java` / evidence 回读；新增上下文单测和工具授权集成测试。按 [契约](../../validation/semantic-owl-01/context-contract.md) 完成版本、来源、预算、分页、推理状态。

先在 OWL-02 完成后实现不带推理的 NOT_RUN 路径，03 完成再集成，不等待引擎就串行阻塞所有上下文开发。验收八个 CTX-* 场景，尤其草稿隔离、版本过期和缓存撤权。不能把 OntologyAuthoringTool 直接注册给普通员工。

## VAL-01 → M7 → M8

- VAL-01：在 `docs/validation/semantic-val-01/` 冻结 18 题题库/评分表、A/B/C 上下文输入和运行脚本；使用同模型重复对照，保存脱敏请求、响应、来源回读和 token/延迟。通用库存用例不得改通用模型代码。失败后调整具体检索/契约缺口，再跑受影响题，不以主观“更聪明”验收。
- M7：新增 `semantic/ontology/source/OntologySourceReviewService.java` 与来源变更复核 UI；基于 revision/axiom/sourceSnapshot/digest 索引生成待复核项。来源变更不自动重写发布本体；保留原快照和人工差异审核。测试删除资料、共享来源、一段支撑多个公理、已失效来源、重复通知幂等与越权。
- M8：新增 `semantic/graph/migration/GraphRevisionMigrationService.java` / plan repository 和 migration UI；改 `GraphApplicationService.java` 的受控升级入口，普通 rebind 仍禁止非空图。支持 OWL v1→v2 的 dry-run、版本/IRI/策略映射、影响报告、审核、CAS 原子切换、失败恢复、原解释版本回读。旧 format 数据没有迁移入口。测试非空图、未解决映射阻断、并发事实写入阻断、v1 历史保持、导入依赖变化与回滚。

## 命令与证据门槛

实施后的后端按模块运行 `mvn -pl mateclaw-semantic-owl,mateclaw-server -am test`（先固定 JAVA_HOME 为已安装 JDK21）；失败定位后定向重跑。UI 在其目录运行 `npx vitest run src/features/semantic`、`npx vue-tsc --noEmit`、`npx eslint src/features/semantic`；用不带 --fix 的 lint 检查，避免改无关代码。最终 server package 与真实启动验证解析器 SPI/日志类路径。

每阶段报告明确文件、输入版本、执行命令、通过/失败/跳过、存储回读和真实页面路径。OWL-01 的静态 JSON/XML 检查不代替上述运行。新依赖未引入之前不声称 Java21 或标准引擎兼容已通过。
