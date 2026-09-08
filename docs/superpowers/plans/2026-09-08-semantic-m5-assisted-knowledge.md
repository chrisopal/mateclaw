# M5 Assisted Knowledge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从一份已有资料生成有原文依据的知识建议，经人工核对后进入现有审核流程。

**Architecture:** 新增 JDK-only semantic-application 模块承接 M5 用例，通过资料、权限、模型、存储和知识提交接口调用外部能力。MateClaw server 提供适配、调度、事务和 API，核心保持独立，同进程部署。

**Tech Stack:** Java 21、现有 Maven/Spring/JDBC/Flyway、Vue 3/Element Plus/Vitest；不新增第三方依赖或基础设施。

## 执行结果（2026-09-08）

Task 1–6 功能实现及相关验证已完成；Task 7 质量主线、10份真实模型样本、持久化回读、手册与截图已完成。真实模型存在格式/引文错误，均明确拦截并记录；Kingbase实机未验证。详见 [M5验收记录](../../validation/semantic-m5/acceptance.md)。

本次未提交或推送。为保留工作区已有UI与手册改动，分步提交项仍未勾选。下面红灯过程未保留独立日志的步骤也不补写为已证实；绿色测试证据见验收记录。

## Global Constraints

- 设计：[已批准的 M5 设计](../specs/2026-09-08-semantic-m5-assisted-knowledge-design.md)。批准发生于设计提交 `b151e93f` 之后。
- 工作区：`/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`；保留既有未提交界面、手册和运行产物。任何任务不得使用 git add .；仅提交该任务文件，消息遵循 Lore 协议。
- 本计划完成不等于 M5 已实现；所有任务初始未执行。M6–M8 不在范围内。
- 只支持 WIKI_RAW 已解析资料；一任务一份资料；100,000 Unicode code point/现有快照字节限制同时满足；块长 6,000、重叠 300、最多 20 块；最多 200 条建议。超限显式失败，不截断冒充完整结果。
- 工作区并发 2、实例并发 4；调用超时 90 秒，任务运行预算 10 分钟；暂时错误最多自动重试 2 次。生产默认关闭辅助生成开关。
- 建议不进入已确认查询；对象显式登记；原审核和冲突规则唯一权威；提交实时授权且固定本体与资料快照。
- API ID 使用字符串；模型不执行工具；不记录凭据/原文到普通日志；模型调用在事务外。
- API 和内部方法命名以下文为实现契约；迁移文件序号在实施时根据所有数据库目录最新版本统一分配，禁止改旧迁移 checksum。

## 文件组织与契约

新模块根 `mateclaw-semantic-application/`，下文 A 表示 `src/main/java/vip/mate/semantic/application/extraction/`，AT 表示同模块 `src/test/java/vip/mate/semantic/application/extraction/`。
S 表示 `mateclaw-server/src/main/java/vip/mate/semantic/extraction/`，ST 表示 `mateclaw-server/src/test/java/vip/mate/semantic/`。
U 表示 `mateclaw-ui/src/features/semantic/extraction/`。每个缩写均相对仓库根展开，不建立第二个代码仓库。

| 文件组 | 单一责任 |
|---|---|
| A/ExtractionContracts.java、ExtractionPorts.java | 语义类型与消费方端口；只用 JDK/core 类型 |
| A/SourceChunker.java、SuggestionValidator.java | 确定性分块、证据定位、本体/数量验证 |
| A/ExtractionCoordinator.java | 创建、执行、取消、重试及结果发布 |
| A/SuggestionSubmissionService.java | 编辑版本、提交幂等、收据恢复 |
| S/JdbcExtractionRepository.java、ExtractionScheduler.java | 条件更新、租约、心跳与后台领取 |
| S/MateClawSourceAdapter.java、MateClawAccessAdapter.java、MateClawModelAdapter.java、MateClawSubmissionAdapter.java | 宿主特有资料、权限、模型和命令对接 |
| S/ExtractionConfiguration.java、ExtractionController.java、ExtractionDtos.java | 装配、功能开关及 HTTP 边界 |
| U/ExtractionWizard.vue、ExtractionTaskPanel.vue、SuggestionReviewPanel.vue、useExtractionTask.ts | 向导、进度、原文核对、请求状态 |

公共记录放在 ExtractionContracts 内，端口放在 ExtractionPorts 内，可用静态导入。建议值使用 core 已有值类型，不能另写一套值域或冲突规则。

```java
record Actor(String workspaceId, String userId) {}
record StartCommand(String graphId, String sourceRef, String modelConfigId, String operationId) {}
record TaskRef(String taskId, String status, long version) {}
record Chunk(int ordinal, int startCodePoint, String text) {}
record Quote(int startCodePoint, int endCodePoint, String exactQuote) {}
record SubmitCommand(String suggestionId, long expectedVersion, String operationId) {}
record SubmissionRef(String statementId, int revision) {}
```

