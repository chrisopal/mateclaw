# Enterprise Semantic Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 MateClaw 仓库交付一个小型企业语义核心、本体管理维护界面、可信事实图谱及可取证的 Agent 查询闭环。

**Architecture:** 新增一个无框架 Java Maven 模块 `mateclaw-semantic-core`；现有 Server 负责权限、事务、持久化与工具注册。复用现有数据库、Flyway、Vue/Element Plus/ECharts 和企业主题，同进程部署。

**Tech Stack:** Java 21、Spring Boot 3.5.16、MyBatis Plus、Flyway、H2/MySQL、Vue 3、TypeScript、Element Plus、Pinia、Vue I18n、ECharts、JUnit、ArchUnit、Vitest。

## Global Constraints

- 2026-09-07已按用户授权完成M1（SEM-01～03），其余SEM-04～11尚未实施。M1验收见 ../../validation/ontology-m1/acceptance.md；复选框不能替代证据。
- 代码基线为 `codex/enterprise-ui@04dde691`；实施前刷新 git 状态和迁移编号。
- 只新增一个 Maven 模块；不在外部 Semantica4j 工作区实施，不导入其 BOM/实验 records。
- core 生产依赖为 JDK-only；不新增生产库，测试复用仓库已有 JUnit/ArchUnit。
- 同 KB 一图、一图固定一个已发布本体版本；跨图关系、非空图本体迁移、自动推理和 GraphRAG 不在范围。
- 本体界面必须支持新建、类型/属性/关系维护、草稿保存、校验、版本对比和发布。
- 本体发布内容和事实历史不可覆盖；候选和修改候选不替换当前正式事实。
- Scope 与 Actor 分离；应用服务授权覆盖 HTTP、工具及任务，缺失 Scope 拒绝。
- 所有 ID 对外为字符串，DECIMAL 为字符串；正文/证据不能进入日志。
- 仅新增迁移；三方言同版本同语义，不改旧 SQL，不依赖 Flyway repair 掩盖旧脚本修改。
- H2/MySQL 真环境与真实模型调用分别验收；Kingbase/Postgres 没有实跑就明确标未验证。
- 保留现有 UI/登录/工作区/Wiki。功能开关和权限独立于 classic/enterprise 外观开关。
- 不提交其他未跟踪目录，不自动 push/部署。每个代码提交采用 Lore 意图行和相关 Tested/Not-tested/Scope-risk trailers。

---

## 0. 阅读、执行与文件布局

必读：[总体设计](../specs/2026-09-05-enterprise-semantic-core.md)、[领域模型](../../architecture/2026-09-05-semantic-domain-model.md)、[本体管理规格](../../architecture/2026-09-05-ontology-management-ui-spec.md)、[词汇](../../../CONTEXT.md)、[ADR](../../adr/0001-semantic-knowledge-ontology-fact-boundaries.md)。设计约束优先于实现便利；发生新范围调整时先同步这些文档。

实施时以当前企业基线创建 `codex/enterprise-semantic-core` 分支或隔离 worktree，按用户对工作区的明确指示选择。将本轮指定 CONTEXT/ADR/spec/plan 文件携带过去并校验内容，不能因它们未跟踪而遗漏；不搬运 `.omx/`、其他 UI 标准目录。本文自身不执行建分支或提交。

路径简写仅用于阅读：`CORE=mateclaw-semantic-core/src/main/java/vip/mate/semantic/core`；`SERVER=mateclaw-server/src/main/java/vip/mate/semantic`；`UI=mateclaw-ui/src/features/semantic`。以下 Files 给出仓库相对的实际文件/目录，均在 `/Users/guojiexie/Development/mateclaw` 内。

| 自有目录 | 责任 |
|---|---|
| core/identity、validation、ontology、fact、evidence、conflict | 明确类型与纯领域规则 |
| server semantic/ontology、graph、source、statement、query | 用例及各自 Mapper/持久化对象 |
| server semantic/security、web、tool、config | 宿主装配边界 |
| UI api、ontology、graph、review、shared | 复用现有 http 的客户端及业务界面 |

每个任务包含“具体反例 → 红灯 → 最小实现 → 绿灯 → 证据/提交”。任务大小按可独立验收的行为切分，不创建只含 ModuleMarker 的占位任务。

## 1. 里程碑与依赖

```text
SEM-01 本体核心 ── SEM-02 本体 API/存储 ── SEM-03 本体管理 UI
       │                   │                     │
       │                   └── SEM-04 图绑定/实体 ┘
       └── SEM-05 事实/证据/冲突规则
                         │
              SEM-04 + SEM-05 → SEM-06 来源与候选持久化
                                      ↓
                              SEM-07 审核/冲突/撤回事务
                                      ↓
                              SEM-08 可信查询与证据 API
                                      ↓
                         SEM-09 工作台 UI / SEM-10 Agent 工具
                                      ↓
                              SEM-11 全链路验收与运行文档
```

M1：SEM-01～03 后，可通过 UI 管理发布本体；M2：SEM-04～08 后，真实数据库能管理可信事实；M3：SEM-09～11 后，完整产品及 Agent 闭环验收。M1/M2 不能标整项完成。

允许的并行：SEM-03 与 SEM-05；SEM-09 与 SEM-10。共享 POM、能力映射、菜单和迁移编号由主执行者串行管理；每个子任务指定所有权，不回退别人修改。

## 2. 验证命令约定

以下均在实施后运行，本计划编写阶段未执行。定向测试用 `surefire.failIfNoSpecifiedTests=false` 只允许 reactor 中非目标模块没有该测试名；执行者必须确认目标类被执行且非零 tests，不能用 0 tests 声称通过。

