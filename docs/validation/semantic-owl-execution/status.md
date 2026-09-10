# OWL 全路线执行状态

2026-09-09；工作树 `semantic-m1`，基线 `fa8217cd`。整体目标仍在执行，未部署或清理运行数据库。

## 已实现与最近验证

- OWLAPI 5.5.1 模块、JDK 核心文档/断言端口、新 Functional Syntax 权威文档；标准 RDF/XML 交换、离线 imports 锁、OWL 2 DL profile 校验。
- 旧核心 OntologyDefinition / StatementValue 等模型移除；实体 IRI / 多类型，事实标准断言；server ontology/graph/statement/extraction/query 调用链切换。
- V199/V200 新文档与断言列、公理索引；草稿写事务同步索引、CAS、operationId 重放；不修改 V191–V198。
- 核心 25、应用抽取 13 项最近独立运行通过。适配器原 101 项通过后发现匿名公理 ID 碰撞，新增失败回归并改用 AST 文档作用域；修复后 102 项通过。RDF/XML 允许匿名节点重命名，绑定 ID 稳定性保证限于权威 Functional 文档。
- 新 server 契约26项测试通过：Ontology12 + QueryWindow2 + DomainContract2 + DocumentMapper3 + Persistence1 + Schema6。
- 新标准事实 M2 端到端7项通过，含证据/审核/查询/撤回/恢复/容量，冲突测试改为显式正负断言。
- Extraction lease6 + Mapping2 + Model1 最近通过；质量2项在修复 Set JSON 边界后通过。
- 标准包 v2 的5项测试通过；独立重复导入、原文摘要、幂等和回滚。Authoring4项、建模师技能1项通过。

证据日志均在本机 `/tmp/mateclaw-owl-*.log` 与模块 surefire-reports；不是正式跨库认证。测试中的模拟账号不得用于真实运行环境。

## 正在执行

- CTX-01：版本固定、签名分页、实际序列化预算、时间过滤、证据撤回的3项集成通过；来源绑定随上下文返回并按当前 Agent 权限回读，推理集成仍待完成。
- UI：原工作台布局上切换完整 OWL 文档和断言契约；旧表单/旧 JSON 路径退役。
- OWL-03：HermiT 标准推理与受限子进程13项通过；实际 Spring Boot 打包 jar 的嵌套依赖展开启动验证通过。宿主服务/工具与上下文推理尚待接入。
- M7：V201 来源快照/绑定/变更复核，HTTP 业务错误边界；来源修改、删除、CAS、幂等、草稿复制、原始发布文本保留、Agent 撤权4项集成通过。建模工具新增精确来源绑定；V202 注册只读上下文工具，默认不改变既有 Agent 选择。
- 业务单位采用标准公理注释，已接入单事实校验；允许值策略已接入，显式单值/完整对象提交仍待补。

## 尚未完成的门槛

- 全部旧 server/UI 测试用例按新语义重建；M4影响、来源引用等完整回归；前端类型、测试、lint、真实浏览器。
- 业务策略真正接入录入/审核，包括显式单值规则与单位约束。不能以 OWL functional 代替业务唯一性规则。
- imports 制品持久化去重、M7公理来源绑定、来源变更复核与权限回读。
- 完整 OWL 矩阵负例/无损/编辑映射及标准推理SEM测试证据汇总；不把93个适配器测试叫作435个规格测试通过。
- RESET dry-run/白名单/备份/写入围栏/恢复演练与新质量、库存数据重建。
- VAL-01冻结18题的三组真实模型重复对照、证据准确性与成本结果。
- M8仅新OWL版本非空图升级、映射审核、CAS原子切换/恢复及旧解释回读。
- 最终打包、真实启动、部署验证及三库环境验证。

当前不能声明整体规划完成、完整标准推理已验收或运行环境已切换。

## 2026-09-09 后续增量（覆盖上方过时的待办状态）

- imports 制品池已持久化并按工作空间/摘要去重：1 项真实持久化测试通过；修订仍独立保存完整锁定文档。
- M4 影响分析 8 项通过，显式业务 singleValue、允许值及单位校验已接入；完整对象必填字段提交入口仍待实现。
- 推理宿主服务和只读工具已接入，V203 默认不启用；4 项权限/快照/工具测试通过。事实单位注释不再被推理解析器错误拒绝，13 项真实 worker 测试再次通过。
- M7 公理来源原/新快照比较、当前复核状态、撤权校验 4 项通过；V204 新观察快照外键及去重索引。
- M7 图来源变化队列 V206 已覆盖事实、候选、待处理变更提案及未提交抽取建议；两项集成通过。追加上下文也触发复核。CTX 返回事实待复核状态且固定到分页摘要，CTX 与 source-change 合计 5 项通过。扫描恢复/并发还需进一步验证。
- AST 角色化 IRI 映射支持 class/object property/data property/individual 分开处理，嵌套表达式和 punning 回归通过（assertion adapter 10 项）。M8 服务尚未实施，细化规格见 2026-09-09-m8-owl-revision-upgrade.md。
- VAL 质量与库存真实 API/HermiT 输入导出通过；prepared-run 已生成 162 个请求。没有执行正式模型对照，当前提供商余额不足；无实验分数或货币成本结论。
- RESET 精简模式 H2/隔离 MySQL 演练已通过，但当前仍需真实 V191–V206 模式及备份文件恢复验证，不能替代最终 RESET 验收。
- UI 先前 51 项与后来来源作用域 2 项通过；图来源队列 UI 仍在实现，最终统一检查及真实浏览器尚未完成。

## 2026-09-09 11:46 核验记录（替代上文相应旧状态）

- 最新全语义范围命令 `mvn -q -pl mateclaw-server -am '-Dtest=vip.mate.semantic.**' -Dsurefire.failIfNoSpecifiedTests=false test` 返回 0；报告汇总：core 26、application 13、owl 125 均无失败；server 147 中 20 skipped，无失败。20 skipped 为 MySQL 条件用例 19 及显式导出用例 1；不能算通过，亦非全项目 Maven test。
- M8 已实现 prepare/approve/execute/rollback、实体角色映射、非空图 ACCEPTED 事实追加修订、CAS、临时 IRI/digest 同步互换与目标 singleValue 组冲突阻断。2 项集成通过；完整并发/失败恢复验收仍需逐项补证据。
- RESET 工具覆盖 V191–V207 实际 36 张 semantic 表；按引用类型限定闭包，共享来源/外部迁移计划拒绝，schema 指纹不符拒绝恢复。H2 与临时 MySQL 实际迁移演练通过，恢复 8 行、重建 2 revisions/83 axioms；主业务库尚未清理。
- 最新打包服务已在独立 H2 的 18109 启动，重启后本体摘要、实体、事实及 M7 来源决策读回通过。复核脚本重复运行只读取已完成决策，不再次提交新决策。
- 5189 验证前端此前漏传企业版参数而显示 classic；现已恢复 enterprise，并在浏览器 DOM 验证。新增 `scripts/semantic-owl-runtime/start-ui.sh` 固定 `--mode enterprise`。不更改主前端的默认 profile 契约。
- M8 UI 绑定修订改变时刷新可选目标，避免显示已绑定版本；作用域与刷新回归共 3 项通过。此前全语义 UI 60 项、类型/lint/build 通过；此后新增该回归，不将旧 60 项冒充最新全套运行。
- 尚余完整对象业务必填入口、标准矩阵逐项证据与外部标准集、M8 真实页面升级回退、跨库条件门槛、实际目标测试数据 RESET、VAL 真实模型实验及最终集成交付。VAL 当前提供商余额不足，未生成真实对照分数。

