# P1 投标项目到解析基线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可从界面发起、失败可重试且可确认的真实招标解析链路。

**Architecture:** 新增独立投标领域与持久任务表；先建立来源、技能固定与受限执行，再接四项分析及前端。平台改动只通过显式任务选项扩展，保护售前和普通聊天。

**Tech Stack:** Java 21、现有 Spring Boot/MyBatis/Jackson/Flyway/PDFBox/POI；Vue 3、Element Plus、TypeScript、Vitest；现有 H2/MySQL/Kingbase 方言，不新增依赖。

## Global Constraints

- bid-agent 仅提供业务拆分与规则设计参考，不成为代码、数据、接口、部署或运行依赖。
- 每项 Skill 返回结构化内容，校验并持久化至数据库。
- Word 输入限定 `.docx`；正式交付以可编辑 DOCX 为必验格式，PDF 输出后续扩展。
- 一个投标项目对应一个标段；自动重试最多两次；首版单应用实例调度。
- 真实负责人选择真实成员；数字员工执行候选任务，审核员工必须不同于编写员工。
- 正式下载只是对同一组已批准字节开放正式成果状态，不再调用模型或渲染器。
- enterprise UI，六个业务页签，统一任务抽屉，简短中文标签与明确蓝色按钮；不显示裸 ID、G1/G2、Fit-Gap。
- 禁止新依赖、外部提交、密钥入日志、生产数据重置、盲目 git add -A；不把模拟结果当真实模型成果。
- 执行前阅读[设计规格](../specs/2026-09-23-independent-bidding-design.md)和[公共类型、API、容量与验收词典](2026-09-23-bidding-implementation.md)。此两份文档是每项任务的必需上下文。

---


## 阶段交付与文件边界

交付项目、原文与证据、真实员工/岗位、固定技能运行、四类解析、人工确认及刷新回读。P1 结束时目录/写作/审核页签显示相应阶段尚不可用，不显示可点击但无实现的按钮；P2/P3 分别接通。

下文路径相对工作树根。`J` = `mateclaw-server/src/main/java/vip/mate/bidding/`，`T` = `mateclaw-server/src/test/java/vip/mate/bidding/`；文件表中的 J/T 均按这两个前缀展开，不创建名为 J 或 T 的目录。

| 所属 | 新建文件（每行各自职责） | 修改现有文件 |
| --- | --- | --- |
| P1-01 | J`BiddingTypes.java`, `BiddingApiException.java`, `BiddingExceptionHandler.java`, `BiddingAccess.java`, `BiddingRepository.java`, `BiddingProjectService.java`, `BiddingController.java`, `BiddingCommandService.java`, `BiddingProperties.java` | `mateclaw-server/src/main/resources/application.yml` |
| P1-02 | J`BiddingSourceReader.java`, `BiddingSourceService.java`, `BiddingDependencies.java` | J`BiddingController.java`, `BiddingCommandService.java`, `BiddingRepository.java` |
| P1-03 | J`BiddingSkillPackages.java`, `BiddingEmployeeBindings.java` | 既有 SkillRuntimeService 仅增加固定内容读取需要的小接口 |
| P1-04 | J`BiddingEmployeeRuntime.java`, `BiddingToolScope.java`, `BiddingReadTool.java`；平台 `agent/execution/ProjectExecutionOptions.java`, `ProjectToolPolicy.java` | AgentService/AgentGraphBuilder/NodeStreamingChatHelper/ReasoningNode/ToolExecutionExecutor/SkillLoadTool/SkillFileTool |
| P1-05 | J`BiddingTaskService.java`, `BiddingScheduler.java`, `BiddingRetryPolicy.java`, `BiddingResultHandler.java` | J`BiddingRepository.java`, `BiddingCommandService.java` |
| P1-06 | J`BiddingSkillValidator.java`, `BiddingAnalysisService.java`；四个技能包 | J`BiddingCommandService.java` |
| P1-07 | `mateclaw-ui/src/features/bidding/` 下 api/types/routes、列表/工作台、文件/解析/概览组件、任务/证据抽屉、shared/state | 现有 router、MainLayout、zh-CN/en-US locales |
| P1-08 | `scripts/bidding/check-runtime.py`, `docs/bidding/runtime.md`, `docs/bidding/acceptance/2026-09-23-p1.md` | 只补验收发现的当前范围缺陷 |