```bash
# core 全量测试
mvn -pl mateclaw-semantic-core -am -Dmaven.compiler.proc=full test
# server 指定测试：将测试名替换为具体任务给出的类名
mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=SemanticOntologyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# 前端：从 mateclaw-ui 目录运行，避免脚本缺失的 precision 门禁
pnpm exec vitest run src/features/semantic
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/semantic
pnpm exec vite build --mode enterprise --outDir dist/enterprise
```

完整收尾还需 server 测试、UI 全量 Vitest/ESLint、双 profile 构建和认证浏览器验收。旧脚本缺失及旧 lint 问题单列并复现，不修改无关代码来“凑绿”。

## SEM-01：可运行的本体验证核心

**依赖：** 无。**产出：** 新模块已参与 Reactor，能验证一套设备本体；没有 Server/存储依赖。

**Files**
- Create: `mateclaw-semantic-core/pom.xml`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/identity/{SemanticIds,GraphScope}.java`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/validation/{ValidationReport,Violation}.java`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/ontology/{OntologyDefinition,OntologyRevision,EntityTypeDefinition,PropertyDefinition,RelationDefinition,ValueType,Multiplicity,OntologyValidator}.java`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/fact/Entity.java`（供SEM-04持久化和SEM-05验证共同使用的纯核心身份对象）。
- Test: `mateclaw-semantic-core/src/test/java/vip/mate/semantic/core/ontology/OntologyValidatorTest.java`。
- Modify: `pom.xml` 的 modules 和内部依赖管理；core 放在 server 前；`mateclaw-server/pom.xml` 依赖新模块。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/SemanticCoreArchitectureTest.java`。

**Interfaces**：输出总体设计 §4.2 的 validate 方法与类型。OntologyDefinition 由 types/properties/relations 三个不可变 List 构成。EntityTypeDefinition 参数为 key,label,description；RelationDefinition 为 key,label,description,sourceTypeKey,targetTypeKey,multiplicity；PropertyDefinition 为 key,label,description,ownerTypeKey,valueType,multiplicity,fixedUnit（Optional<String>）。

- [x] 建立上述 record/enum 的最小可编译契约和测试模块；测试只引入已有 JUnit，Server 复用已有 ArchUnit。
- [x] 写真实无效定义测试，再运行 core 测试确认失败：

```java
var definition = new OntologyDefinition(
    List.of(new EntityTypeDefinition("Equipment", "设备", "")), List.of(),
    List.of(new RelationDefinition("hasPart", "安装部件", "",
        "Equipment", "MissingType", Multiplicity.MULTI)));