## 2026-09-09 11:52 页面及输入读回

- VAL 最新真实 HTTP/HermiT 输入重新导出通过；`prepared-run-final` 冻结 162 请求，manifest `4b4f0c53b728e60ff469903b9713328cb770faa51b070e20cc3db1ac32b36323`。仅 PREPARED，未调用付费模型。
- M8 在企业版真实浏览器完成含 1 实体的非空图 v1→v2 预演、批准、执行、恢复。API 二次读回分别为 EXECUTED/ROLLED_BACK，图版本 1→2→3，实体 ID 保留。证据：`semantic-owl-runtime/migration-{executed,rollback}-readback.json`。本页面夹具没有 ACCEPTED 事实，事实追加历史由集成测试覆盖，不混称浏览器证明。
- 新发现目标版本的待复核公理来源未被升级门槛检查；已补 prepare/执行前复查的目标 pending 条件和回归，待定向 Maven 运行。当前运行 jar 不包含此后端增量。

## 2026-09-09 完整对象与 M8 门槛补齐

- 新增 `POST /semantic/graphs/{graphId}/objects/complete-validation` 显式完整对象检查：服务端实体类型、固定图/本体版本、标准断言、单位注释、必填/枚举/单值聚合校验；不创建事实，不绕过证据与审核。空输入返回业务必填缺失。单纯增量事实录入继续允许不完整对象。
- 完整对象 HTTP 集成 1 项（多个行为断言）、核心策略 6 项通过；此 API 尚未接入页面提交操作，不把它称为所有表单已集成的门禁。对象关系必填仍显式报告不支持，当前聚合策略面向数据属性。
- 目标本体 pending 来源复核加入 M8 prepare 与切换前 recheck；M8 集成现 3 项通过。
- 独立 MySQL 测试 runner 已启动，新建两个临时 schema，仅针对这些新建 schema 做清理；实际业务库不参与。结果待进程完成读回。

- 隔离 MySQL 条件测试已结束：5 个 suite 共 19 项，0 failures/errors/skipped。真实 Flyway 空库→V207 与 V190→V207 均通过；两个专用临时 schema 已随成功 cleanup 删除。摘要见 `mysql-acceptance.json`。这覆盖现有 MySQL 基础/M4/质量用例，不代表新增 M7/M8/完整对象全部跨库验收。

## 2026-09-09 12:05 跨库扩展与真实缺陷修复

- 新增 MySQL 条件测试包装类，复用完整对象、M7 公理来源/图来源变化、M8 升级原始集成用例，扩展到 9 suites/30 项。
- 首次实跑发现 MySQL REPEATABLE READ 下复核事务看不到独立导入事务刚提交的新快照，M7 三项返回 500。已将捕获快照摘要读取改为锁定当前读，图版本也在队列写入前锁定读取。重跑 30 项全部通过，无失败/错误/跳过，临时 schema 清理成功。H2 无法单独证明该行为。
- 企业版前端 `npm run build -- --mode enterprise` 返回 0，生产构建明确启用 enterprise；仅有现有 bundle size 警告。
- 已启动规划指定模块及依赖的完整 `mvn -pl mateclaw-semantic-owl,mateclaw-server -am package`，含测试，结果待完成。完整标准矩阵/外部标准集、目标数据实际重建、VAL 真模型及最终集成交付仍未完成。

- 最新前端语义全套：25 文件/61 项通过。真实 MySQL 30 项汇总已保存；旧 19 项数字是先前较小覆盖，不能与新增部分重复相加。
- 目标数据只读探查：容器 `mateclaw-mysql` 配置的 `mateclaw` schema 当前没有 `mate_semantic_ontology` 表，不能将该库当作已确认旧语义测试数据清理目标；未对其执行迁移或删除。RESET 实际目标仍需从运行语义服务配置确认。

## 2026-09-09 12:24 标准归档与实际旧数据定位

- W3C 官方归档已下载并固定 SHA-256；489 个唯一用例中 266 个明确属于 Approved/DIRECT/DL。`semantic-owl-w3c/case-index.json` 全部 execution=NOT_RUN，不能计为通过；实际标准库/worker 验收 harness 正在实现。
- 使用 `IFEXISTS=TRUE;ACCESS_MODE_DATA=r` 只读访问实际本地默认 H2 路径：主目录 `mateclaw-server/data/mateclaw` 没有本体表；本工作树同路径 ontology/revision/graph 数量均 0，读回见 `semantic-owl-reset/local-target-readonly.txt`。所以这些已核实位置的旧数据删除为无目标，不执行删除；主 MySQL 也已核实无本体表。后续重建样例仍需与使用者实际运行环境区分。
- 完整 package 仍在原进程执行，769 份 server 测试报告已产生，尚无 Maven 失败汇总，不作为最终完成证据。

- 完整测试主体已结束并进入 JVM SpringApplicationShutdownHook：server 769 suites/5152 tests，0 failures/errors、32 skipped；owl 118、core 27、application 13，均0失败。测试进程仍在逐个关闭上下文（SchedulingConfig每个等待30秒），Maven session 12516 尚未退出，所以 package 不计通过。摘要 `full-test-pre-exit.json`；MySQL 30项已在单独环境显式通过，不把本轮条件跳过掩盖为通过。

### M8 current source validation follow-up (implementation pending test)

- Added source and target ontology binding checks against the current raw source digest, including edits made without running the scan queue. Explicit REMODEL decisions block migration.
- Migration checks lock source rows through transaction commit; ordinary source reads keep their existing behavior.
- Extended the HTTP regression scenario: acknowledge original source, prepare successfully, edit without scan, reject approval and block a new plan while preserving source graph binding.
- `git diff --check` passed. Targeted H2/MySQL tests not yet run: Maven session 12516 remains alive in Spring shutdown; no second Maven process started. Its package, if successful, predates these edits and must not be presented as validation of this follow-up.

### M8 current-source verification
- Original full package session 12516 exited 0 and produced the JAR. New current-source changes then passed H2 migration tests (3) and actual MySQL tests (30, no skips); disposable schemas cleaned by runner.
- Updated package session 5124 exited 0 (`-DskipTests`, after the targeted tests). Evidence: `m8-current-source-tests.json`. Runtime process 44789 was started before this updated package and is not yet proof of the latest M8 change.

- Updated runtime session 8704 runs an isolated JAR copy, preventing subsequent builds from replacing its class source. Health 200; complete-object packaged API and post-restart persisted readback passed (exit 0), stale CAS rejected, graph binding unchanged. Evidence: `../semantic-owl-runtime/complete-object-readback.json`. Added a 60-second HTTP timeout to the smoke helper.