任务持久化补充 spec 第 5 节全部字段。模型适配返回业务建议及 Quote，不返回任意可执行动作；HTTP DTO 与应用记录单独映射。

## Task 1：应用边界与确定性证据规则

依赖：无。修改根 `pom.xml`、`mateclaw-server/pom.xml`；新增模块 pom、A/ExtractionContracts.java、ExtractionPorts.java、SourceChunker.java、SuggestionValidator.java、AT/SourceChunkerTest.java、SuggestionValidatorTest.java；扩展 ST/SemanticCoreArchitectureTest.java。

接口：`SourceChunker.split(String text): List<Chunk>`；`SuggestionValidator.matches(String snapshot, Quote quote): boolean`。端口方法：SourceContentPort.read、AccessPolicyPort.require、ExtractionModelPort.extract、ExtractionTaskRepository 条件写入、KnowledgeSubmissionPort.submit；输入输出完整结构与 spec 4/5 节对应，所有权限输入含 Actor 和 graphId。

- [ ] 增加最小失败测试：Unicode 偏移、20 块上限、引用不匹配、值域非法；加入应用包架构扫描及“禁止依赖能被检出”的反例。

```java
@Test void quoteUsesCodePoints() {
    assertTrue(new SuggestionValidator().matches("甲😀乙", new Quote(1, 2, "😀")));
    assertFalse(new SuggestionValidator().matches("甲😀乙", new Quote(1, 2, "乙")));
}
```

- [ ] 运行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-semantic-application -am test`，确认新增断言失败而非环境错误。
- [x] 实现最小分块/定位规则，调用已有 core 校验器，不实现模型调用。定位使用：

```java
int begin = text.offsetByCodePoints(0, quote.startCodePoint());
int end = text.offsetByCodePoints(0, quote.endCodePoint());
return text.substring(begin, end).equals(quote.exactQuote());
```

负数、逆序、越界先返回 false。分块步长 5700，完整覆盖快照；输入/输出计数限额必须提前校验。
- [x] 复跑模块测试及 ST 架构测试，证明 core 仍仅依赖自己/JDK，application 仅依赖自身/core/JDK。
- [ ] 定向提交；intent：`Keep assisted extraction independent from host frameworks`。

## Task 2：资料、权限和任务启动纵向闭环

依赖：Task 1。新增 S/MateClawSourceAdapter.java、MateClawAccessAdapter.java、JdbcExtractionRepository.java、ExtractionConfiguration.java；A/ExtractionCoordinator.java；ST/SemanticExtractionStartIntegrationTest.java；各现有 Flyway 方言目录的新任务迁移。修改现有 SourceApplicationService 仅提取被 M5 复用的宿主读取边界，不改资料业务行为。

接口：`ExtractionCoordinator.start(Actor actor, StartCommand command): TaskRef`。repository 保存 Task/Attempt/Suggestion/Receipt；task 唯一键工作区+图+operationId；receipt 唯一键 suggestionId+editVersion，操作号唯一性范围与现有命令一致。

- [ ] 在现有 Semantic 集成测试风格中增加 create→read、同 operation 同输入返回同 taskId、不同输入 409、资料撤权、跨图/工作区拒绝测试；先运行确认失败。
- [x] 实现：实时 require → 固定快照与本体修订 → 固定允许模型配置摘要 → requestHash → 事务写任务。哈希采用固定字段顺序，不含密钥。

```text
start(actor, command):
  require(actor, graph, source, START)
  snapshot = readAuthorizedImmutableSnapshot(source)
  definition = readBoundPublishedRevision(graph)
  validateLimits(snapshot)
  return transaction(findReplayOrInsertQueued(command, snapshot, definition))
```

- [x] 验证已有快照资料同步和 M2 重试行为未改变；新开关关闭返回明确错误，原人工入口继续正常。
- [x] 运行 `mvn -pl mateclaw-server -am -Dtest=SemanticExtractionStartIntegrationTest,SemanticMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（使用 Java 21）；覆盖 H2 真实迁移，MySQL 按现有容器测试方式执行。
- [ ] 定向提交；intent：`Freeze authorized source and ontology inputs before extraction`。

## Task 3：可恢复执行、模型适配与取消

依赖：Task 2。新增 S/ExtractionScheduler.java、MateClawModelAdapter.java，扩展 repository/coordinator；新增 AT/ExtractionCoordinatorTest.java、ST/SemanticExtractionLeaseIntegrationTest.java。

接口：`runNext(String workerId): boolean`、`cancel(Actor,String graphId,String taskId): TaskRef`、`retry(Actor,String graphId,String taskId,String operationId): TaskRef`；repository 条件更新必须同时校验 taskId、leaseGeneration、状态。