assertFalse(new OntologyValidator().validate(definition).valid());
```

- [x] 实现重复 key、缺失起终点/所属类型、非法单位组合、空集合/长度上限、标识符校验；Violation 返回精确字段路径，如 `relations[0].targetTypeKey`。
- [x] 添加合法 Equipment/Component 本体、中文标签、不可变集合、相同 key 类型边界测试；ID 使用明确 record，拒绝空/负/带路径分隔符。
- [x] 架构测试扫描实际 core 类，先断言发现非零生产类，再禁止 Spring/MyBatis/Jackson/Spring AI/宿主包依赖；用 test fixture 的非法依赖证明规则可失败。
- [x] 运行 core 全测及 `SemanticCoreArchitectureTest`，记录测试数/报告；检查 root/server POM diff 仅是装配。提交意图：`Keep enterprise ontology rules independent of the host framework`。

## SEM-02：本体草稿、发布、版本与权限 API

**依赖：** SEM-01。**产出：** 真实数据库上完整本体维护 API；为 UI 提供稳定契约。

**Files**
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/config/SemanticProperties.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/security/{SemanticAccessService,SemanticPrincipalResolver}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/ontology/{OntologyApplicationService,OntologyMapper,OntologyRow,OntologyRevisionRow,OntologyWireMapper}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/{OntologyController,OntologyDtos,SemanticApiException,SemanticExceptionHandler}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/SemanticStatusController.java`（始终装配，GET /semantic/status 受现有认证保护，只返回 enabled）。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/statement/{CommandRecordMapper,GovernanceRecordMapper}.java`（跨用例原子操作/治理记录，后续复用）。
- Create: `mateclaw-server/src/main/resources/db/migration/{h2,mysql,kingbase}/V191__semantic_ontology.sql`，实施前扫描编号，冲突则三方言统一使用新号并更新本计划。
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/core/security/{Capability,RoleCapabilities}.java`。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/{SemanticOntologyIntegrationTest,SemanticAuthorizationTest,SemanticMigrationTest}.java`。
- Test support: `mateclaw-server/src/test/java/vip/mate/semantic/support/SemanticHttpFixture.java`。

**Interfaces**：实现设计 §8 ontology/draft/validate/publish/revisions/diff/availability/operations 全部端点。SemanticHttpFixture 用现有用户/工作区服务建立 owner/member/viewer 和两个工作区，再通过真实 `/api/v1/auth/login` 取得 `bearer(role)`；测试密码只在测试内随机生成，不读取用户数据库。

- [x] 创建 HTTP 集成反例：viewer 保存被拒、缺 Scope 被拒、跨 workspace revision 404、同草稿并发保存仅一个成功、旧校验报告不能发布新内容。
- [x] 运行上述三个测试，确认失败理由是语义入口/规则缺失，不是测试数据库或认证准备失败。
- [x] 追加三方言 ontology/revision/command_record/governance_record 表；父行锁维护唯一活动草稿；编写空库和从 V190 升级的隔离数据库测试。保留旧迁移 SHA 清单作前后比较。
- [x] 实现草稿 CAS、基于稳定 key 的差异、发布时重验、精确版本只读、availability 治理；发布请求同 operationId 同 payload 回读原结果、异 payload 返回 409。

```java
// 发布事务的必要顺序（调用均在 OntologyApplicationService 内实现）
lockOntology(ontologyId);
assertDraftVersion(ontologyId, expectedDraftVersion);
requireValid(validator.validate(loadDraftDefinition(ontologyId)));
// 发布版本、operationId 结果和治理记录同事务持久化；任一步失败全部回滚。
```

- [x] 增加 capability 映射；每 Controller 方法有角色注解，应用服务再次依据真实 principal/Scope 验证；模块默认关闭。SemanticExceptionHandler 限定语义 Controller，保留 HTTP 状态和 R envelope，422.data 带字段错误，409 带稳定冲突码。
- [x] 实现常驻status端点和业务端点条件装配；SemanticAuthorizationTest验证模块关闭时status仍能供登录用户读取，而ontology业务端点不可访问。UI不能只根据静态capabilities展示被关闭的入口。
- [x] 通过以下发布不可变性测试与故障注入：

```java
var v1 = fixture.createAndPublishVoltageOntology();
fixture.editNewDraftLabel(v1.ontologyId(), "设备维护新版");
assertEquals(v1.definition(), fixture.getRevision(v1.revisionId()).definition());
fixture.failNextGovernanceInsert();
assertEquals(500, fixture.publishCurrentDraft().status());
assertEquals(1, fixture.publishedRevisionCount(v1.ontologyId()));
```

测试 fixture 上述方法分别通过已定义 HTTP 端点创建/读取数据，failNextGovernanceInsert 仅替换测试 Bean 使插入抛错；其余 Mapper 和事务用真实 H2。另用 JDBC 回读，不只验证 HTTP 响应。

- [x] 三个测试绿灯且版本/治理记录原子回读；记录 MySQL 方言待 SEM-11 真环境验证；提交意图：`Make ontology publication durable and scoped to its workspace`。

## SEM-03：本体管理界面完整闭环

**依赖：** SEM-02。**产出：** 在真实后端上创建、编辑、校验、对比、发布本体；绑定部分在 SEM-04 完成。

**Files**
- Create: `mateclaw-ui/src/features/semantic/api/{types,ontologyApi,semanticErrors}.ts`。
- Create: `mateclaw-ui/src/features/semantic/ontology/{OntologyList,OntologyEditor,OntologyVersions}.vue`。
- Create: `mateclaw-ui/src/features/semantic/ontology/components/{EntityTypeEditor,PropertyEditor,RelationEditor,ValidationPanel,PublishDialog}.vue`。
- Create: `mateclaw-ui/src/features/semantic/ontology/useOntologyDraft.ts`；`mateclaw-ui/src/features/semantic/routes.ts`。
- Modify: `mateclaw-ui/src/router/index.ts`、`mateclaw-ui/src/views/layout/MainLayout.vue`、`mateclaw-ui/src/composables/capabilities.ts`、`mateclaw-ui/src/i18n/locales/{zh-CN,en-US}.ts`。
- Test: `mateclaw-ui/src/features/semantic/ontology/__tests__/{ontologyDraft,ontologyManagement,ontologyWorkspaceSwitch}.test.ts`。

**Interfaces**：ontologyApi 复用 `import { http } from '@/api'`，实现 API 表对应方法；useOntologyDraft 输出 load/save/validate/publish/discard、form、draftVersion、dirty、validationReport、saveError，参数包含 ontologyId 和 workspaceId getter。请求支持 AbortSignal，缓存 key 含 workspaceId。路由 `/semantic/ontologies`、`/semantic/ontologies/:id/edit`、`/semantic/ontologies/:id/versions`。

feature状态由 `mateclaw-ui/src/features/semantic/shared/useSemanticAvailability.ts`（新建）读取受认证status端点；菜单及新路由同时要求 enabled 与相应capability，失败时不默认开启。它不复制角色到capability映射。

- [x] 用 Vitest 写并发409后表单仍保留、校验报告过期、超大字符串ID不变、工作区切换取消旧结果四个测试；Element Plus 交互测试用 Vue createApp+happy-dom，不新增测试框架。
- [x] 跑这三类测试得到真实红灯，再实现 feature API 解包 R.data、结构化错误和草稿 composable。

```ts
// ontologyDraft 测试中的已有接口 mock 与真正 composable 状态交互。
api.saveDraft.mockRejectedValue({ status: 409, code: 'DRAFT_VERSION_CONFLICT' })
await draft.save()
expect(draft.dirty.value).toBe(true)
expect(draft.form.value.types[0].label).toBe('泵设备')
expect(draft.saveError.value?.code).toBe('DRAFT_VERSION_CONFLICT')
```

测试建立的 api mock 提供与 ontologyApi 相同的方法；draft 由 useOntologyDraft 注入 api 测试依赖创建，所有测试运行真实状态管理逻辑。

- [x] 实现列表、表单/Drawer 类型维护、字段错误定位、版本差异和发布面板；已发布页面只读，主操作为建立新草稿；无引用的草稿删除可操作，有引用的定义先提示并阻止非法删除。
- [x] 挂接路线、三个 ontology capabilities 和双语文案；MainLayout 的局部 capability union 如仍重复需同步加入新值，勿改造整个导航。复用企业 tokens，不添加主题字面量或第二个 Axios 实例。
- [x] 运行定向 Vitest、vue-tsc、feature ESLint；启动隔离后端，用真实登录手工/浏览器自动化创建发布 v1、从 v1 建草稿发布 v2、刷新回读及历史差异。保存认证截图和 API 回读证据。
- [x] 记录本体可管理、图绑定未交付的阶段边界；提交意图：`Let administrators maintain ontology versions without editing code`。

## SEM-04：知识库图绑定、实体身份与 UI 入口

**依赖：** SEM-02；UI 部分依赖 SEM-03。**产出：** 单 KB 单图，绑定明确版本，实体可登记。

**Files**
- Reuse: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/fact/Entity.java`（由SEM-01创建，本任务不重复定义）。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/graph/{GraphApplicationService,GraphMapper,EntityMapper,GraphRow,EntityRow}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/{GraphController,GraphDtos}.java`。
- Create: `mateclaw-server/src/main/resources/db/migration/{h2,mysql,kingbase}/V192__semantic_graph.sql`（同样按实施时编号重核）。
- Create: `mateclaw-ui/src/features/semantic/graph/KnowledgeBindingPanel.vue`；`mateclaw-ui/src/features/semantic/api/graphApi.ts`。
- Modify: `mateclaw-ui/src/views/Wiki/index.vue` 加独立语义入口，不改变原生 Wiki 图行为；OntologyVersions 加按权限过滤的绑定列表。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/SemanticGraphBindingTest.java`；`mateclaw-ui/src/features/semantic/graph/__tests__/knowledgeBinding.test.ts`。