### M8 execution-time source change regression
- Added approval-then-edit scenario: execute returns 409 after an unscanned source change; graph version and source ontology binding remain unchanged. H2 migration suite passed 3/3, exit 0 (`/tmp/mateclaw-owl-m8-execute-source.log`). MySQL follow-up is running; no production changes in this slice.
- W3C first full standalone report now contains 266 cases: 210 PASS, 52 GAP, 4 FAIL. Failures are under diagnosis and explicitly prevent a complete OWL conformance claim.

### M8 bounds before staging
- Moved entity/current-accepted-fact count limits before freezing and transforming payloads under the graph lock. Oversized requests return explicit GRAPH_ENTITY_LIMIT/GRAPH_STATEMENT_LIMIT conflicts without storing a plan. Target class signature is computed once per freeze instead of once per entity.
- H2 migration suite now passes 4/4 including 1001-entity rejection with no plan or graph-version change (`/tmp/mateclaw-owl-m8-limits.log`, exit 0). MySQL latest 30/30 proves the preceding execution-after-source-edit scenario; new bound test is not yet MySQL-verified or runtime-packaged.

### M8 typed mapping target validation
- Reused OWL typed signature via OntologyWireMapper.termKinds. Class/object-property/data-property mapping targets must match the specified entity role even for unused mappings. Named-individual handling remains graph-identity based.
- H2 migration suite 4/4 passed with a data-property-as-object-property target regression (`/tmp/mateclaw-owl-m8-typed-mapping.log`, exit 0). This production change still requires updated MySQL/package/runtime verification.
- The preceding pre-staging bound change passed actual MySQL 31/31, no skips, runner exit 0; evidence `m8-limits-mysql.json`.

### M8 target availability and W3C diagnosis
- Typed mapping MySQL acceptance passed 31/31 (no skips), evidence `m8-typed-mysql.json`.
- M8 recheck now rejects a target that has become unavailable for new bindings after plan creation; a regression disables the target, verifies approval rejection, restores it and completes the original migration scenario. H2 4/4 passed (`/tmp/mateclaw-owl-m8-target-availability.log`). New availability change awaits MySQL and package verification.
- Corrected W3C harness prefix rendering, unchanged ontology meaning: full result 211 PASS / 54 GAP / 1 FAIL. Remaining WebOnt-Thing-003 reproduces in HermiT initialization on EquivalentClasses(owl:Nothing owl:Thing); production fix assigned with regression coverage, not yet complete.

### W3C gap ledger and M8 atomic failure regression
- Created hash-linked `w3c-gap-ledger.json`: 32 query-cardinality, 14 RDF expression-cardinality, 3 locked-import fixture, 2 DTD input, 2 timeout, 1 blank-rendering gaps. All remain open; no exclusions or inferred passes.
- Added an integration fault at the final migration plan update via a temporary, plan-specific CHECK constraint. The scenario asserts graph binding/version, entity IRI, fact revision count and APPROVED plan all roll back, then removes the constraint and runs normal execution. This test has NOT run yet; the sole Maven slot is with the OWL engine-fix agent. `git diff --check` passed.

### M8 atomic failure H2 evidence
- H2 migration suite passed 4/4 with the forced CHECK failure at final plan-state update. Assertions read back unchanged graph binding/version, original entity IRI, original fact revision count and APPROVED plan; subsequent normal execute and append-only rollback also passed. Log `/tmp/mateclaw-owl-m8-atomic-failure.log`, exit 0.
- Actual MySQL runner session 94689 is executing this same scenario plus target availability and typed mapping regressions. No result claimed yet.

### M8 MySQL atomic failure and W3C offline imports
- Actual MySQL runner 94689 exited 0, 31/31 tests, no skips; injected final write failure rolled back the earlier entity/fact/graph writes and preserved APPROVED plan. Target availability and typed mapping checks included. Evidence `m8-atomic-mysql.json`.
- Extracted 14 exact imported ontology documents and 14 case-reference lists from pinned W3C archive into `w3c-import-fixtures.json`; no missing reference or empty artifact. Every document includes hash and ontology IRI. Harness integration has not yet occurred; not counted as passing cases.

### Integrated package and runtime
- Package session 83650 exited 0 after targeted M8/HermiT checks; jar hash recorded in `integrated-package.json`. W3C full rerun 212 PASS / 54 GAP / 0 FAIL, including WebOnt-Thing-003 INCONSISTENT.
- Fixed macOS mktemp suffix behavior in runtime startup: use unique directory with server.jar inside. Verified consecutive unique directories and shell syntax. Runtime 84917 health 200; post-restart data readback and complete-object CAS smoke exited 0. Enterprise frontend process remains unchanged.
- W3C offline imports harness integration delegated; production engine changes not included in that scope.

### M8 real intervening writes
- Added HTTP-created entity writes after preparation and after execution. The stale plan cannot be approved; after reprepare/execute, rollback is rejected even with the latest graph version once another entity is created. Binding and added entity remain unchanged.
- H2 migration suite 5/5 passed (`/tmp/mateclaw-owl-m8-concurrent.log`, exit 0). Actual MySQL follow-up running. No production code changed in this slice.

### M8 post-rollback revision continuity
- Real intervening-write scenario passed actual MySQL: 32/32 no skips, runner78769 exit0; `m8-intervening-writes-mysql.json`.
- Extended normal migration/rollback scenario through public change-proposal and review endpoints: revision4 after rollback advances to revision5 with source ontology binding, preserving earlier history without PK collision. H2 suite5/5 passed (`/tmp/mateclaw-owl-m8-after-rollback.log`, exit0). This newest test has not yet run under MySQL.

### M8 scope and replay gates
- Post-rollback revision continuity passed real MySQL 32/32 with no skips, session62255 exit0; evidence `m8-postrollback-mysql.json`.
- Added prepare rejection across workspace (404), for member role (403), and for changed mapping reusing operationId (409); valid identical replay still returns original plan. H2 suite5/5 passed session18011 (`/tmp/mateclaw-owl-m8-scope.log`).
- W3C imports integration currently changed standalone result counts unexpectedly; do not supersede the previous hash-linked 212/54/0 evidence until worker execution configuration and results are verified.

### M8 scope MySQL and W3C imports acceptance
- Scope/replay scenarios passed actual MySQL 32/32, no skips; session34802 exit0. Evidence `m8-scope-mysql.json`.
- W3C imports harness now uses hash-checked exact offline fixtures. Verified full266 result213PASS/53GAP/0FAIL; imports-011 proves consistent + entailed. Two miscellaneous cases now expose the actual DOCTYPE input restriction rather than missing fixture. Gap ledger updated against current result hash; failure-diagnosis preserves earlier prefix and engine issues.
- Next harness work: properly evaluate ground multi-axiom conclusions without incorrectly separating shared anonymous existential variables.