### Task 1: P1-01 建立隔离的项目和持久化基础

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTypes.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingApiException.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingExceptionHandler.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingAccess.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingRepository.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingProjectService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingProperties.java`
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V212__bidding_foundation.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V212__bidding_foundation.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/kingbase/V212__bidding_foundation.sql`
- Modify: `mateclaw-server/src/main/resources/application.yml`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingHttpFixture.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingProjectTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingMigrationTest.java`

**Interfaces:**
- Consumes: 现有 `AuthService.findByUsername(String)` / `findById(Long)`，WorkspaceMapper/WorkspaceMemberMapper；总计划 BiddingTypes。
- Produces: `BiddingAccess.require(String workspaceId, String minimumRole): String` 返回 actorId；`requireActor(Scope,String): void` 用于后台；`requireApprover(Scope): void` 验证真实项目负责人或 workspace/admin；`requireOwner(String workspaceId,String ownerId): void`。
- Produces: `BiddingProjectService.create(Scope,NewProject): ObjectNode`、`get(Scope): ObjectNode`、`list(Scope,String query,String stage,String ownerId,int page,int pageSize): Page<ObjectNode>`；`BiddingCommandService.execute(Scope,Command): ObjectNode`。
- Produces: 总计划列出的测试基座 `api/project/command/ref`；所有后续集成测试继承该测试配置。

- [ ] 在 BiddingProjectTest 写真实 JWT/DB 的第一组失败测试；复制总计划 `api` 的具体 MockMvc 算法到 BiddingHttpFixture，建独立配置，member 默认负责人从当前 token 用户得出。

```java
@Test void cannotReadAnotherWorkspaceOrCreateAsViewer() throws Exception {
  var p = project();
  api("GET", "/projects/" + p.path("id").asText(), "owner", otherWorkspace, null, 404);
  api("POST", "/projects", "viewer", workspace,
      Map.of("operationId", "denied", "name", "测试标段", "lotName", "一标段"), 403);
  api("GET", "/projects", "member", null, null, 400);
}
@Test void createIsIdempotentAndPayloadConflictIsRejected() throws Exception {
  var body = Map.of("operationId", "same", "name", "测试项目", "lotName", "一标段");
  var first = api("POST", "/projects", "member", workspace, body, 200);
  assertEquals(first, api("POST", "/projects", "member", workspace, body, 200));
  api("POST", "/projects", "member", workspace,
      Map.of("operationId", "same", "name", "另一个项目", "lotName", "一标段"), 409);
}
```

- [ ] Run（根目录）：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dtest=BiddingProjectTest -Dsurefire.failIfNoSpecifiedTests=false test`。Expected：新增路由不存在或类型未定义而失败；不得把测试配置启动异常当有效业务 RED。
- [ ] 创建总计划记录类型和异常。BiddingApiException 提供 `(int status,String code,String message)` 构造、`status():int` 和 `code():String`；handler 返回现有 R 错误格式。验证 scope、用户 enabled/deleted、workspace/member 当前记录；服务调用不能信任请求 actorId。
- [ ] 写三个方言迁移。除总计划通用列与唯一索引外，project 有 owner_id/body_json/version；source 有 source_id/version/kind/digest/content/blocks_json/quality/read_token/read_started_at；head 有 workspace_id/project_id/kind/object_id/version/selected_ref_json，主键前四列；revision 有 kind/object_id/version/payload_json/input_refs_json/status/digest；task 有 agent_id/skill_package_id/config_digest/input_json/input_refs_json/status/active_attempt_id/deadline_at/cycle_no/cycle_attempt/attempt_count/next_run_at/boot_id；attempt 有 token/state/output_json/tool_receipts_json/rejected_output/error_json/started_at/finished_at；decision 有 target_ref_json/decision/reason/actor_id；operation 有 request_digest/result_json；skill_package 有 skill_id/version/digest/files_json。大内容不出现在列表查询。
- [ ] 实现 project 的列表/创建/更新和幂等，SQL 均使用命名参数，无动态表名。project 新建 version=1，空白标题/超长名称拒绝，名称/标段各 200 字；列表 page≥1，pageSize 默认20/最大100。相同 operationId 先查原请求摘要；撞 unique 后读回原结果，不多建项目。

```sql
UPDATE mate_bidding_project
SET body_json=:body, version=version+1, updated_at=:now
WHERE id=:projectId AND workspace_id=:workspaceId AND version=:expectedVersion;
```

- [ ] 注册 `UPDATE_PROJECT` / `ARCHIVE_PROJECT` 动作；UPDATE 校验真实 owner，不能用数字员工 ID。`expected.version` CAS 为 0 行则抛 409。每个成功操作在同一事务保存 operation.result_json；GET 重新从 DB 查询。归档项目拒绝新增执行，历史只读。
- [ ] 增加 enabled=false 不暴露业务路由、semantic/presales=false 可建项、负责人非成员/已禁用用户拒绝、来源表大字段可完整回读、修改版本冲突的测试。使用新增 BiddingMigrationTest 从 V211 迁到 V212，预置售前和本体记录，断言内容/计数不变；SQL 方言实跑单列，不虚报 H2 为全部方言通过。
- [ ] Run：同前命令 `-Dtest='BiddingProjectTest,BiddingMigrationTest'`。Expected：全部 PASS，读取两次返回同一业务对象，无关联原数据变化。
- [ ] Commit：仅 stage 本任务文件及对应测试；`git commit -m "Prevent bidding operations from crossing workspace and revision boundaries" -m "Constraint: Preserve existing presales and semantic data" -m "Tested: Bidding project and migration tests"`。

### Task 2: P1-02 持久原文、完整读取和稳定证据定位

**Files:**
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V213__bidding_source_read_state.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V213__bidding_source_read_state.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/kingbase/V213__bidding_source_read_state.sql`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSourceReader.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSourceService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingDependencies.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingRepository.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSourceReaderTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSourceTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingMigrationTest.java`
- Test: `mateclaw-server/src/test/resources/bidding/reader/expected.json`

**Interfaces:**
- Consumes: `BiddingAccess.requireActor(Scope,String)`；既有 PDFBox Loader、POI XWPFDocument。
- Produces: `BiddingSourceReader.read(byte[] bytes,String filename): Extraction`；`BiddingSourceService.upload(Scope,String operationId,String sourceKind,Ref supersedes,byte[] bytes,String filename): ObjectNode`；`evidence(Scope,String sourceId,long version,String blockId): ReadBlock`；`confirmSet(Scope,Command): ObjectNode`；`readPending(int limit):int`；`retryRead(Scope,Command):ObjectNode`。
- Produces: `BiddingDependencies.validate(Scope,List<Ref>): void`，重查当前授权和固定引用是否完整；`isCurrent(Scope,List<Ref>): boolean` 比对已选择依赖；`invalidate(Scope,Ref changed): void` 标记受影响确认与派生版本。

- [ ] 先用 POI 在测试中生成包含中文、金额和表格的 DOCX；建立真实可读 PDF、纯扫描、混合空文本页、损坏文件、加密 PDF 的最小 fixture（PDFBox 可在测试创建，无需下载）。失败测试：

```java
@Test void docxKeepsTableCellLocatorAndValue() throws Exception {
  byte[] bytes;
  try (var d = new org.apache.poi.xwpf.usermodel.XWPFDocument();
       var out = new java.io.ByteArrayOutputStream()) {
    d.createParagraph().createRun().setText("评分标准");
    d.createTable(1, 2).getRow(0).getCell(1).setText("12.50 分");
    d.write(out); bytes = out.toByteArray();
  }
  var extraction = new BiddingSourceReader().read(bytes, "tender.docx");
  assertTrue(extraction.complete());
  assertTrue(extraction.blocks().stream().anyMatch(b ->
      b.locator().equals("body/table:0/row:0/cell:1") && b.text().equals("12.50 分")));
}
```