**Interfaces**：GraphApplicationService.bind(actor,kbId,revisionId,expectedGraphVersion,action)；createEntity(actor,graphId,typeKey,displayName)。actor 为 SemanticPrincipalResolver 输出的可信类型，接口不从请求正文接受 actor。返回 GraphDtos.Binding 与 Entity DTO。

- [ ] 反例覆盖并发双建图、跨工作区本体绑定、停止新绑定版本、存在实体后的换绑、只有来源/任务但无事实的图换绑。对后者先建立检测接口，并在 SEM-06 的真实表出现后补集成测试。
- [ ] 运行 `SemanticGraphBindingTest` 得到红灯，再实现 graph/entity 表、唯一(workspace,kb)、图锁与版本检查。
- [ ] 实现 ENABLE/DISABLE/REBIND 命令，DISABLE 保留数据；绑定不可根据 latest 漂移。实体类型须存在于绑定本体；displayName 不作为去重键。

```java
var binding = fixture.bindEmptyKnowledgeBaseToPublishedV1();
fixture.createEquipmentEntity(binding.graphId(), "P-101");
assertEquals(409, fixture.rebindToV2(binding.graphId()).status());
assertEquals(binding.revisionId(), fixture.readBinding().revisionId());
```

fixture 方法通过 GraphController/SEM-02 API，真实 H2 回读验证唯一约束。

- [ ] UI 显示实际绑定版、可见可选版及非空图限制；新增本体版本后 UI/API 回读旧图仍是旧版。切换工作区不复用旧绑定请求结果。
- [ ] 跑 GraphBindingTest、knowledgeBinding.test.ts 与类型检查；提交意图：`Keep each knowledge graph tied to an explicit ontology revision`。

## SEM-05：事实、证据与确定性冲突核心

**依赖：** SEM-01，可与 SEM-03 并行。**产出：** 无数据库的真实领域规则。

**Files**
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/fact/{StatementContent,StatementRevision,StatementValue,Validity,PredicateRef,ChangeProposal,StatementValidator}.java`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/evidence/{SourceSnapshot,Evidence,EvidenceVerifier}.java`。
- Create: `mateclaw-semantic-core/src/main/java/vip/mate/semantic/core/conflict/{ConflictKind,ConflictFinding,ConflictMemberRef,ConflictDetector}.java`。
- Test: `mateclaw-semantic-core/src/test/java/vip/mate/semantic/core/{StatementValidatorTest,EvidenceVerifierTest,ConflictDetectorTest,SemanticFixtures}.java`。

**Interfaces**：实现总体设计 §4.2 的StatementValidator、EvidenceVerifier、ConflictDetector.compare及detect公共方法。SemanticFixtures 提供 `voltageOntology()`（额定电压DECIMAL/V/SINGLE）、`voltage(String)`（相同图/主体/无界有效期候选）、`acceptedVoltage(String)`（同内容ACCEPTED修订）、`snapshot(String)`、`evidence(int,int,String)`；测试专用 ID 均固定正整数字符串。

- [ ] 编写单值冲突、多值不冲突、时间不重叠不冲突、UNKNOWN 竞争不放行、不同Scope引用拒绝、实体范围类型不符六组测试。先建立最小类型使测试可编译，运行红灯。

```java
var detector = new ConflictDetector();
assertEquals(1, detector.detect(SemanticFixtures.voltageOntology(),
    SemanticFixtures.voltage("400"), List.of(SemanticFixtures.acceptedVoltage("380"))).size());
assertTrue(detector.detect(SemanticFixtures.voltageOntology(),
    SemanticFixtures.voltage("380.0"), List.of(SemanticFixtures.acceptedVoltage("380"))).isEmpty());
assertEquals(Optional.of(ConflictKind.SINGLE_VALUE_DISAGREEMENT),
    detector.compare(SemanticFixtures.voltageOntology(),
        SemanticFixtures.voltage("380"), SemanticFixtures.voltage("400")));
```

- [ ] 实现互斥值类型、UTC半开区间/UNKNOWN、BigDecimal等价、固定单位严格匹配；输入同 scope 且精确本体版本。返回稳定违反码，不把非法类型误当可投票解决的冲突。
- [ ] 证据校验用 `text.offsetByCodePoints(0, start/end)` 转换后 substring；校验 begin/end 范围和 quote 一致；测试含 emoji 的 `设备😀额定380V`，不能直接按 Java char 索引处理 code point。
- [ ] 验证 published/accepted snapshot 构造时防御性复制 collections；ChangeProposal 引用自身 proposalId 和 expectedRevision，ConflictMemberRef 明确区分修订/候选。
- [ ] 运行 core 全测和架构测试，保留新规则红/绿证据；提交意图：`Keep enterprise facts traceable and reject deterministic contradictions`。