### Restricted internal XML entity prototype
- Added package-private RdfXmlInput using JDK DOM/transformer with explicit external-access restrictions, parameter-entity rejection and 10000 expansion / 1MiB entity-size bounds. Internal entities expand before DTD removal. It is NOT yet connected to document/worker input paths, and no W3C DTD gap is closed yet.
- RdfXmlInputTest4/4 passed: internal expansion, external local/network declarations, external DTD, parameter entity, expansion budget and ordinary XML passthrough. Maven session3100 exit0. Initial compile attempts exposed missing core import and in-progress harness import; corrected before this successful run.
- Reference checked: https://docs.oracle.com/en/java/javase/21/security/java-api-xml-processing-jaxp-security-guide.html (explicit external-access restrictions and JAXP processing limits).

### RDF/XML internal entity integration
- Connected bounded internal entity normalization to adapter root/imports and reasoning worker root/imports. Authoritative document and locked import text/hash remain original; only disposable parser input is normalized. Invalid XML maps to PARSE_ERROR.
- 115 tests passed (RdfXmlInput4 + OwlDocumentAdapter95 + Hermit16), session73178 exit0; original root/import content readback and typed signature asserted. Evidence `rdfxml-internal-entities.json`. A prior shared-temp cleanup assertion collided with concurrent W3C; rerun used isolated temp dir inherited by child JVMs.
- Final server package/runtime and W3C rerun for these production changes remain pending.

### RDF/XML packaged server verification
- Ontology12/package5/reasoning2 server tests passed. Package75760 exited0; runtime7343 healthy; rdfxml-readback.py exited0 after real save/reject/publish/readback. Evidence `rdfxml-package.json` and `../semantic-owl-runtime/rdfxml-readback.json`.
- Corrected runtime smoke expectation: the existing server contract stores canonical Functional Syntax, not uploaded XML bytes. Adapter preserves original input/hash; server publication preserves its canonical document/hash and expanded Equipment IRI. External entity save returned422 and preserved draft version/digest. Initial unsuccessful smoke created a synthetic draft only; no business data changed.

### RDF singleton class expressions
- Added RdfClassExpressions to adapter and worker RDF imports closure: simplify singleton intersection/union wrappers on affected axioms only. Functional Syntax remains unchanged.
- Whole-ontology OWLAPI transformation initially altered unrelated capability matrix constructs; constrained transformation to axioms containing singleton wrappers. Final113 tests (2+95+16) passed in isolated temp directory, session26594 exit0. Evidence `rdf-singleton-expressions.json`. W3C full rerun and server/runtime verification still pending; do not claim14 gaps closed yet.

### RDF expression preservation and packaged runtime
- Added same-axiom nominal + annotation preservation regression; all3 RdfClassExpressions tests pass. Server ontology/package/reasoning19 tests passed. Package42098 exit0, hash in `rdf-lists-package.json`.
- Runtime75686 health200; rdfxml-readback.py --singleton passed real internal-DTD + singleton-intersection input save, canonical EquivalentClasses preservation, external-entity rejection preserving draft, publish/readback. Evidence `../semantic-owl-runtime/rdfxml-singleton-readback.json`. W3C full run remains pending.

### Root axiom closure profile validation
- Reproduced false undeclared-class violation where root declares B and imports A subclass B. OWLAPI profile walk checks imported ontology declaration scope even when called on root; repeated per-member checks compounded the issue.
- Validate the root axiom closure in an isolated profile ontology with scoped anonymous IDs and ontology annotations, keeping per-document literal lexical checks. No declarations auto-added. Missing B in entire closure remains invalid.119 tests passed, session52026 exit0; `root-closure-profile.json`. Server/package/runtime and W3C Wine reruns pending.
- Latest observed W3C full result259PASS/7GAP/0FAIL incorporates preceding multi-query, XML and RDF expression work; final manifest report pending agent.

### Root closure packaged verification
- Server ontology/package/reasoning19 tests passed after closure profile change. Package75529 exit0, hash in `root-closure-package.json`.
- Runtime74202 health200; closure-readback.py passed real save/publish/readback with root-supplied declaration used by imported axiom. Locked dependency original text and content digest preserved. Evidence `../semantic-owl-runtime/closure-readback.json`. W3C corresponding rerun remains pending.

### M8 target boundaries and verified W3C baseline
- Added direct HTTP rejection tests for a published revision of another ontology and an unpublished revision of the same ontology. Both preserve graph binding/version and entity IRI and create no migration plan.
- H2 migration suite6/6 passed; isolated actual MySQL suites33/33 passed, no skips. Evidence `m8-target-boundary-h2.json` and `m8-target-boundary-mysql.json`. No production code changes in this slice.
- Verified W3C result/archive/import-fixture/diagnostic hashes against manifest:266 cases,260PASS/6GAP/0FAIL. Updated `w3c-gap-ledger.json`; Wine closure cases pass. Remaining1 query-rendering,2 shared-anonymous-query,3 timeouts at15 seconds are not accepted as passing.

### M8 accepted fact write CAS
- Parameterized the intervening-write acceptance over entity creation and real HTTP fact proposal plus ACCEPT review. Writes after prepare reject old approval; writes after execute reject rollback even with current graph version. Target binding and intervening row remain intact.
- H2 migration7/7 and isolated actual MySQL34/34 passed, no skips. Evidence `m8-fact-cas-h2.json`, `m8-fact-cas-mysql.json`. This proves interleaved HTTP operation protection, not simultaneous-thread stress performance. No production code changed.

### Matrix remove preservation
- Extended all78 concrete capability rows through adding a unique annotation axiom and removing it by stable axiom ID. Original axiom structure, ontology IRI, ontology annotations and import-lock digest are restored exactly.
- OwlDocumentAdapterTest96/96 passed; `matrix-remove-preservation.json` records scope and remaining negative/context coverage limitations. Initial test accessor typo and concurrent harness signature edit caused compile failures; corrected before final successful run. No production code changed.

### Concrete missing-operand negatives
- Added22 parameterized negative fixtures:17 class expressions OWL-015..031 and5 data-range constructors OWL-033..037, each missing required operands. All require explicit PARSE_ERROR rather than successful parsing or silently dropping malformed expressions.
- OwlDocumentAdapterTest118/118 passed, no skips; `matrix-missing-operands.json`. This does not claim lexical/facet or inference negative coverage complete.

### Datatype facet compatibility
- Added four paired facet cases: decimal/minInclusive and string/length accepted; decimal/length and string/minInclusive rejected with PROFILE_VIOLATION. Parsed restrictions remain present; invalid constructs are not silently discarded.
- Adapter122/122 passed, no skips. Evidence `facet-compatibility.json`; OWL2 structural specification datatype restrictions checked. No production changes in this slice.

### Actual JVM heap exhaustion
- Reproduced exit3 from a real16MiB JVM allocating64MiB; worker incorrectly returned FAILED. Added ExitOnOutOfMemoryError to managed JVMs and classify exit3 only with OutOfMemoryError diagnostics as RESOURCE_EXHAUSTED. JVM stdout diagnostics are retained for failed exits.
- HermiT17/17 passed; a further directed control proves plain exit3 stays FAILED and repeated real OOM releases the slot. Evidence `heap-exhaustion.json`. Server packaging/runtime refresh still required for this production change.