- [ ] Run：Maven 上述模板 `-Dtest=BiddingSourceReaderTest`。Expected：读入类不存在或定位/内容断言 FAIL。
- [ ] Reader 按 magic+扩展名校验类型；DOCX 使用 getBodyElements 保留段落与表格顺序，单元格 locator 如测试；PDF 按实际 page 1..N，以 PDFTextStripper 坐标分段，记录 bbox/阅读顺序和真实页序；同源版本重读得到相同 blockId。无文本但含实质图片、无法解释对象/合并表格或图像表格标为 `NEEDS_REVIEW`，complete=false。可靠表格不足时阻断确认，不输出猜测列关系。

```java
int points = text.codePointCount(0, text.length());
if (points > 1_000_000) {
  throw new BiddingApiException(413, "SOURCE_TEXT_LIMIT", "文件内容超出读取上限");
}
```

- [ ] 不调用会吞截断的 TikaExtractor 作为成功证据。上传事务保存原字节、摘要与PENDING读取状态后返回SourceRevision；readPending使用来源行PENDING→READING CAS与read_token领取，每次至多2项，在事务外解析，保存READY/NEEDS_REVIEW/FAILED及问题码。启动把遗留READING标FAILED，`RETRY_SOURCE_READ`动作调用retryRead将同源版本重置PENDING，不改原字节。损坏/加密保存FAILED和422类错误；后续确认来源返回422，不能把上传成功当读取成功。25MiB/100MiB/500页边界先检查，POI 保留 zip bomb 防护；禁止执行宏/外链/嵌入对象。
- [ ] 定义并实现 `CONFIRM_SOURCE_SET`：payload `{sourceRefs, exclusions:[{sourceRef,blockId,reason}]}`；只有 approver 能确认排除项，实质不可读内容不能豁免。sourceSet 作为 revision，涵盖每页/区块及排除原因。上传补遗不会直接覆盖已确认集合，确认新集合触发 Dependencies.invalidate。
- [ ] 所有原文/证据/任务快照读 API 重查授权。证据摘录由服务器从 blockId 取，不信任模型传入的 quote；图片/OLE 内容只提供原文定位。URL 不自动访问。旧 source refs 保留；删除/撤权应阻止其派生快照未授权读取。
- [ ] 增加 BiddingSourceTest：文件重复上传幂等、原字节 SHA-256 读回、跨项目证据404、混合扫描不能确认、纯空白页有理由可排除、超过限额413、缺失抽取页不能标完成。Run：Maven `-Dtest='BiddingSourceReaderTest,BiddingSourceTest'`，Expected PASS。
- [ ] Commit：显式 stage 本任务文件；`git commit -m "Keep every tender finding traceable to complete immutable source content" -m "Rejected: Silent extractor truncation | loses tender clauses" -m "Tested: Reader and source boundary tests"`。

### Task 3: P1-03 岗位绑定与不可变技能包

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSkillPackages.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeBindings.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingProjectService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/skill/runtime/SkillRuntimeService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSkillPackagesTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeBindingsTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingProjectTest.java`

**Interfaces:**
- Consumes: `AgentService.getAgent(Long)`、`listAgentsByWorkspace(Long,Boolean)`；`AgentBindingService.getBoundSkillIds(Long)` / `getEffectiveToolNames(Long)`；`SkillRuntimeService.findActiveSkill(String,Long)`。
- Produces: `BiddingSkillPackages.pin(Scope,String agentId,String skillId): SkillPin`；`read(Scope,String digest,String relativePath): String`；`static digest(Map<String,String>): String`。
- Produces: `BiddingEmployeeBindings.assign(Scope,Command): ObjectNode`（action `ASSIGN_EMPLOYEES`）；`resolve(Scope,String role): String`；`configDigest(Scope,String agentId): String`；`modelConfigId(Scope,String agentId):String`；`validate(Scope,String agentId,String expectedDigest): void`。

- [ ] 建立摘要与路径拒绝测试，及真实 DB/Agent binding 配置的绑定测试。

```java
@Test void digestIncludesReferencedSchemaAndIgnoresMapInsertionOrder() {
  var a = Map.of("SKILL.md", "read schema", "output.schema.json", "v1");
  var b = Map.of("output.schema.json", "v1", "SKILL.md", "read schema");
  assertEquals(BiddingSkillPackages.digest(a), BiddingSkillPackages.digest(b));
  assertNotEquals(BiddingSkillPackages.digest(a),
      BiddingSkillPackages.digest(Map.of("SKILL.md", "read schema", "output.schema.json", "v2")));
}
```

- [ ] Run：Maven `-Dtest='BiddingSkillPackagesTest,BiddingEmployeeBindingsTest'`，Expected 缺少包固定实现导致 FAIL。
- [ ] 包固定读取活动技能一次，递归收集该包内 SKILL.md/input.schema.json/output.schema.json/references/examples（排除缓存，拒绝符号链接逃逸）；UTF-8 单包上限 2MiB。相对路径规范化后拒绝绝对路径、`..`、跨包读取。files_json 入 skill_package 后不可变；按路径排序做长度前缀编码再 SHA-256，避免分隔符歧义。

```java
String normalized = java.nio.file.Path.of(relativePath).normalize().toString();
if (java.nio.file.Path.of(relativePath).isAbsolute() || normalized.startsWith("..")) {
  throw new BiddingApiException(403, "SKILL_PATH_DENIED", "技能文件不可访问");
}
```

- [ ] `ASSIGN_EMPLOYEES` payload `{analystAgentId,writerAgentId,reviewerAgentId}` 三字段均出现，值可为 `null` 表示当前阶段未绑定该岗位；非空岗位校验 enabled/未删除/当前 workspace/native 且不是 plan_execute，reviewer!=writer。P1 只要求分析员的四项解析技能就绪才能派发解析；编写员与审核员可未绑定，P2/P3 技能尚未提供时不得凭空固定其包或标记为可执行。后续阶段绑定非空岗位时须在有效授权范围固定该岗位所需技能，不是只检查全局“存在”。缺模型/技能用可读配置错误，不自动创建有密钥的配置。GET employees 返回各岗位可用性与缺失原因，不返回内部模型密钥。
- [ ] 配置指纹只含配置 ID、修改版本、模型名、运行模式、有效工具集合、技能 pin digest；凭据值不入 hash。若现有模型配置没有 revision，使用更新时刻/安全持久化版本，不解析密钥。默认初始化只提供三个岗位所需技能清单，绑定/创建员工通过现有平台配置路径完成并回读。
- [ ] 测试 pin 后更新活动文件仍读原包；包中 schema 变更导致新 digest；绑定跨 workspace/disabled/未授技能均拒绝；null 工具授权表示继承、空集合表示全部禁用，不能把空集当继承。Run：同前测试，Expected PASS。
- [ ] Commit：`git commit -m "Make bidding retries execute the same authorized skill package" -m "Tested: Skill pinning and employee scope tests"`，只包含本任务列出的文件。

### Task 4: P1-04 补强平台任务执行接口，保留隔离与错误类型

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingToolScope.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingReadTool.java`
- Create: `mateclaw-server/src/main/java/vip/mate/agent/execution/ProjectExecutionOptions.java`
- Create: `mateclaw-server/src/main/java/vip/mate/agent/execution/ProjectToolPolicy.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/StateGraphReActAgent.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/state/MateClawStateAccessor.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/state/MateClawStateKeys.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ActionNode.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/NodeStreamingChatHelper.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ReasoningNode.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/tool/builtin/SkillLoadTool.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/tool/builtin/SkillFileTool.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeRuntimeTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingRuntimeIsolationTest.java`