- [ ] 先用假时钟/阻塞模型替身测试：双 worker 抢占、过期代次写入失败、取消后迟到结果丢弃、重启恢复、限流重试上限、工作区/实例并发上限。

```text
claim(generation=1); expireLease(); claim(generation=2)
assert complete(generation=1) == false
assert complete(generation=2) == true
```

- [x] 实现数据库领取与心跳：租约 60 秒、每 15 秒续约；失败续约立即停止后续块调用。全局/工作区限额领取在事务中串行化校验，避免多线程 count 后并发插入突破限额。
- [x] 通过当前宿主模型工厂适配允许的配置；先定位真实工厂/配置授权路径，再复用，禁止为绕过授权直连供应商。模型客户端接口差异只出现在 adapter。
- [x] 模型调用无工具、90 秒上限，任务预算覆盖所有分块及重试；暂时失败等待 2 秒/8 秒再尝试，预算不足即终止。显式手动重试也受每任务最多 3 次尝试约束，新配置需新任务。
- [ ] 保存完整成功尝试后原子切换结果；错误格式、超额、引文无法验证显示诊断或失败，部分尝试结果不混用。单条建议证据错误保留不可提交诊断，顶层协议错误失败。
- [ ] 模块测试和 `SemanticExtractionLeaseIntegrationTest` 通过后定向提交：`Recover extraction without accepting stale worker results`。

## Task 4：建议核对、对象匹配与审核提交恢复

依赖：Task 3。新增 A/SuggestionSubmissionService.java、S/MateClawSubmissionAdapter.java；新增 AT/SuggestionSubmissionServiceTest.java、ST/SemanticExtractionSubmissionIntegrationTest.java。

接口：`submit(Actor actor,String graphId,SubmitCommand command): SubmissionRef`；编辑建议使用 expectedVersion CAS。已有对象列表/登记 API 复用，不建立自动合并入口。

- [ ] 先测试：建议不能被可信查询看到；同名对象需显式选定；编辑冲突 409；篡改对象图/类型拒绝；来源失效/撤权/换绑后拒绝。
- [x] 实现复核映射与标准命令构造：允许现有对象 ID，缺失先引导登记；每次内容编辑生成新修订。用户选择的引文重新按完整快照校验。

```text
submit:
  requireCurrentAccessAndBinding()
  validateExpectedSuggestionVersionAndEvidence()
  result = existingPropose(stableOperationId, immutablePayload)
  upsertReceipt(suggestionId, editVersion, result)
  return result
```

- [ ] 注入“现有 propose 提交后，receipt 写入前失败”；用相同操作号重试，断言知识总数只增长 1，补出的收据引用同一记录。
- [ ] 测试修改内容却复用操作号返回409；SUBMITTED 记录只读；建议忽略不撤回已提交知识。
- [ ] 运行 submission 集成测试及现有 SemanticChangeConflictIntegrationTest、SemanticQualityRootCauseIntegrationTest，定向提交：`Route assisted suggestions through the existing review authority`。

## Task 5：HTTP、权限隔离与运维开关

依赖：Task 4。新增 S/ExtractionController.java、ExtractionDtos.java；扩展 configuration；新增 ST/SemanticExtractionApiIntegrationTest.java、SemanticExtractionFeatureFlagTest.java。

接口：严格实现 spec 第 8 节的任务/建议资源；start 返回 202，错误使用现有 SemanticApiException；列表分页默认20、最大100；编辑/提交操作都带版本和操作号。

- [ ] 先写 HTTP 合同测试：字符串 ID 保真、未知字段拒绝、跨工作区对象不可见、取消/重试角色限制、分页计数不泄漏不可见资料。
- [x] 实现 DTO 映射和现有异常格式，普通模型错误对外返回分类及 traceId；原文、密钥和供应商完整响应不入日志。

```text
POST extraction-tasks -> 202 { taskId: "...", status: "QUEUED", version: 1 }
PATCH suggestions/id(expectedVersion=old) -> 409
POST suggestions/id/submit -> { statementId: "...", revision: 1 }
```

- [x] 关闭开关禁止新增任务/模型调用；对运行任务发取消请求；允许有权用户读取历史和收据。验证现有手工知识与 semantic_search 仍工作。
- [x] 记录 traceId、排队/执行耗时、错误分类和用量；已有模型发送策略拒绝时明确失败，不自动切换提供方。
- [ ] API/feature flag 测试通过，定向提交：`Expose recoverable extraction without weakening access control`。

## Task 6：业务向导、原文核对与任务恢复

依赖：Task 5。新增 U/ExtractionWizard.vue、ExtractionTaskPanel.vue、SuggestionReviewPanel.vue、useExtractionTask.ts、U/__tests__/extractionWorkflow.test.ts；新增 `mateclaw-ui/src/features/semantic/api/extractionApi.ts`；修改 api/types.ts、graph/SemanticWorkbench.vue、zh-CN.ts/en-US.ts。必须基于现有未提交的业务文案继续修改，禁止覆盖关系图工作。