### Heap status packaged runtime
- Service reasoning/snapshot tests3/3 passed; package66941 exited0. Old isolated server PID79020 exited and released H2 before starting runtime66391.
- Health200 and complete-object-readback.py exited0, including existing ontology/fact/source persisted readback. Evidence `heap-package.json`. OOM itself was forced in the isolated16MiB JVM regression, not the live service. Enterprise frontend was not restarted.

### M8 pinned digest drift
- Injected out-of-band changes to source/target document and import-lock digests after approval. Each execute rejects with409, preserving APPROVED state, source binding/version and original fact revision count. Original digest is restored in finally before normal execution/rollback.
- H2 migration7/7 and actual isolated MySQL34/34 passed, no skips; evidence `m8-digest-drift-h2.json`, `m8-digest-drift-mysql.json`. This is integrity-fault protection; it does not permit editing published revisions.

### Complete context pagination and draft isolation
- Strengthened CTX tests with a genuinely edited unpublished DraftOnlySecret concept; published Agent context excludes it. Traverse every page of120 declarations, asserting serialized budget, consistent snapshot, unique IDs and complete retrieval.
- A signed cursor remains unusable after KB visibility revocation (404). Context integration3/3 passed; `context-complete-pagination.json` records repository-mock authorization boundary. No production change.

### Retire legacy runtime field writes
- Removed definitionJson from OntologyRevisionRow, its null setter, and INSERT/UPDATE assignments in OntologyMapper. The application semantic runtime no longer references the old field. Updated mapper tests to check absent OWL documents fail rather than assigning a deleted legacy DTO field.
- Ontology/package/mapper/persistence checks passed; `legacy-runtime-write-removal.json`. Historical migration fixtures and RESET still reference the physical nullable column; its conditional retirement is not complete. Packaging/runtime refresh for this change remains pending.

### W3C full tree normalization baseline
- Verified manifest result SHA:266 cases264PASS/2GAP/0FAIL. Generic singleton and tree-shaped shared-witness queries now covered; different-witness counterexample returns NOT_ENTAILED. Gap ledger updated.
- Independent directed run of208/209 at product default30s also times out for both; `w3c-30s-timeouts.json`. No increase of main baseline counts and no claim of full reasoning acceptance.
- Legacy runtime field removal passed actual MySQL34/34 and package47226 exit0. New package is built but isolated server still runs the preceding heap-fix package; refresh remains pending.

### Legacy runtime dependency removal packaged
- Old server69605 exited before new runtime10860 started. Health200; new owl-persistence-readback.py created a synthetic ontology, inserted/updated draft, published, then edited a new draft and verified published document immutability. Existing source/fact readback also passed via common helper.
- Evidence `legacy-runtime-package.json` and `../semantic-owl-runtime/owl-persistence-readback.json`. Physical column retirement remains separate and pending. Frontend process unchanged.

### RESET rebuild independent of old column
- Removed definition_json column/placeholder/null binding from RESET fixture insert. Added an opt-in isolated H2 rehearsal variant that drops the column after seed and before RESET.
- Both retired-column and original transition-schema full H2 rehearsals passed; `reset-column-independent-rebuild.json`. Real application databases untouched. MySQL retired-schema and guarded physical retirement/restore remain pending.

### RESET MySQL without legacy column
- Actual MySQL migrated-schema rehearsals passed both with physical definition_json removed and retained. RESET rebuilds2 OWL revisions, restores target ontology/source-change run from backup, retains unrelated graph, and rejects schema-drift restore. Disposable databases were dropped.
- `reset-column-independent-mysql.json` records logs. This verifies same-schema backup restore, not reinstating a removed column. Application retirement/DDL recovery remains pending.

### Legacy column retirement preflight
- Added standalone read-only LegacyColumnRetirementGuard: checks all retained revision rows, rejects non-null legacy JSON, absent OWL model, missing required fields, unsupported syntax and document digest mismatch; returns deterministic ordered-row fingerprint.
- Standalone H2 guard test passed for empty/new rows and rejection controls; restored input fingerprint proves no guard mutations. No DDL or deletion entry point exists yet. Full OWL/import validity, writer fence, schema backup and matching restore remain required before retirement.

### OWL-backed retirement guard
- Retirement preflight now reuses OwlDocumentAdapter for parse and DL profile, verifies LockedImport metadata/content digests, compares ontology/version identity and decodes BusinessPolicySet. Requires existing application/OWL classpath; plain JDK-only compilation is no longer sufficient for this guard.
- Standalone H2 controls passed including valid-hash malformed OWL, import-lock mismatch, wrong ontology identity and invalid policy JSON. Evidence `retirement-owl-guard.json`. Writer isolation, schema manifest and DDL/restore execution are still pending; no database columns changed.

### Guarded DDL executor initial H2 verification
- Added LegacyColumnRetirement plan/apply/restore CLI. Manifest pins target, revision data and column metadata; mutations require the offline writer-fence environment. Only verified all-NULL nullable text column is eligible. APPLYING/RESTORING states are written before DDL; repeats use observed schema and state.
- H2 positive chain and document-drift rejection passed; `retirement-ddl-h2.json`. This is initial isolated evidence only: native MySQL, missing fence, interrupted-state tests and schema dependency audit still required before application use. No application DDL executed.

### Retirement fence and DDL interruption recovery
- Added reusable run-retirement-rehearsal.py using current application test classpath and disposable MySQL schemas. Four runs passed: H2/MySQL missing writer fence refusal, H2/MySQL DDL/idempotency/data-drift/interruption recovery.
- Simulated interruption after DROP with APPLYING manifest and after ADD with RESTORING manifest; resumed to APPLIED/RESTORED and verified original document fingerprint. Evidence `retirement-rehearsals.json`.
- These are synthetic schema checks; schema dependency audit and actual migrated-schema/application retirement remain pending. No application databases changed.

### Retirement on migrated MySQL schema and CHECK dependencies
- Optional SEMANTIC_RESET_TEST_COLUMN_EXECUTOR=1 runs plan/apply/restore on exact V191-V207 MySQL metadata before seed, then completes existing RESET/backup restore checks. Passed; `retirement-migrated-mysql.json`. Empty-schema DDL proof complements earlier nonempty synthetic document tests.
- Added explicit CHECK-constraint dependency rejection so H2 cannot silently lose a column constraint. All four H2/MySQL fence/recovery rehearsals pass with this negative control. No application DDL executed.


## 2026-09-09 — isolated runtime physical legacy column retirement

- Fixed the executor's real H2 TEXT/VARCHAR metadata mismatch; recorded original SQL type and precision for faithful restoration. Five H2/MySQL guard/DDL/recovery rehearsals pass.
- Stopped isolated runtime PID 20612, saved offline database backup, preflighted 10 revisions, dropped definition_json and repeated apply to verify document fingerprint and retired schema.
- Restarted isolated service (session 34504); health 200. New draft INSERT/UPDATE/publish, subsequent draft edit preserving history and separate publication readback passed without the legacy column.
- Evidence: retirement-isolated-runtime.json and ../semantic-owl-runtime/column-retired-persistence-readback.json. Main database untouched. Full roadmap remains active: broader capability acceptance, external model evaluation, Kingbase and two bounded W3C timeout cases are not proved complete.