**Interfaces:**
- Consumes: Claim / Execution / Failure；P1-03 fixed package、config validation；现有 `vip.mate.agent.context.ChatOrigin` 输入。
- Produces: `BiddingEmployeeRuntime.execute(Claim): Execution`；`static readResult(Flux<AgentService.StreamDelta>,String expectedSkillDigest,String expectedConfigDigest):Execution`；`BiddingToolScope.require(Claim,String toolName,String arguments): void`；`claim(org.springframework.ai.chat.model.ToolContext):Claim` 从服务端受信上下文取active attempt/token并重查。
- Produces: `BiddingReadTool.readSource(String sourceId,long version,String blockId,ToolContext):String`（注册 `bidding_read_source`）；`readMaterial(String materialId,ToolContext):String`（注册 `bidding_read_material`，P1对尚未绑定材料明确拒绝，P2接通Materials.snapshot）。
- Produces: 新 AgentService overload `chatStructuredStream(Long agentId,String message,String conversationId,String requesterId,String thinkingLevel,ChatOrigin origin,ProjectExecutionOptions options): Flux<StreamDelta>`；旧 overload 委托 options=null。

平台选项不引用 bidding 包，避免平台对业务的反向依赖；record 精确定义：

```java
public record ProjectExecutionOptions(
    String attemptId, String modelConfigId, String configDigest, String skillName, String skillDigest,
    java.util.Map<String,String> skillFiles, java.util.Set<String> allowedTools,
    ProjectToolPolicy toolPolicy, int internalRetryLimit, boolean allowFallback,
    boolean injectMemory, int maxIterations) {}
@FunctionalInterface
public interface ProjectToolPolicy {
  void require(String toolName, String arguments);
}
```

- [ ] 写 BiddingEmployeeRuntimeTest 对实际 graph 路径做受控模型异常测试，记录供应商调用数；同时在 BiddingRuntimeIsolationTest 检查两个任务不同会话与撤权。纯 options 单元测试不能代替链路测试。

```java
@Test void rejectsPartialFinalAnswerEvenWhenItIsValidJson() {
  var stream = reactor.core.publisher.Flux.just(
      AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill-digest")),
      AgentService.StreamDelta.finalAnswer("{\"items\":[]}", false),
      AgentService.StreamDelta.event("project_execution_failed", Map.of(
          "code", "STREAM_INCOMPLETE", "category", "TRANSIENT", "resultUnknown", true,
          "partial", true, "stopped", false)));
  var result = BiddingEmployeeRuntime.readResult(stream, "skill-digest", "model-digest");
  assertNull(result.payload());
  assertTrue(result.failure().partial());
}
```

此收集器测试之外必须添加实际故障流测试：fake streaming client 先发合法 JSON 再断开，调用 execute 后断言 payload=null 且底层模型请求计数=1；fixed pin 的 SkillLoad/SkillFile 返回原内容而非活动版本。mock 仅供应商与可控外部依赖，不 mock 被测 runtime。

- [ ] Run：Maven `-Dtest='BiddingEmployeeRuntimeTest,BiddingRuntimeIsolationTest'`，Expected 部分输出、技能回执或隔离断言 FAIL。
- [ ] 为 options!=null 创建隔离图；关闭缓存图共享、历史/长期记忆注入、默认 tools/fallback 扩权；会话 `bidding:<projectId>:<attemptId>` 只用于追踪，权限只来自服务端 options。通过 ConversationService.getOrCreateConversation 绑定 actor/workspace；每次尝试新会话。普通聊天、售前旧签名维持原行为。
- [ ] 把 options 传到实际工具 callback 和审批 replay 路径。先检查 allowedTools，再调用 toolPolicy.require；与 AgentBindingService.getEffectiveToolNames 求交。工具名称白名单为 `load_skill`、`readSkillFile`、`bidding_read_source`、`bidding_read_material`、`bidding_export_document`，按当前skill只开放必需子集；后三个工具由本模块注册，未实现/未授权即不可用。BiddingReadTool只按Claim.inputRefs返回内容，成功读取block后把回执写attempt.tool_receipts_json；字段范围和actor不能由模型参数指定。阻断任意 shell/write/network/message/workflow/批准工具；不能靠 system prompt 实现边界。每次工具执行及结果提交重查 actor/employee/source 权限。
- [ ] SkillLoadTool/SkillFileTool 遇 options 从 skillFiles 读取；成功读取 SKILL.md 发 `StreamDelta.event("project_skill_loaded", Map.of("digest", options.skillDigest()))`；失败/越界无回执。固定包允许读取清单内的根目录 input.schema.json/output.schema.json；子文件固定包读取不可退回活动版本，也不能沿用原普通SkillFileTool只允许references/scripts/templates而使契约不可读。runtime 无对应摘要回执则 `SKILL_NOT_LOADED` 失败。
- [ ] 将 NodeStreamingChatHelper 的固定任务重试限额设0；构建固定模型时调用现有 `AgentGraphBuilder.buildRuntimeChatModel(ModelConfigEntity, RetryTemplate)`，传 `RetryTemplate.builder().maxAttempts(1).build()`，并禁用所有上层 fallback；认证/配额/审批/结构错误保持区别。出现 partial/stopped/timeout/异常结束时，在 ReasoningNode 处理为 finalAnswer 前发终止失败事件，带 `code,category,retryAfterMs,resultUnknown,partial,stopped`；不能只发 `[错误]` 文本后正常结束。不支持关闭内部重试/固定模型的provider返回MODEL_RUNTIME_UNSUPPORTED，不能假称满足重试上限。原有普通聊天行为不改。