## SEM-06：来源快照、导入任务与候选持久化

**依赖：** SEM-04、SEM-05。**产出：** 真实 Wiki 文本固定保存，人工候选及证据可重启回读；尚不自动确认。

**Files**
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/source/{SourceApplicationService,SourceAccessService,SourceSnapshotMapper,EvidenceMapper,ImportJobMapper,ImportJobRunner,SourceSnapshotRow,EvidenceRow,ImportJobRow}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/statement/{StatementMapper,StatementRevisionMapper,ChangeProposalMapper,ConflictMapper,StatementApplicationService}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/{SourceController,SourceDtos,StatementController,StatementDtos}.java`。
- Create: `mateclaw-server/src/main/resources/db/migration/{h2,mysql,kingbase}/V193__semantic_facts_and_sources.sql`，含设计 §5 剩余事实、来源、证据、任务和冲突表。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/{SemanticSourceImportTest,SemanticCandidatePersistenceTest}.java`。
- Test fixture: `mateclaw-server/src/test/resources/semantic/repair-note-zh.txt`（固定中文、emoji、多行文本），`semantic-v1-requests.json`（候选 HTTP 数据及恶意范围变体，不含真实资料）。

**Interfaces**：SourceApplicationService.startImport(actor,graphId,sourceRef,operationId)、readImport(actor,graphId,jobId)、retryImport(...); StatementApplicationService.propose(actor,graphId,StatementDtos.ProposeRequest)、proposeChange(actor,graphId,statementId,expectedRevision,content,operationId)。详情为设计 §8 source/statement API。

- [ ] 写红灯：导入后更新 Wiki chunk 不改变旧快照；错误 quote/offset 拒绝；同 operationId 内容变化409；KB 不属于工作区拒绝；2 MiB文本上限拒绝；重启后候选与证据可回读。
- [ ] 追加三方言表和索引，默认不对原 Wiki 设置级联删除；事实当前列/JSON payload一致性由持久化映射测试保证；每个 Mapper 查询明确 scope 或从已授权资源获取 scope。
- [ ] 导入锁定原始资料身份，在权限检查后固化实际文本、摘要和 captureVersion；持久化任务QUEUED/RUNNING/SUCCEEDED/FAILED、租约和attempts。重启只恢复过期租约，worker重复执行通过operationId防重，失败不伪造成功。
- [ ] SourceController提供来源/快照列表和精确快照text读取，供候选表单选取片段；text读取要求 propose:semantic 或 manage:semantic 加来源访问权限，不以当前Wiki文本替代固定快照。
- [ ] 实现 `POST /graphs/{graphId}/snapshots/{snapshotId}/evidence`，输入operationId/startCodePoint/endCodePoint/exactQuote；服务端重新读取快照用EvidenceVerifier校验，原子保存证据和幂等结果，返回evidenceId。测试错摘要/错quote/跨图/重复提交，浏览器不直接生成可信evidenceId。

```java
var imported = fixture.importRawText("设备😀额定380V");
fixture.replaceCurrentWikiText("设备😀额定400V");
assertEquals("设备😀额定380V", fixture.readSnapshot(imported.snapshotId()).text());
assertEquals(imported.operationResult(), fixture.retrySameImportOperation().operationResult());
```

fixture 通过现有 Wiki 服务写测试 raw，再调用 SourceController；replaceCurrentWikiText 改的只是隔离测试资料。

- [ ] 提供从快照片段提出结构化候选的接口；运行 EvidenceVerifier 与 StatementValidator。候选缺证据允许保存但标明不可确认；类型/单位/范围格式错误直接422。MANUAL_RECORD记录操作者与文字，不绕过证据要求。
- [ ] 在图锁内用ConflictDetector.compare比对新候选与未决PROPOSED/PENDING及ACCEPTED；建立精确成员引用的OPEN Conflict。添加零ACCEPTED、两项互斥PROPOSED的测试，确认已有冲突且正式图仍为空；并发提议也不能漏掉候选间冲突。
- [ ] 来源访问复查在每次查询/任务提交前完成；原 raw 不存在不能显示受保护文本。后台任务使用获准执行的用户ID和资源范围，在执行时再验权限，不能沿用过期授权。
- [ ] 补 SEM-04“只有快照/任务/候选也不是空图”的真实测试；运行 SourceImport、CandidatePersistence、GraphBinding 三类测试。提交意图：`Preserve the exact evidence used to propose enterprise facts`。

## SEM-07：事实审核、修订、冲突与来源治理事务

**依赖：** SEM-06。**产出：** 事实可被确认/拒绝/撤回，修改和冲突有原子决策，来源治理可追踪。

**Files**
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/statement/{StatementReviewService,ConflictResolutionService,SupportEvaluator}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/source/SourceGovernanceService.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/{ReviewController,ReviewDtos}.java`。
- Modify: `StatementApplicationService.java`、SourceController.java、ConflictMapper.java（均为前述任务新建文件）。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/{SemanticReviewTransactionTest,SemanticConflictConcurrencyTest,SemanticSupportStateTest}.java`。

**Interfaces**：reviewStatement(actor,graphId,statementId,expectedRevision,action,reason,operationId)、reviewChange(actor,graphId,proposalId,action,reason,operationId)、resolveConflict(actor,graphId,conflictId,memberRefs,decision,operationId)；withdrawSource 和 excludeSnapshot 必须显式 reason。