## 2026-09-09 — M8 current accepted fact count guard

Added acceptedFactLimitRejectsBeforeStaging: bulk-load 10,001 current accepted revisions from an HTTP-accepted revision shape; assert GRAPH_STATEMENT_LIMIT, no plan, unchanged binding/version and all statement heads preserved. H2 targeted 1/1 and real isolated MySQL 35/35 passed, no skips. Evidence: m8-fact-limit.json. This verifies overflow refusal, not successful migration of 10,000 facts.


## 2026-09-09 — M8 persisted role revocation

Added revokedAdminCannotApproveOrExecutePreparedPlan using real WorkspaceService membership changes and authenticated HTTP requests. Demotion after preparation rejects approval; restoring the role allows the same request; subsequent demotion rejects execution. Plan states and graph binding/version remain unchanged on denial. H2 targeted 1/1 passed. Evidence: m8-revoked-role.json. Prior MySQL 35/35 excludes this newly added case.


## 2026-09-09 — M8 role revocation MySQL verification

Real isolated MySQL regression 36/36 passed with no skips, including revokedAdminCannotApproveOrExecutePreparedPlan. Evidence: m8-revoked-role-mysql.json. The previous pending-MySQL note is superseded. A source-backed remaining mapping gate is recorded in m8-mapping-coverage.md; nested/punned HTTP execution and rollback remains unverified.


## 2026-09-09 — M8 nested/punned full HTTP migration

Added nestedPunnedAssertionsSurviveMigrationAndRollback. H2 targeted 1/1 passes nested class/property remapping, independent punned individual identity, literal preservation, persisted target revision/evidence, and append-only source rollback. Evidence: m8-punning-h2.json. Real MySQL verification of this new case remains pending.


## 2026-09-09 — M8 nested/punned MySQL and UI checks

Real isolated MySQL 37/37 passed, including nestedPunnedAssertionsSurviveMigrationAndRollback. UI semantic Vitest 25 files/61 tests, vue-tsc and eslint all pass. Evidence: m8-punning-mysql.json and ui-regression-current.json. Browser acceptance and the reported UI appearance issue remain separate unverified items.


## 2026-09-09 — full-suite shutdown defect and fix

Original full run produced 5366 test results with 0 assertion failures/errors and 39 skips, but hung in SpringApplicationShutdownHook awaiting taskScheduler. The verified shutdown JVM was stopped; Maven exit 1 is recorded, not a clean pass. Minimal delayed-task reproduction failed on original configuration. SchedulingConfig now cancels future delayed tasks at shutdown while preserving graceful completion of running tasks. Regression before fails / after passes; targeted Maven SchedulingConfigTest exits 0. Full-suite rerun required. Evidence: scheduler-shutdown-fix.json and full-backend-current-run.json.


## 2026-09-09 — corrected full suite completed

Maven session 51837 exited 0; fresh report totals {'tests': 5367, 'failures': 0, 'errors': 0, 'skipped': 39}. No scheduler termination timeout or Surefire kill-self markers. Evidence: full-backend-fixed-run.json. Skips remain explicit; independent MySQL evidence is separate. Actual IAB tab 5 shows mobile topbar/drawer with served enterprise profile; ui-narrow-layout-observation.json. Scheduling fix still needs package/runtime validation.


## 2026-09-09 — packaged scheduling fix runtime verified

Package exit 0; packaged SchedulingConfig.class matches compiled fix. Isolated H2 service starts, ontology/publication/source/decision readback passes. SIGTERM exits the verified fixed service in 0.51s. Same package restarted, health 200, active session 32137. Evidence: scheduler-fixed-runtime.json. Main database untouched.


## 2026-09-09 — actual responsive UI comparison

Playwright owl-acceptance session logged into isolated 5189. At 1440px enterprise profile and desktop header observed; at 640px same enterprise profile with mobile header. Editor retains OWL Functional Syntax, axioms, sources and export controls. No ontology content changed, viewport restored to 1440. Evidence: ui-responsive-comparison.json. This diagnoses the reproduced layout difference, not the still-pending complex editing/export acceptance or exact user intended comparison.


## 2026-09-09 — complex UI acceptance exposed label form gap

Browser-created ontology 2097590293872402433 saved an 8-axiom Functional document with nested intersection/existential/universal/union/complement and HasKey. Actual axiom panel has only add/delete, no planned simple label form. Gate remains incomplete; implement form before continuing export structural comparison. Evidence: ui-complex-edit-progress.json.


## 2026-09-09 — simple label form implemented and browser-saved

Added OntologyLabelEditor + restricted simple-label helper, reusing atomic REMOVE/ADD through draft.edit CAS. Disabled while text is dirty/busy; annotated/non-simple forms remain document-only. Escaping, rejection and no-flattening tests 3/3 pass; typecheck/lint pass. Browser selected Equipment and saved Chinese label; independent GET reads draftVersion 3. Export structural comparison remains pending. Evidence: ui-complex-edit-progress.json.


## 2026-09-09 — browser discovered draft export 404

Both editor export buttons used the published-revision endpoint with a draft ID. Added GET draft/document with expectedDraftVersion, existing viewer scope and read-only export; published export remains published-only. UI uses draft endpoint and disables unsaved/busy export. Backend targeted test covers both formats, stale version, unauthorized workspace (403), wrong resource workspace (404), published-only route and unchanged draft. Test passes after correcting expected pre-resource permission status. UI 26 files/64 tests, typecheck and eslint pass. New backend not packaged yet; browser download and structural comparison remain pending.


## 2026-09-09 — actual UI downloads structurally verified

Updated isolated package (session 74569) enables draft export. Browser first exposed second defect: global interceptor already unwraps ArrayBuffer, but export functions returned response.data, producing literal undefined files. Fixed both draft and published export; real-interceptor API regression and vue-tsc pass. Browser downloads now parse in OWLAPI and exactly match input axiom set except desired label edit, with same ontology/version IRI, annotations and imports. 8 axioms in both formats. Evidence: ui-export-structural.json. File-upload import path remains pending; fixture has empty ontology annotations/imports.


## 2026-09-09 — standard file upload and RDF/XML reimport

Extended package dialog to wrap raw Functional/RDF XML files for existing strict server preview/import, preserving JSON raw text for duplicate-key detection and requiring package locks for external imports. Helper 3 tests/typecheck/lint pass. Browser uploaded actual RDF export, preview reported 8 axioms/0 imports, created ontology 2097596307644575746. Independent draft readback reparsed by OWLAPI matches original except intended label, IDs/annotations/imports unchanged. Functional file upload still to exercise separately.


## 2026-09-09 — Functional file upload accepted