接口：API 与 Task 5 DTO 一致；useExtractionTask 接受 graphId/taskId，并暴露 task、suggestions、refresh、cancel、submit；切换 graph/workspace 后中止请求并丢弃旧响应。

- [ ] 先写组件交互测试：选择资料→启动→恢复任务→核对→提交审核；模型失败/零建议分别显示；旧请求晚返回不能污染新工作区。

```text
start -> render "生成中"
reload with taskId -> GET same task, no second POST
submit -> render "已提交审核", link uses returned statementId
switch workspace -> clear source text and suggestions
```

- [x] 实现“从资料生成”入口；明确展示将发送的资料、模型和绑定本体版本。默认轮询间隔3秒，仅活动页面/运行任务轮询，离开停止前端轮询不取消后台任务。
- [ ] 实现左原文右建议、引文高亮、对象选择、类型/单位/适用时间错误、编辑保存/忽略/逐条提交；证据高亮按 code point 转换 JS string 索引，不能直接拿偏移 slice UTF-16。
- [ ] 增加390px上下布局、键盘导航、未保存提醒；提交按钮不是“批准”，无全选自动批准；来源失效清除内容并显示可行动提示。
- [ ] 运行 `pnpm exec vitest run src/features/semantic`、`pnpm exec vue-tsc --noEmit`、修改文件 ESLint；两种 profile 构建，浏览器验证深浅主题和手机。定向提交：`Let business users review source-backed suggestions before submission`。

## Task 7：质量案例、故障验收和交付文档

依赖：Task 6。新增 ST/SemanticExtractionQualityIntegrationTest.java；资料与标注位于 `docs/validation/semantic-m5/fixtures/`；验收记录 `docs/validation/semantic-m5/acceptance.md`；更新 `docs/user-guide/ontology/README.md`、quality-case.md 及真实截图。

- [x] 准备10份合成资料及逐条人工标注，覆盖：正常报告、无知识、重复引文、Unicode、同名对象、时间不明、相互矛盾报告、越界值、资料中的恶意指令、超限资料。质量主线测量值域允许0.08，合格标准不冒充录入范围。
- [x] 假模型稳定执行自动验收；真实模型执行质量主线并记录配置/输入摘要/用量/错误分布，不把假模型通过写成真实模型通过。

```text
before review: trusted query does not include generated statement
after authorized approval: search returns same statementId + evidenceId
after source withdrawal: trusted query excludes unsupported statement
retry after lost response: statement count unchanged
```

- [x] 数据库回读 task/attempt/suggestion/receipt/statement/evidence 关联；UI截图只证明展示，不能代替持久化和权限验收。
- [x] 对所有 spec 第10节8项验收逐项登记：通过/失败/未验证及证据路径。H2/MySQL分别跑；其他方言没有实机环境明确不宣称验证。
- [x] 跑 core/application 全测与 server Semantic 回归、前端语义测试/类型检查/构建、Snowflake检查和 git diff --check。记录运行环境与慢点，不反复无变化重跑全套。
- [x] 复核关闭开关与服务重启后人工流程、旧图及 format1/2版本仍可用；更新手册并回读生成HTML全部图片。
- [ ] 定向提交：`Prove assisted knowledge remains evidence-backed across failures`；推送/部署按当时用户授权执行，不以本计划授权代替。

## 执行顺序与完成门禁

1 → 2 → 3 → 4 → 5 → 6 → 7。优先顺序不能反转：没有证据和提交恢复测试，不先上线生成按钮。适配层与前端仅在接口冻结后可有界并行，不要求每个任务新开 agent。

每项执行遵循：先有针对性失败测试 → 最小实现 → 相关测试通过 → 小提交。测试/类型错误未解决不得勾选任务完成。第三方模型能力、宿主授权、方言迁移若出现不兼容，先在适配层解决；不得放宽核心权限/证据约束来迁就模型。

## 自检与设计覆盖

| 设计要求 | 任务 |
|---|---|
| 模块隔离与接口 | 1、2 |
| 固定快照/本体/配置、资料权限 | 2、4、5 |
| 模型安全、限额、取消、重试、租约 | 3、5 |
| 证据、对象匹配、现有审核、幂等恢复 | 1、4 |
| 业务UI、恢复、移动端、主题 | 6 |
| 质量评测、运行回读、回退、文档 | 7 |

自检结论：无占位任务；所有设计章节已映射；任务方法/DTO契约一致；唯一未固定的迁移序号必须由实施时真实仓库版本决定，这是防止碰撞的操作要求。计划级“Task”包含多个红绿验证步骤，不承诺固定人日。下一步按本计划执行 Task 1，不继续扩大 M5 范围。