```java
return AgentService.StreamDelta.event("project_execution_failed", Map.of(
    "code", "STREAM_INCOMPLETE", "category", "TRANSIENT",
    "resultUnknown", true, "partial", true, "stopped", false));
```

- [ ] runtime 只累积 FINAL_ANSWER 语义的完整输出，2MiB上限；300秒整体超时；maxIterations=12 到限失败。解码失败/Schema失败为 VALIDATION，不猜错误字符串为网络失败。实际模型配置/技能摘要与 Claim 不一致即失败，绝不透明换模型。正常终止显式发送 `project_execution_completed` 事件，含 configDigest/skillDigest；readResult 必须收到正常终止事件、匹配的加载回执且没有失败事件才接受 payload，仅 Flux 正常结束不足以成功。
- [ ] Run：前两测试加现有 `SkillLoadToolTest,SkillFileToolTest,PresalesEmployeeRuntimeTest,PresalesGenerationCoordinatorTest`；检查 streaming helper/fallback 相关现有测试。Expected PASS；另断言 model一次失败只请求一次、普通聊天策略保持既有行为。未跑 graph 整条链路不能标本任务完成。
- [ ] Commit：`git commit -m "Keep project task execution isolated and retry accounting observable" -m "Directive: Preserve ordinary chat and presales behavior when options are absent" -m "Tested: Bidding isolation and existing runtime regressions"`。

### Task 5: P1-05 数据库驱动执行、失败重试与中断恢复

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingScheduler.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingRetryPolicy.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingResultHandler.java`
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V214__bidding_task_actor.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V214__bidding_task_actor.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/kingbase/V214__bidding_task_actor.sql`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingRepository.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSourceService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingTaskTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingRecoveryTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingRetryPolicyTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingFakeRuntime.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingMigrationTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSourceTest.java`

**Interfaces:**
- Consumes: EmployeeBindings.resolve/configDigest/validate、SkillPackages.pin、Dependencies.validate/isCurrent、EmployeeRuntime.execute。
- Produces: `BiddingTaskService.enqueue(Scope,Command,String skillId,String targetId,List<Ref> refs,ObjectNode input): ObjectNode`；`retry(Scope,Command): ObjectNode`；`cancel(Scope,Command): ObjectNode`；`complete(Claim,Execution): void`。
- Produces: `BiddingResultHandler.skillIds():Set<String>` / `accept(Claim,ObjectNode):Ref`；各业务服务实现这两个方法，TaskService通过ObjectProvider惰性读取handler列表以避免与enqueue的构造依赖循环；未知技能拒绝，重复注册启动失败。
- Produces: `BiddingRepository.claimDue(Instant now,String bootId,int limit): List<Claim>`；`BiddingScheduler.dispatchDue(Instant): int`；`recoverInterrupted(String newBootId): int`；`BiddingRetryPolicy.nextDelayMs(Failure,int cycleAttempt,long jitterMs): OptionalLong`。
- Test-only: `BiddingFakeRuntime extends BiddingEmployeeRuntime`（测试构造器注入必要依赖）提供 `enqueue(Execution):void`、`calls():int`，override execute 消费队列；不得用于运行 profile。测试调度执行器用 Runnable::run，生产有界线程池。扫描器同时调用SourceService.readPending(2)，来源读取与AI执行分别有界，不占模型重试额度。
- P1-02 已在 SourceService 设置独立 `@Scheduled` 轮询。Task5 接通 BiddingScheduler 时移除来源读取的第二个定时入口，保留 SourceService.readPending(2) 与启动中断恢复；由 BiddingScheduler 每轮调用一次 readPending(2)，即使模型 worker 容量为 0 也照常扫描。用测试证明没有双轮询且来源读取不消耗模型 slot。

- [ ] 写明确重试策略测试与真实 DB CAS 竞争测试（两个线程领取同一待执行 task，只有一个 Claim），避免只测内存状态。

```java
@Test void retryBudgetIncludesRetryAfterAndStopsAfterThirdAttempt() {
  var f = new BiddingTypes.Failure("RATE_LIMIT", "TRANSIENT", 45000L, false, false, false);
  var policy = new BiddingRetryPolicy();
  assertEquals(45000L, policy.nextDelayMs(f, 1, 0).orElseThrow());
  assertEquals(45000L, policy.nextDelayMs(f, 2, 0).orElseThrow());
  assertTrue(policy.nextDelayMs(f, 3, 0).isEmpty());
  var invalid = new BiddingTypes.Failure("INVALID_OUTPUT", "VALIDATION", null, false, false, false);
  assertTrue(policy.nextDelayMs(invalid, 1, 0).isEmpty());
}
```

- [ ] Run：Maven `-Dtest='BiddingRetryPolicyTest,BiddingTaskTest,BiddingRecoveryTest'`，Expected 缺领取/策略实现 FAIL。
- [ ] 实现事务 enqueue：验证 skill→岗位白名单、输入/员工/权限，固定包、指纹及快照；同业务操作写 task 和 operation；提交后扫描兜底，无必须成功的内存入队。task QUEUED、attempt_count=0。定时器最多2工作线程、每workspace1；容量满时不领取，不先将大量任务设 RUNNING。测试只注入测试用ResultHandler，不绕过令牌/权限/事务逻辑；四个分析skill的正式handler在P1-06注册。
- [ ] V212 的 task 表没有 actor_id，后台领取不能从项目负责人或模型输入推断原操作人。三方言 V214 只追加 actor_id 列，不修改已应用的 V212/V213；enqueue 将经服务端鉴权的真实 actorId 存入 task，claim/retry/recover 始终从该列构造 Scope 并重查权限。迁移前遗留 actor_id 为空的待执行任务必须 fail closed，不能借用 owner 身份执行；迁移测试验证旧表/既有行保留及新列可读写。
- [ ] claim 原子 CAS task.status+active_attempt_id，创建 attempt 和不可猜 token；事务提交后调用模型。领取时校验权限和输入；发现变化转STALE/FAILED，不调用模型。输入只包含实际授权 refs，不读取 latest。产生 Claim 后不持有数据库事务。

```java
if (!"TRANSIENT".equals(failure.category()) || cycleAttempt >= 3) return OptionalLong.empty();
long base = cycleAttempt == 1 ? 10_000L : 30_000L;
long delay = Math.max(base + jitterMs, failure.retryAfterMs() == null ? 0 : failure.retryAfterMs());
return OptionalLong.of(delay);
```

- [ ] 实现 complete 单事务：校验 token / RUNNING /未取消/配置/依赖→写 attempt结果→调用相应业务校验生成候选 revision→标 SUCCEEDED；过期存输出诊断但不生成有效候选。同 token 再提交返回已有结果。写数据库失败最多本地重提3次（100/300/900ms），使用原 Execution，不再调用模型；彻底失败保存中断诊断或由重启恢复标未知。事务失败不留下半个修订。验证失败走独立的失败结果提交事务，保存拒绝输出/错误指针；不能因业务校验抛异常而把失败记录一并丢掉。
- [ ] 失败分类只有连接/429/可恢复5xx的 TRANSIENT可WAITING_RETRY，其余 FAILED/STALE；nextRunAt 落库。Retry-After 不可解析保留诊断用退避；不能自动无限重试。`RETRY_TASK` payload `{taskId}` 用相同输入/配置创建下一attempt、cycle_no+1；不覆盖失败历史。`CANCEL_TASK` 立即使 token 失效；晚到结果拒绝。参数修改须重新 DISPATCH 并传 retryOfTaskId，不能复用原任务快照。
- [ ] 启动 recoverInterrupted 将其他 bootId 的 RUNNING 转FAILED、code=EXECUTION_INTERRUPTED/resultUnknown=true，失效token；QUEUED/到期WAITING_RETRY可重新扫描。不要把重启后的未知调用自动再做。扫描器同时检查 RUNNING.deadline_at；逾期CAS为FAILED/EXECUTION_INTERRUPTED并吊销token，DB恢复后继续此对账，避免进程未重启却永久假运行。取消/超时尽力停止底层订阅并释放slot，结果晚到不允许恢复成功。仅一个调度实例是部署前提；多实例启动不宣称受支持。
- [ ] 测试：三次临时错误用 fake clock/显式 now 推进（不实际睡30秒），每次attempt入库；第四次不调用；人工重试总序号递增；取消前后/撤权后迟到提交拒绝；成功接收后DB故障只重提本地；进程启动标中断；一次评分失败不动兄弟task。日志只task/error类，不打印快照/密钥。
- [ ] Run：同前三测试，Expected PASS；增加线程争用重复运行只在观察到竞态后需要重复验证。Commit：`git commit -m "Make bidding execution recoverable without duplicating accepted results" -m "Constraint: Two automatic retries per manual execution cycle" -m "Tested: Persistent attempts, CAS, cancellation and recovery"`。

### Task 6: P1-06 四项分析技能、契约校验与人工解析基线

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSkillValidator.java`
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingAnalysisService.java`
- Create: `mateclaw-server/src/main/resources/skills/bidding-tender-profile/`
- Create: `mateclaw-server/src/main/resources/skills/bidding-elimination-analysis/`
- Create: `mateclaw-server/src/main/resources/skills/bidding-requirement-analysis/`
- Create: `mateclaw-server/src/main/resources/skills/bidding-scoring-analysis/`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSkillValidatorTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingAnalysisTest.java`
- Test: `mateclaw-server/src/test/resources/bidding/analysis/golden.json`