- [ ] 写事务反例：两个线程同时确认同一实体同期380V/400V最多一项成功；两个基于r2的修改候选不能先后覆盖；治理记录插入失败全部回滚；成员引用过期不能解决冲突。
- [ ] 跑三个测试红灯，确认使用两个独立事务/连接及同步栅栏，不以顺序mock模拟并发。
- [ ] 图行锁串行重读当前约束；验证待提交最终状态，生成不可变修订，更新当前指针与mutationVersion，保存command/governance/conflict结果同事务。冲突候选保持可审核，不能静默替换已确认事实。

```java
var statuses = fixture.resolveSameConflictConcurrently("380", "400");
assertEquals(1, statuses.stream().filter(s -> s == 200).count());
assertEquals(1, fixture.currentAcceptedDistinctVoltageCount());
assertEquals(1, fixture.successfulConfirmationGovernanceCount());
```

fixture.resolveSameConflictConcurrently 先建立两个候选及一个OPEN Conflict，再用ExecutorService、CountDownLatch和真实HTTP/application transaction分别提交选择不同winner的决定；成功数同时用SQL检查，第二个过期决定应409而不是500。双候选未处理时普通review应均409，不能与这里的resolve并发测试混用。

- [ ] 实现来源全版本撤回与单快照排除；SupportEvaluator 返回SUPPORTED/SUPPORT_LOST派生状态。失去一份证据但仍有其他证据不丢支持；全失支持不物理删事实且仍参与单值约束。
- [ ] 实现冲突列表和处理决定：拒绝候选、批准目标修订、补有效时间后重验；decision必须列出预期成员引用，最终状态仍冲突则409。
- [ ] 普通review不能绕过候选间OPEN Conflict；resolve明确winner/memberRefs与其他成员的reject/revise决定。测试首次380V/400V双候选：两个直接确认均409，经resolve选定并处理另一项后恰好一项ACCEPTED，不能以抢先确认消除待审冲突。
- [ ] viewer/member不可确认，admin/owner及真实KB权限通过才可治理；操作重试回读结果仍校验当前权限。运行三类测试和已有SemanticAuthorizationTest。提交意图：`Keep fact decisions atomic under conflicting evidence and concurrent reviews`。

## SEM-08：可信事实、邻域和证据查询

**依赖：** SEM-07。**产出：** 页面/Agent 共用的一条有界授权查询链。

