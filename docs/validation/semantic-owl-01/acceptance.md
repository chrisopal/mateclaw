# OWL-01 交付与验收记录

> 历史阶段记录：本文描述 OWL-01 当时的交付状态。当前实施与未完成验收请见 [当前验收入口](../semantic-owl-execution/current-acceptance.md)。

日期：2026-09-08。结论：**OWL-01 设计与规格交付完成；OWL-02 替换实施尚未开始。**

## 实际基线

工作树 `/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`，分支 `codex/enterprise-semantic-core`，HEAD `fa8217cd`。M6 已提交，与上一版规划“未提交”状态不同。M6 acceptance 记载本地合成场景完成，本轮未重跑其模型/浏览器验收。当前 Java 为 Temurin 21.0.7。相关 Maven 缓存未发现 OWLAPI/HermiT/Jena/Openllet/ELK JAR；本轮没有解析器或推理器执行证据。

核实的源码边界：

- core `OntologyDefinition` 三列表模型，`Entity` 单一 typeKey，`StatementValidator` 的精确 owner/source/target 类型检查。
- server `OntologyDtos`/`OntologyDefinitionCodec` 旧 JSON 契约；`SemanticQueryDtos`/`SemanticTool` 的事实与标签结果；M6 `OntologyAuthoringTool` 草稿与来源访问。
- `StatementContent` 的修订/证据/有效期边界；V191–V198 三库目录中 H2 表定义及测试路径；实际数据库未访问。
- root/core/application POM 与 UI package：Java21、宿主依赖版本及现有 Monaco 能力。未执行生产依赖解析。

## 交付物

| 文件 | 已完成内容 |
|---|---|
| [ADR-0003](../../adr/0003-owl2-dl-authority-and-reset.md) | 新文档唯一权威、IRI、imports 锁、完整编辑、事实/本体 ABox 分离、退役清单、M7/M8 契约 |
| [标准矩阵](capability-matrix.md) / [JSON](capability-matrix.json) | 87 个构造/边界行，每行七个能力状态及正反/往返/编辑/上下文测试映射；435 个规格 ID |
| [依赖报告](../../architecture/2026-09-08-owl-01-dependencies.md) | 版本/POM/许可证/官方能力和维护证据、Java21 风险、具体候选及验收方法 |
| [AI 契约](context-contract.md) | 普通 Agent 只读发布版本、授权、切片/分页、来源、公理、事实、推理和预算状态 |
| [RESET](reset-spec.md) | 真实数据源核实、白名单/共享引用、writer fence、备份恢复、表级依赖顺序和保留数据回读 |
| [实施任务卡](../../superpowers/plans/2026-09-08-owl-02-and-followups.md) | OWL-02 四单元、OWL-03、CTX、VAL、M7、M8 文件级动作及退出条件 |
| [测试规格](test-spec.md) / [夹具清单](fixture-manifest.json) | 11 个 Functional 输入、1 个 RDF/XML 输入、预期语义与治理/质量/库存测试要求 |
| [静态验证结果](static-verification.json) | ID、路径、矩阵列、夹具存在、XML well-formed、文件摘要；明确未运行列表 |

## 关键取舍

采用完整 Functional 文档持久化、RDF/XML/Functional 标准交换、公理级表单操作加完整文本编辑。旧格式不兼容，不迁移旧图。关系数据库仍管理治理与事实权威，暂不新增图数据库。

OWLAPI 5.5.1 是下一步明确的解析候选；HermiT 1.4.5.519 为推理候选。HermiT 原依赖 OWLAPI 5.1.9，精确组合必须先验证；不能以选定库等于全量语义已通过。推理采用本地隔离 worker，限制资源；不支持/超时/不一致单列。

AI 通过任务相关上下文和工具读取本体，不能靠建完本体就假定模型永久学会。M7 需要结构化公理来源，M8 只迁移新 OWL 修订之间的非空图。超差实测与公差分别建模。

## 本轮实际验证

运行 `python3 docs/validation/semantic-owl-01/verify_artifacts.py`，结果 PASS_STATIC_ONLY。验证器只检查规格一致性和 XML 结构，不运行 OWL。没有将 435 个规格 ID 写成已执行测试数。

已检查改动范围只在文档及其验证夹具/脚本；生产代码、POM、Flyway 和业务数据库未修改。未提交、推送或部署。工具输出目录 `.omx/`、`.playwright-cli/`、`output/` 未纳入交付或清理。

未验证：OWL parse/profile/round-trip、完整 datatype 语义、HermiT 二进制兼容、宿主打包启动、UI 新契约、RESET 真库执行、A/B/C 效果与性能。它们是后续阶段验收，不属于本轮静态通过结论。

## 交接

下一单元为 OWL-02-A：按具体候选执行标准库隔离探针，再开展新契约和编辑链路替换。新增依赖遵守项目的明确采用授权边界；本轮只提出坐标，没有添加。RESET 只能在新结构与重建夹具就绪后运行。任务卡明确“不自动进入后续实施”，本轮到 OWL-01 退出条件为止。