**Interfaces:**
- Consumes: TaskService.enqueue、SourceService.evidence、Dependencies.validate。
- Produces: `BiddingSkillValidator.validate(String skillId,ObjectNode payload,ObjectNode input): void`（失败抛422含 JSON pointer）；`BiddingAnalysisService` implements `BiddingResultHandler`（skillIds返回四个分析ID）；`dispatch(Scope,Command): ObjectNode`；`accept(Claim,ObjectNode): Ref`；`confirm(Scope,Command): ObjectNode`。

- [ ] 先写畸形/缺字段/未读区块的校验失败测试，输入必须定义完整 fixture，不能靠缺其他字段偶然失败。

```java
@Test void rejectsMissingCoverageForScoring() throws Exception {
  var j = new com.fasterxml.jackson.databind.ObjectMapper();
  var input = (com.fasterxml.jackson.databind.node.ObjectNode) j.readTree("""
      {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术项10分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
      """);
  var payload = (com.fasterxml.jackson.databind.node.ObjectNode) j.readTree("""
      {"schemaVersion":"1","criteria":[],"totalChecks":[],"coverage":{"processedBlockIds":[],"unprocessedBlockIds":[]},"warnings":[]}
      """);
  var e = assertThrows(BiddingApiException.class,
      () -> new BiddingSkillValidator().validate("bidding-scoring-analysis", payload, input));
  assertEquals("COVERAGE_INCOMPLETE", e.code());
}
```