**Files**
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/query/{SemanticQueryService,SemanticQueryMapper,SemanticQueryDtos}.java`。
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/web/SemanticQueryController.java`。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/{SemanticQueryAuthorizationTest,SemanticGraphQueryTest,SemanticEvidenceReadbackTest}.java`。

**Interfaces**：SemanticQueryService.search(actor,graphId,query,limit,atTime)、neighbors(actor,graphId,entityId,depth,nodeLimit,edgeLimit)、evidence(actor,graphId,evidenceId)、history(actor,graphId,statementId)。结果包含statementId/revision、typedValue、证据引用、支持状态、可见pendingDisputes、traceId和truncated。

同任务补齐 `GET statements?view=trusted|review|mine`、changes列表/详情、按权限分页的审核来源/冲突列表；review视图要求review:semantic，mine仅返回本人候选。不得让默认trusted列表兼任审核列表，也不得遗漏独立ChangeProposal的读取入口。

列表支持reviewStatus/status、page、pageSize筛选；测试刷新回读时，无冲突的PROPOSED陈述和PENDING修改也出现在相应队列，不能只从pendingDisputes拼出审核列表。

- [ ] 写红灯：只按ACCEPTED而忽略SUPPORT_LOST不能返回；个人撤权不能从节点/邻居/计数/历史引用泄漏；UNKNOWN 不进入指定时点严格结果；超过2跳拒绝；显示标签不能形成HTML注入。
- [ ] 以参数化 SQL 先按graph/workspace及当前版本过滤，支持和证据权限再复核；严禁全库检索后只由前端过滤。文本搜索为受限实体名/谓语/字面文本查询，不宣称向量语义检索。

```java
fixture.acceptVoltageWithTwoSources();
fixture.withdrawFirstSource();
assertEquals(1, fixture.searchAsViewer("P-101").facts().size());
fixture.withdrawSecondSource();
assertTrue(fixture.searchAsViewer("P-101").facts().isEmpty());
assertEquals("ACCEPTED", fixture.readStoredCurrentRevisionStatus());
```

- [ ] 图视图由实体及可信关系型Statement组成，属性型Statement附在节点；节点仍需可见事实/授权支撑。硬限100节点/200边/2跳，结果截断显式标注；超deadline返回明确错误。
- [ ] 证据响应默认只返回经授权片段与摘要；候选编辑需要全文时使用SEM-06受保护快照text接口，要求提议/管理能力及来源权限。历史读取允许已撤回状态的有权治理者，但个人撤权和来源不存在仍不可披露原文。
- [ ] 运行三类查询测试及Review/SupportState回归；提交意图：`Return only authorized, supported facts with inspectable evidence`。

## SEM-09：图谱、候选、冲突与证据工作台

**依赖：** SEM-03、SEM-04、SEM-08。**产出：** 用户无需手工调用API，即可构建和审核小型图谱。

**Files**
- Create: `mateclaw-ui/src/features/semantic/api/{graphQueryApi,sourceApi,statementApi}.ts`。
- Create: `mateclaw-ui/src/features/semantic/graph/{SemanticWorkbench,SemanticGraphView,EntityEditor,SourceCapturePanel}.vue`。
- Create: `mateclaw-ui/src/features/semantic/review/{CandidateEditor,StatementReviewDrawer,ConflictReviewPanel,EvidenceDrawer,SourceGovernancePanel}.vue`。
- Modify: `mateclaw-ui/src/features/semantic/routes.ts`、`mateclaw-ui/src/i18n/locales/{zh-CN,en-US}.ts`、KnowledgeBindingPanel.vue 的图入口。
- Test: `mateclaw-ui/src/features/semantic/graph/__tests__/semanticWorkbench.test.ts`、`mateclaw-ui/src/features/semantic/review/__tests__/{reviewFlow,evidencePermissions}.test.ts`。

**Interfaces**：路由 `/semantic/graphs/:graphId`；SemanticGraphView 接收 `GraphResult` 与 emit('select-statement', {statementId,revision})，不自行调用 Wiki API；EvidenceDrawer通过graphId/evidenceId调用受保护查询。

- [ ] 写Vitest反例：候选不进入正式图；只读用户无确认入口；409确认不假报成功；支持丢失不展示旧缓存证据；切换workspace取消请求。图渲染单元测试可mock ECharts对象，但最终浏览器验收必须实际渲染。
- [ ] 实现从KB选择资料、固化快照并查看任务状态；人工登记实体、从精确片段提出候选，谓语和值类型由绑定本体驱动，不能自由新增正式predicate。
- [ ] 先调用snapshot/text读取固定文本，用户选区转换为全快照code point区间；POST evidence取得evidenceId，再POST候选。使用含emoji和分段范围的UI测试证明偏移正确；证据建立失败不继续提交候选。
- [ ] 实现正式图/事实表、候选与修改队列、冲突审核、证据历史导航；展示来源/事实/本体各自版本。来源撤回与单快照排除分别命名并要求理由。

```ts
// reviewFlow：HTTP失败后仍显示待处理状态，不能乐观确认正式事实。
reviewApi.confirm.mockRejectedValue({ status: 409, code: 'STATEMENT_VERSION_CONFLICT' })
await review.confirm()
expect(review.current.value.reviewStatus).toBe('PROPOSED')
expect(review.error.value?.code).toBe('STATEMENT_VERSION_CONFLICT')
```

review状态封装在ReviewDrawer使用的同目录composable `useStatementReview.ts`，测试与生产共用；补入对应文件。操作成功后重新查询服务器当前修订，不仅更新本地状态。

- [ ] 复用现有ECharts GraphChart注册方式，自有组件管理resize/dispose和安全文本tooltip；保留原Wiki图组件，避免修改其数据源契约。
- [ ] 跑feature全量Vitest、vue-tsc和feature ESLint；浏览器验证一个380V/400V同期冲突、一个多值关系、一个撤回支持案例，回读数据库和API。提交意图：`Make enterprise knowledge construction and review usable in the workbench`。

## SEM-10：默认关闭的 Agent 取证工具

**依赖：** SEM-08，可与SEM-09并行。**产出：** 一项高层只读工具经同一授权查询链返回证据。

**Files**
- Create: `mateclaw-server/src/main/java/vip/mate/semantic/tool/SemanticTool.java`。
- Create: `mateclaw-server/src/main/resources/db/migration/{h2,mysql,kingbase}/V194__register_semantic_tool.sql`，beanName=`semanticTool`，初始enabled=false，依实际既有Tool表字段编写。
- Modify: SemanticPrincipalResolver.java（加入明确的ToolContext解析入口，不改全局ChatOrigin）。
- Test: `mateclaw-server/src/test/java/vip/mate/semantic/{SemanticToolTest,SemanticFeatureFlagTest}.java`。

**Interfaces**：`@Tool` 方法 `semantic_search(String graphId, String query, Integer limit, ToolContext context)`；解析已认证Web的requesterUserId和workspaceId，再调用SemanticQueryService.search，返回结构化事实、证据引用和traceId。

- [ ] 写红灯：module关闭时无bean且不可被ToolRegistry收集；module开但DB工具关闭时Agent不可调用；缺身份/缺Scope/外部IM/cron拒绝；参数graph属于其他工作区拒绝。
- [ ] 用 `@ConditionalOnProperty(prefix="mateclaw.semantic", name="enabled", havingValue="true")` 装配组件；新增默认关闭注册迁移，不改ToolRegistry的上游默认逻辑。

```java
// ToolContext由测试构造与宿主一致的ChatOrigin；不能用工具JSON声明身份。
assertThrows(SemanticApiException.class,
    () -> semanticTool.semantic_search("301", "P-101", 5, new ToolContext(Map.of())));