Browser uploaded actual .ofn export; preview 8 axioms/0 imports, created 2097597038992781313. Independent GET draft reparsed by OWLAPI matches original except intended label, preserving ontology/version ID and axiom structure. Both raw RDF/XML and Functional file uploads verified for this fixture. Frontend semantic suite/typecheck/lint pass; counts in /tmp/owl-import-final-ui-tests.log. Full OWL matrix and locked-import browser coverage remain separate gates.


## 2026-09-09 — raw file import cannot bypass locked dependencies

New package integration test submits a standard document with unresolved imports and no lock. Preview and direct import both reject 422, no ontology or command row created. H2 targeted 1/1 passed. Evidence: unlocked-file-import.json. Browser error display and MySQL execution remain outside this new evidence.


## 2026-09-09 — missing import lock verified on real MySQL

Isolated MySQL regression 38/38 passed without skips, including standardDocumentWithoutItsImportLockCannotCreatePartialOntology. Evidence: unlocked-file-import-mysql.json. Draft export specialized test is not inherited by this suite and is not claimed as MySQL-tested.

### CTX complex structure verification
- SemanticContextIntegrationTest: 4/4 passed. New paginated ordinary-Agent fixture is reparsed with OWLAPI and compared by complete axiom-set equality, preserving nested restrictions, cardinality, annotations, keys and anonymous class assertions.
- Evidence: `context-complex-structure.json`. H2 and mocked KB visibility only; full CTX matrix and real model evaluation remain open.

### CTX optional reasoning integration
- Implemented opt-in same-snapshot reasoning in SemanticContextService/DTOs/tool. Worker executes outside read transactions; inference outcomes remain distinct from assertions/facts and preserve input digest.
- Conclusions participate in bounded signed pagination. Cached continuations recheck all reasoning evidence, including facts outside the question slice. Capacity 64; per-run serialized limit 256 KiB; expiry 10 minutes; stale continuations never silently rerun.
- Semantic regression: 191 results, 0 failures/errors, 38 opt-in skips. Disposable MySQL: 61/61 pass, including 23 inherited context cases. Separate real HermiT callback produces transitive conclusion on H2 and MySQL. Independent read-only review found no actionable issues (no separate diagnostics run).
- Repackaged and restarted isolated H2 service on 18109. Historical ontology/entity/statement/source/review readback passed. Context and reasoning tool groups were found registered but disabled; enabled only those two read-only groups and confirmed availability. Main database unchanged; no real LLM conversation claimed.
- Evidence: context-inference-semantic-regression.json, context-inference-mysql.json, context-inference-runtime.json.
- Signed-expired cursor test passed separately on H2 without wall-clock sleep; worker invocation count remains one. Evidence: context-inference-expiry.json.

### M8 accepted-fact browser chain and empty graph fix
- Seeded dedicated synthetic ontology v1/v2, one entity, one evidence-backed ACCEPTED decimal fact. Used real browser to map class/data property, prepare, approve, execute and rollback. API confirmed prepare has no online effect; execution appends revision 3; rollback appends revision 4; original revisions and evidence remain unchanged.
- Switched to a separate synthetic workspace: old plan absent and scoped not-found displayed. Returning to Default restores the plan. Evidence: m8-fact-browser.json.
- Browser exposed ECharts initialization on a zero-height empty graph. Added no-edges/dimension guard and ResizeObserver lifecycle for hidden tabs. Regression was red before fix; 3 graph tests pass afterward; real browser now reports 0 console errors.
- Frontend semantic tests 69/69, 27 files; enterprise production build and scoped ESLint pass. Repackaged current UI and restarted isolated H2 server. All migration/fact/entity/history fields read back unchanged, normalizing only Java Set signatureIris ordering across JVMs. Runtime jar contains the exact enterprise index built in this run. Evidence: m8-fact-restart.json.
- Changed runtime fixture/readback scripts: migration-fact-fixture.py and migration-fact-readback.py. Main database untouched. Browser evidence uses synthetic data; no industrial outcome claim or Kingbase verification.

## 2026-09-09 — M7 source review acceptance and restart

Shared-source tests: H2 source suite 6/6, disposable MySQL total 64/64 including source suite 6/6. Browser deletion scenario independently retains one binding and marks another for remodeling without changing published axioms or creating a draft. Fixed stale binding states after persisted decisions; regression red/green, frontend 70/70, enterprise build and scoped ESLint passed. Isolated H2 restart readback confirms decisions, snapshots and publication unchanged; runtime jar and enterprise index match build. See m7-acceptance-index.md and m7-source-browser.json. Synthetic fixtures and partial KB mocks do not establish industrial benefit; full goal remains incomplete.

## 2026-09-09 — CTX requirement audit and published-version isolation

Added v2 publication plus further draft to pinned-context regression; graph still returns v1 with no newer vocabulary. SemanticContextIntegrationTest H2 5/5 passed (context-version-published.json). context-acceptance-index.md maps all eight scenarios and explicitly retains trace/tool-list/write-isolation and cross-suite evidence gaps. Test-only change; runtime package unchanged.

## 2026-09-09 — CTX no-write and Unicode evidence

Real HermiT context callback now asserts every discovered mate_semantic_* table is unchanged by comparing all serialized rows before/after. Evidence includes supplementary Unicode code-point bounds. H2 context suite 5/5 and disposable MySQL total 64/64 passed; see context-readonly-unicode.json. Actual assembled Agent tool-list authorization remains a separate gap.

## 2026-09-09 — ordinary Agent authoring exposure fixed

Found legacy unbound agents inherit all globally enabled tools, including ontology authoring. Added shared applyEffectiveToolScope policy: null/global defaults exclude OntologyAuthoringTool and its runtime callbacks; explicit bindings retain normal allowlist behavior. Applied to graph builder, progressive bridge fallback and extension activation. Actual annotated context/authoring beans verify default, explicit authoring and context-only configurations; four focused suites total 11/11 pass. Evidence context-tool-scope.json. Production change needs packaging/runtime acceptance and broader final regression; full goal not complete.

## 2026-09-09 — real tool registry integration and packaged runtime

Real Spring ToolRegistry plus H2 agent binding verifies globally enabled authoring excluded from ordinary defaults, explicit grant available and revocation removed. New integration plus AgentBindingServiceTest 34/34 pass (context-real-tool-registry.json). Package succeeded; isolated service cleanly stopped/restarted, health UP, historical ontology/graph/source readback passed. Jar byte equality and policy/three caller class equality with target confirmed; enterprise index retained (context-tool-scope-runtime.json). Existing already-compiled conversation refresh is not proved by this test.

## 2026-09-09 — annotation semantics matrix evidence

Added real worker comparison for OWL-072..078: ontology/axiom/nested annotations and annotation assertion/subproperty/domain/range preserve class classification, and annotation domain cannot imply class subsumption. Targeted test 1/1 passes; matrix-annotation-no-entailment.json records exact scope. Matrix remains incomplete: syntax-negative totals do not prove semantic-negative requirements, and adapter roundtrip does not prove persisted context. Test-only change.

## 2026-09-09 — preserve ontology metadata in ordinary context