- [ ] Run：Maven `-Dtest='BiddingSkillValidatorTest,BiddingAnalysisTest'`，Expected missing validator/coverage FAIL。
- [ ] 四包分别写触发条件、授权输入、步骤、输出、拒绝条件；明确原文指令不执行、未知字段=null+reason、证据必须在本次block中，不将所有“应/须”当废标项。Skill 必须先读取固定 SKILL.md 及 output.schema.json；包实际执行凭回执，不能把提示词写在Java后只标skillId。
- [ ] 四类 payload 采用共同 `{schemaVersion:"1",coverage:{processedBlockIds,unprocessedBlockIds},warnings}`。profile字段按设计§6；废标items每项 `{id,text,scope,trigger,evidenceRefs,unknowns}`；需求每项 `{id,text,category:"TECHNICAL|COMMERCIAL",constraints,acceptance,evidenceRefs,unknowns}`；评分criteria每项 `{id,parentId,title,score:null|string,unit,rule,requiredProof,evidenceRefs}`，totalChecks含原分/计算分/差额。每条 evidenceRef `{sourceId,version,blockId,quote}`；quote必须原文子串。
- [ ] Schema 在根及对象级 additionalProperties=false，限定类型/枚举/必需字段/长度。使用 Jackson + 本模块显式分技能验证器，不引入通用 schema 引擎；Java validator 与 JSON Schema 共用 valid/invalid examples 做一致性测试。十进制分值用 BigDecimal，若原文不能计算则 unknown，不能总分猜100。
- [ ] `DISPATCH_ANALYSIS` 输入confirmed sourceSet Ref，按12k码点稳定区块分片，过长单表块返回 INPUT_BLOCK_TOO_LARGE而非切掉单元格。四技能×分片 taskgroup落库；基础信息只是需求的可选上下文，不形成隐式硬依赖。读取工具返回授权blocks并记录服务器readBlockIds；processed集合必须覆盖分配集合，未读/未处理不允许标组完整。
- [ ] 分片候选合并到同类分析 revision：同 source/version/block/text 重复可确定性去重，跨源分值/时间/条款矛盾留下 conflict 列表；不“后者自动覆盖前者”。`EDIT_ANALYSIS_ITEM` 产生人工新revision并留证据；`CONFIRM_ANALYSIS` 只允许approver，四类都完整、来源未变、冲突已逐条处置；保存decision和baseline revision，并在同事务预置下一阶段任务意图，P1中未安装目录skill则显示配置待办，不能假成功。
- [ ] 通过黄金fixture验证每条废标/需求/分值与定位；空列表仅可在全读且明确无匹配时通过；跨项目引用、无quote、伪造actor、漏区块、NaN分数全拒绝。确认前再次validate，重复确认幂等，数据库回读必须保存每个skill payload、attempt及基线。
- [ ] Run：同前测试，加 P1-05 任务回归，Expected PASS。Commit：`git commit -m "Require complete evidence-backed analysis before a bidding baseline is confirmed" -m "Tested: Four skill contracts and analysis confirmation gates"`。

### Task 7: P1-07 接通企业风格投标入口、文件与解析页面

**Files:**
- Create: `docs/superpowers/specs/2026-09-25-bidding-p1-ui-blueprint.md`
- Create: `mateclaw-ui/src/features/bidding/routes.ts`
- Create: `mateclaw-ui/src/features/bidding/api/biddingApi.ts`
- Create: `mateclaw-ui/src/features/bidding/api/types.ts`
- Create: `mateclaw-ui/src/features/bidding/shared/state.ts`
- Create: `mateclaw-ui/src/features/bidding/pages/BiddingProjects.vue`
- Create: `mateclaw-ui/src/features/bidding/pages/BiddingWorkbench.vue`
- Create: `mateclaw-ui/src/features/bidding/components/BiddingOverview.vue`
- Create: `mateclaw-ui/src/features/bidding/components/BiddingSources.vue`
- Create: `mateclaw-ui/src/features/bidding/components/BiddingAnalysis.vue`
- Create: `mateclaw-ui/src/features/bidding/components/BiddingTaskDrawer.vue`
- Create: `mateclaw-ui/src/features/bidding/components/BiddingEvidenceDrawer.vue`
- Modify: `mateclaw-ui/src/router/index.ts`
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingProjectService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingAnalysisService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingRepository.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java`
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingProjects.test.ts`
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingWorkbench.test.ts`
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingTasks.test.ts`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingDashboardTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingAnalysisTest.java`
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingTaskTest.java`

**Interfaces:**
- Consumes: 总计划HTTP API；`http` + `scopedConfig(workspaceId,signal)`；useWorkspaceStore.registerBeforeSwitch；members.userId/nickname/username。
- Produces: `biddingApi.get(ws:string,id:string,signal?:AbortSignal):Promise<Project>`；`command(ws:string,id:string,body:Command):Promise<CommandResult>`；list/sources/tasks/evidence同HTTP词典；`isCurrentRequest(capturedWs:string,capturedId:string,currentWs:string,currentId:string):boolean`。TS字段照总计划与Jackson，以字符串 ID 处理。

- [ ] 使用已有 Vitest + createApp + memory router + Element Plus（参考 presalesWorkbench.test.ts，无 Vue Test Utils 新依赖）。先写 workspace 请求代次测试，并写组件测试：请求404显示未启用而非空台账、owner下拉提交userId、切workspace后旧请求不覆盖、409保留编辑内容。
- [ ] 先按 enterprise-ui-design 的 Create 模式写 UI Blueprint：明确真实操作路径、页面职责、空/加载/部分失败/无权限状态、主题映射、桌面/窄屏验收；不增加解释性常驻文案或假按钮。现有 `BiddingAnalysisService` 只有派发/修订/确认写入口，Task 7 须提供经授权与来源依赖重检的解析修订及当前基线读取，支持刷新回读；GET revision 和解析页读取不可跨 Workspace 或绕过来源撤权。由本任务拥有该服务及其测试，避免 Controller 直接查询表或页面从任务逐个拼接业务状态。

```ts
import { describe, expect, it } from 'vitest'
import { isCurrentRequest } from '../shared/state'
describe('workspace response ownership', () => {
  it('does not apply an old workspace response', () => {
    expect(isCurrentRequest('1', 'p1', '2', 'p1')).toBe(false)
    expect(isCurrentRequest('1', 'p1', '1', 'p1')).toBe(true)
  })
})
```

- [ ] Run（UI目录）：`pnpm test src/features/bidding/__tests__`，Expected 新module/交互未实现 FAIL。
- [ ] 建立typed API wrapper，捕获操作开始时ws/id，AbortController取消旧查询，写请求返回时比较代次；POST不自动网络重试。任务轮询只在抽屉打开且有活跃任务时每2s，切workspace/卸载停止。所有 mutation 保持 operationId，409呈现版本冲突且不覆盖本地草稿。

```ts
export const isCurrentRequest = (w: string, id: string, activeW: string, activeId: string) =>
  w === activeW && id === activeId
```