```

- [ ] 实现工具引用查询服务，只读/有限结果，不开放任意SQL或发布工具；真实Web身份必须经用户和工作区服务核实，不能仅将requesterUserId转字符串当已授权。
- [ ] 在隔离测试环境显式开启module和工具，给测试Agent授予该工具，验证原始返回含statementId/revision/evidenceId，随后真实模型回答能指向证据。无模型配置则保留SEM-11模型门禁未完成。
- [ ] 跑Tool/FeatureFlag/QueryAuthorization测试；提交意图：`Expose semantic evidence to agents only through explicit authorization`。

## SEM-11：全链路、数据库兼容、运行与升级验收

**依赖：** SEM-01～10。**产出：** 可复现运行说明和真实验证证据；不是部署授权。

**Files**
- Create: `mateclaw-server/src/test/java/vip/mate/semantic/SemanticEndToEndIntegrationTest.java`、`SemanticMySqlIntegrationTest.java`。
- Create: `docs/validation/semantic-core/README.md`、`acceptance-matrix.md`、`migration-checksums-before.txt`、`migration-checksums-after.txt`。
- Create: `README.semantic.md`；保存浏览器截图、实际命令日志和脱敏API/DB回读到 `docs/validation/semantic-core/evidence/`。
- Modify: 当前设计/领域/界面规格的状态，仅根据实际完成任务逐项更新。

**Interfaces**：沿用真实API和工具，不新增专供验收绕过权限的生产入口。MySQL测试使用独立测试数据库、随机测试用户和环境传入凭据，不读取/清空用户现有数据；MySQL JDBC已存在，无需新增依赖。

- [ ] 跑红/绿完整集成案例：创建本体v1→UI发布→KB绑定→文本快照→实体/候选→确认→邻域/原文→竞争事实→拒绝或有时效修订→源撤回→历史仍可治理读取。
- [ ] H2文件隔离库真实关闭并重新打开后回读；MySQL真库跑同样发布/事实事务/Unicode/并发/迁移路径。空库及从本次基线升级两条迁移路径均记录。Kingbase/Postgres无环境保留明确未验证，不拿H2兼容模式替代。
- [ ] 启停feature flag检查已有Wiki/聊天/登录仍可用；开启后默认禁用工具，授权后真实Agent一次查询返回正确证据。失败模型调用不可伪造成功。
- [ ] 运行收尾命令，阅读实际报告并分类处理新失败/基线失败：

```bash
mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full test
git diff --check
```

在 `mateclaw-ui` 运行：

```bash
pnpm exec vitest run
pnpm exec vue-tsc --noEmit
pnpm exec eslint src
pnpm exec vite build --mode enterprise --outDir dist/enterprise
pnpm exec vite build --mode production --outDir dist/classic
```

classic 构建前确认环境未注入 `VITE_UI_PROFILE=enterprise`，必要时显式以 `VITE_UI_PROFILE=classic`运行同命令。不得用带 `--fix` 的全库lint当只读验收。

- [ ] 认证浏览器检查1366/1440/1920及390宽度、双profile与浅深色：本体编辑、字段错误、版本对比、KB切换、图节点选择、证据抽屉、键盘焦点与未保存离开。每轮视觉修改后按visual-verdict核查；截图与数据证据同时保存。
- [ ] 对照规范逐项填写验收矩阵：static/build、core、DB H2、DB MySQL、authorization、browser、real-model、Kingbase缺口。任何首期必需项未通过则状态为部分完成，不能终结整项。
- [ ] README.semantic 写明依赖当前MateClaw版本、模块开关、工具手动启用、本体维护、无自动图迁移、来源治理、验证命令、关闭回滚和数据库备份恢复验证。提交意图：`Make the semantic knowledge workflow reproducible and reviewable`。

## 3. 需求追踪与完成判据

| 必须交付的要求 | 负责的任务 | 最强验收证据 |
|---|---|---|
| 一个独立核心，保留上游演进 | 01、11 | 非空边界测试、挂接点diff、构建 |
| 本体类型/属性/关系及发布版本 | 01、02 | 核心规则 + 真实DB不可变发布 |
| 本体管理维护UI与配置更新 | 03 | 真实浏览器v1/v2维护、刷新与差异回读 |
| 知识库复用/单图/版本绑定 | 04 | 唯一约束、非空拒绝、UI绑定回读 |
| 来源快照/精确证据/版本 | 05、06、08 | 源更新不改历史、Unicode片段、DB重启回读 |
| 候选/确认/修订/冲突 | 05、07、09 | 真实并发事务+审核界面与SQL证据 |
| 来源全版本撤回/单次排除/个人撤权 | 07、08、09 | 支持状态与权限独立的负测 |
| 真实知识图谱与可信查询 | 08、09 | 数据驱动节点/边、证据定位、权限过滤 |
| Agent集成与默认安全开关 | 10、11 | ToolRegistry收集测试 + 真实模型取证 |
| 可部署运行说明和兼容边界 | 11 | H2/MySQL实跑、浏览器、明确Kingbase缺口 |

## 4. 提交、审查和回滚

每任务完成后才提交其拥有的文件；Lore消息首行说明动机，正文保留相关约束，附 Tested/Not-tested/Scope-risk。例如：

```text
Keep ontology publication consistent across retries

Publication and its governance record share the existing database transaction.

Constraint: Published definitions must remain immutable
Rejected: Best-effort audit only | A failed audit write would lose the decision record
Tested: H2 concurrent publish and operation-id replay
Not-tested: Kingbase runtime
Scope-risk: narrow
```

每个门禁可单独审查；不得因前端先完成而跳过Server授权测试，也不得因core全绿而省略真实数据库。进入下一任务前核对消费者接口名称/字段与上游任务一致。

故障回滚：关闭module和tool→停接新任务→等待当前事务完成/回滚→保留表、修订与快照→按证据定位问题。禁止删库、重写迁移或覆盖已发布版本。功能分支尚未合入前可回退单任务提交；数据已写入后优先兼容的前向修复。

## 5. 本次计划编写验收

- 已核查当前仓库挂接点，并记录迁移repair、默认Tool启用、best-effort审计、前端错误解包与大ID等现实约束。
- 计划的路径/接口/验收为待实现契约；现有文件与新建文件已区分。编号V191～V194是当前V190基线下的提案，实施时必须重新分配未占用编号。
- 编写阶段只检查文档引用、一致性和需求覆盖，不运行应用测试，不称上述任务已完成。
- 已完成一次独立审查与限定范围复核，修正证据创建入口、审核队列回读、共享Entity依赖、候选间冲突及并发resolve语义；复核未发现这些范围内的剩余重大矛盾。文档链接和11项任务编号检查通过，实施复选框均保持未完成。

## 2026-09-07 执行状态

M1（SEM-01～03）已完成，用户追加的名称/描述上下两行也已实现。前述“编写阶段”说明是初始计划记录；当前状态以本节及[验收记录](../../validation/ontology-m1/acceptance.md)为准。SEM-04～11未实施，未将整个企业图谱目标标记为完成。