Found ontology-level annotations absent from context despite axiom annotations surviving. Added separate ontologyAnnotations response items with nested descriptors and origin IDs, integrated into snapshot digest/budget/pagination, with omittedOntologyAnnotations counter. Twelve nested root annotations reconstruct exactly across pages; context and reasoning suites 25 tests pass. See context-ontology-annotations.json. Imported annotation origin needs dedicated acceptance, and new production DTO/service not packaged yet.

## 2026-09-09 — imported annotation origin and MySQL acceptance

Added context integration for locked imported ontology annotations: artifact identity, nested descriptor, pinned revision and all-table no-write readback. H2 context 7/7 and disposable MySQL total 66/66 pass (context-import-annotations.json, context-annotation-mysql.json). Initial assertion corrected for standard typed string rendering (xsd:string), not a production semantic change. Annotation production DTO/service still needs updated package/runtime verification.

## 2026-09-09 — annotation context packaged and isolated runtime refreshed

Package exit 0; existing isolated service stopped cleanly and restarted. Health UP and historical ontology/graph/source readback passed. Runtime jar equals target; new annotation DTO/service class bytes and enterprise index match built resources (context-annotations-runtime.json). This is package/readback evidence, not a real LLM annotation response.

## 2026-09-09 — published ordinary context across 74 matrix constructs

Added parameterized integration directly reading frozen capability-matrix positive examples OWL-005..078. Each saves, publishes, binds graph, paginates ordinary Agent context and compares complete rendered axiom sets plus ontology annotation descriptors against standard parsed source. Asserts revision, duplicate prevention, budget and no ABox business-fact promotion. H2 74/74 passes (context-matrix-published.json). Other rows, parameter dimensions, negative semantics and MySQL extension remain distinct. Test-only change.

## 2026-09-09 — MySQL published context matrix

Disposable MySQL completed exit 0, 140/140 tests with zero failures/errors/skips, including 74 OWL-005..078 save/publish/bind/context-pagination cases. Evidence context-matrix-mysql.json; runner drops both temporary schemas. Standard ontologyIRI/versionIRI absent from current context metadata remains the next concrete contract gap; internal IDs are not substitutes.

## 2026-09-09 — standard OWL identity in context

Added ontologyIri/versionIri from pinned parsed document to context metadata. Regression verifies v1 remains after v2 publication and unversioned ontology stays null. Context plus reasoning suites 101 pass, including 74 matrix examples and pagination budgets (context-standard-identity.json). H2 only for new identity case; production package refresh pending.

## 2026-09-09 — identity MySQL gate and runtime

MySQL identity regression 1/1 passes on disposable schemas. Added validated --tests selector to temporary MySQL harness to run changed cases without repeating unchanged matrix. Package exit 0, isolated service restarted, health UP and historical readback pass; runtime jar/class bytes match build and enterprise index preserved (context-standard-identity-runtime.json). No main database change.

## 2026-09-09 — prefix aliases and Unicode context

H2 integration verifies two different prefix aliases produce identical published context axiom sets, preserving Unicode IRI, quoted Chinese label and language tag. Targeted 1/1 passes (context-prefix-unicode.json). Corrected test assumption that every axiom mentions the entity: publication also includes annotation property declarations. Production unchanged.

## 2026-09-09 — cardinality parameter dimensions in published context

Expanded Object/Data × Min/Max/Exact × 0/1/2 × qualified/unqualified to 36 save/publish/bind/context roundtrip cases. All 36 pass on H2, retaining original constraints, revision, estimated budgets and no ABox business-fact promotion. Evidence context-cardinality-dimensions.json; covers positive context dimensions of OWL-023..025/029..031/086, not all inference/global-restriction negatives. Production unchanged.

## 2026-09-09 — VAL provider live recheck

Rechecked frozen experiment provider/model gptsapi/gpt-4o-mini using minimal connectivity-only request. Current response HTTP 403, no insufficient-balance marker; prior HTTP 401/balance diagnosis cannot be assumed current. No formal question executed and no score produced. Sanitized evidence val-provider-recheck.json; credentials remain in memory. Requested usable model config ID or repair without asking for secrets. Other engineering acceptance remains actionable, so overall goal stays active.

## 2026-09-09 — global DL restriction negative preservation

Eight new adapter cases pass for non-simple role restrictions, object/data property punning and cyclic datatype definition. Every case requires nonempty PROFILE_VIOLATION diagnostic/path, unchanged axioms, full Functional export/reimport preservation and continued rejection. Evidence matrix-global-restriction-negatives.json. This does not replace HTTP publication gating or exhaustive role regularity/datatype coverage. Test-only change.

## 2026-09-09 — HTTP global restriction publication gate

Eight invalid combinations now tested through real save/publish APIs after valid baseline publication. Invalid draft save succeeds, publication returns 422, draft and revision list and original publication remain exactly unchanged. H2 8/8 passes (matrix-global-publish-gate.json); no new production change. New suite has not run against MySQL.

## 2026-09-09 — MySQL global restriction publication gate

MySQL subclass executes same eight invalid-publication transactions on fresh disposable database: 8/8 pass, zero failures/errors/skips; draft and history remain unchanged after 422. Runner exit 0 and removes temporary schemas. Evidence matrix-global-publish-mysql.json. No production changes.

## 2026-09-09 — latest full backend regression completed

Root mvn test session 76250 exited 0 with BUILD SUCCESS. All 798 XML reports newer than log creation: 5583 results, 5520 passed, 63 skipped, zero failures/errors. Conditional skip reasons and report hashes stored in full-backend-latest-run.json. Includes current identity, annotations, tool scope and new matrix/publication tests. Skips are not counted as passes and parameterized skipped-method counts are not equivalent to expanded executed cases. Overall OWL/VAL goal remains incomplete.

## 2026-09-09 — role hierarchy regularity boundary

Added OWL-080 probe: regular chain succeeds, mutual strict-order cycle fails with cycle diagnostic and both axioms preserved, legal transitivity exception succeeds. HTTP global restriction suite expanded to 9/9 passing with regularity rejection preserving draft/history. Evidence matrix-role-regularity.json. Test-only changes after latest full backend regression; no production rebuild needed. Added MySQL case remains unexecuted.

## 2026-09-09 — regularity MySQL and reserved vocabulary

Global restriction publication suite including nonregular property chains passes MySQL 9/9 (matrix-role-regularity-mysql.json). Added three reserved vocabulary entity-category probes, all pass: invalid declarations rejected without deletion, legitimate owl:Thing/owl:Nothing remain allowed (matrix-reserved-vocabulary.json). Test-only changes; neither is exhaustive vocabulary/regularity coverage.

## 2026-09-09 — explicit 435-item evidence ledger

Added reproducible index of all 87×5 frozen specification IDs, with explicit bounded example/partial/unmapped classification and existing evidence references. Counts 311 VERIFIED_EXAMPLE, 50 PARTIAL, 74 MISSING_DIRECT_EVIDENCE are not completion percentages. matrix-evidence-audit.md defines next evidence audit priorities; unmapped items require checking existing tests before adding more. No product changes and no overall completion claim.