- [ ] 路由 `/bidding` 与 `/bidding/:id` lazy load，MainLayout公共navGroups只加一次nav.bidding；详情高亮按前缀匹配并加入面包屑。沿用当前enterprise theme，不创建新主题。GETcapabilities用于页面只读/停用显示，安全仍由后端判断；成员无法审批不显示可执行确认按钮。
- [ ] 列表4指标：在办、7天内截止、待确认、失败task；与分页相同授权/过滤条件，过期和未知日期分别显示。表格真实成员姓名，缺成员保留“成员不可用”；创建编辑无裸ID输入。概览清晰区分“负责人”和3个员工岗位。
- [ ] `/dashboard` 聚合可落在既有 BiddingRepository，但须沿用列表 workspace 与 name/stage/owner 过滤谓词，截止时间仅在原文能确定日期和时区时计入未来7天；日期不完整或时区不明确显示未知，不从字符串前10位猜测。
- [ ] 文件页显式版本/读取问题/查看原文/确认来源/开始解析；解析分4子面板，业务结果表格、证据抽屉、人工修订/确认。统一task抽屉展示尝试历史、失败原因、重试/取消；诊断JSON折叠。禁用状态有简短业务原因，不堆叠教学文字。
- [ ] 真实浏览器验收时，解析任务列表的“解析项”必须显示四类业务名称，状态及常见失败原因显示可理解的中文（原始代码留在折叠诊断中）；概览阶段和未开放能力不得暴露 `SETUP`、`P2/P3` 等内部阶段代号。任务列表的 skillId 由后端授权列表返回并做回归，不依赖逐行补查详情。
- [ ] 按钮使用 `<el-button type="primary" plain size="small">查看记录</el-button>`；动作组 `display:flex;gap:8px;align-items:center;flex-wrap:nowrap`，表格操作列明确 min-width，窄屏横向滚动或整组折叠，不错位换行。表单标签对齐，正文编辑不挤在统计卡旁；未完成的后三页签显示阶段依赖，避免假操作。
- [ ] Run：UI范围测试、直接eslint、precision、vue-tsc、临时outDir构建（总计划完整命令）；Java `BiddingDashboardTest`。Expected PASS；数字员工配置缺失、只读/撤权、部分解析失败、空数据、刷新回读都必须有交互断言。
- [ ] Commit：`git commit -m "Expose bidding progress and recovery through clear workspace-scoped actions" -m "Tested: Bidding UI states, typecheck and scoped dashboard queries"`，只stage源文件和测试，不stage编译static。

### Task 8: P1-08 验证解析闭环与持久数据启动边界

**Files:**
- Create: `scripts/bidding/check-runtime.py`
- Create: `docs/bidding/runtime.md`
- Create: `docs/bidding/acceptance/2026-09-23-p1.md`
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingRecoveryTest.java`
- Test: `scripts/bidding/test_check_runtime.py`

**Interfaces:**
- Consumes: P1 HTTP路径、任务状态、attempt/skill pin/来源Refs。
- Produces: CLI `python3 scripts/bidding/check-runtime.py --manifest <runtime-manifest.json>`；manifest `{mode:"existing|new",dataDirectory,expectedDatabaseFiles:[absolute paths],backupManifest}`，不得存账号/URL密码；退出0=路径检查通过，退出2=缺失/临时目录/不完整备份。它是启动前检查，不是自动恢复工具。

- [ ] 建脚本测试，existing模式指向空临时目录必须失败；不能创建数据库文件。

```python
import importlib.util
import pathlib
import tempfile
import unittest
spec = importlib.util.spec_from_file_location('check_runtime', pathlib.Path(__file__).with_name('check-runtime.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
class RuntimeCheckTest(unittest.TestCase):
    def test_missing_existing_database_does_not_create_one(self):
        with tempfile.TemporaryDirectory() as folder:
            db = pathlib.Path(folder) / 'business.mv.db'
            self.assertEqual(2, module.check({'mode': 'existing', 'dataDirectory': folder,
                                            'expectedDatabaseFiles': [str(db)], 'backupManifest': None}))
            self.assertFalse(db.exists())
```

- [ ] Run：`python3 -m unittest discover -s scripts/bidding -p 'test_*.py'`。Expected 缺script/check或错误创建库 FAIL。
- [ ] 实现 `check(manifest:dict)->int`：path.resolve、检查existing必须存在且非空；禁止 worktree/output、target、/tmp、系统临时目录保存业务data；new模式须明确，不复用历史环境名；远程数据库不伪造文件检查，manifest另用 `databaseVerified:true` 与受控探针报告摘要并在运行手册明确必须先验证连接/表数据。backupManifest只验证存在与文件校验和，恢复成功仍需独立演练证据。打印路径标识和问题码，不打印环境变量/连接密钥。
- [ ] 使用专用测试profile在临时测试库完成迁移演练和备份恢复，保留原生产/售前/本体库不动。记录H2和可用方言实跑；缺数据库实例记NOT_RUN。启动前确认真正的业务数据目录和已有项目记录数，不因目录丢失新建空库。
- [ ] 真浏览器执行：新建→真实负责人/员工配置→PDF/DOCX上传→确认来源→四类解析→制造一项可恢复失败→只重试该项→查看结果/证据→确认基线→刷新；跨workspace请求与迟到响应另测。记录UI布局1280/1440宽、按钮对齐、无信息墙；所有数据从DB回读。
- [ ] 真实模型用已配置DeepSeek（实际可用性先查，不能只凭历史），现有凭据只从受控配置读取；每次skill加载摘要、尝试、结构化输出入库。模型不存在则该验收NOT_RUN并报告实际阻碍，不填模拟输出；不为通过测试创建伪业务批准。使用一份可授权脱敏真实招标与golden标注，报告四类遗漏/误判，保留来源摘要。
- [ ] 报告按总计划格式分开静态/集成/浏览器/真实模型/方言迁移/恢复。Run脚本单测与P1范围回归，仅对新增修改补必要复验。Expected：本阶段范围没有FAIL，NOT_RUN原因清楚；模型完整证据缺失时不得称首阶段实跑完成。
- [ ] Commit：`git commit -m "Make bidding acceptance reproducible without losing existing business data" -m "Related: docs/bidding/acceptance/2026-09-23-p1.md"`。提交前报告必须记录实际状态，不凭计划声称通过。

## P1 完成判定

从真实界面完成解析基线且DB可回读；A01/A03/A04/A06～09/A14在此阶段适用范围通过；A02只证明4项分析skill，其余4项到P2/P3验证。P1不得宣称已具备目录编制、正文、审核或导出。
