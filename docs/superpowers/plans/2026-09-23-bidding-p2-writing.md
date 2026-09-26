# P2 解析基线到技术标草稿 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付精确版本交接、目录确认、分章写作与可追溯整本正文。

**Architecture:** 复用P1持久任务/固定技能/权限边界。售前只通过可选精确快照接收；目录与每章拥有独立版本和选定指针，变更通过引用关系传播失效。

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

前置：P1 的真实模型解析、持久任务与权限工程链路已通过隔离样例验证。2026-09-26 用户要求继续 P2；允许工程实现推进，P1 真实脱敏文件的质量验收仍待完成，不能据此宣称真实投标业务已验收；沿用总计划全部公共类型。Java 根 J=`mateclaw-server/src/main/java/vip/mate/bidding/`，测试根 T=`mateclaw-server/src/test/java/vip/mate/bidding/`。

| 任务 | 单一职责文件 |
| --- | --- |
| P2-01 | J`BiddingHandoffService.java`, `BiddingMaterials.java`；现有售前服务加显式历史版本读取；V215 两张表 |
| P2-02 | J`BiddingOutlineService.java`；目录技能与覆盖校验 |
| P2-03 | J`BiddingWritingService.java`, `BiddingContentBlocks.java`；写作技能、章节候选与采用 |
| P2-04 | J`BiddingDependencies.java` 增补变更影响与重新确认 |
| P2-05 | UI 中独立的交接、材料、目录、写作组件；共用任务/证据抽屉 |

### Task 1: P2-01 显式接收售前发布版本与授权材料

**Files:**
- Create: J`BiddingHandoffService.java`, `BiddingMaterials.java`。
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V215__bidding_materials.sql`、`mysql/V215__bidding_materials.sql`、`kingbase/V215__bidding_materials.sql`（同 migration 根）。
- Modify: `mateclaw-server/src/main/java/vip/mate/presales/PresalesService.java`, `PresalesController.java`（同 presales 目录）；J`BiddingController.java`, `BiddingCommandService.java`, `BiddingDependencies.java`。
- Test: T`BiddingHandoffTest.java`, `BiddingMaterialsTest.java`；`mateclaw-server/src/test/java/vip/mate/presales/PresalesIntegrationTest.java`。

**Interfaces:**
- Consumes: P1 Access/Dependencies/Ref；现有 `PresalesService.get(String scope,String projectId):ObjectNode`；`WikiKnowledgeBaseService.getById(Long)` / `findVisibleById(Long agentId,Long kbId)`；`WikiPageService.getById(Long)`。
- Produces: `PresalesService.handoff(String scope,String projectId,String releaseId):ObjectNode`，保留旧两参数API行为；增加GET `/api/v1/presales/projects/{id}/releases/{releaseId}/handoff`。
- Produces: `BiddingHandoffService.options(Scope,String presalesProjectId):List<ObjectNode>`；`receive(Scope,Command):ObjectNode`；`BiddingMaterials.bind(Scope,Command):ObjectNode`；`snapshot(Scope,String agentId,List<Ref>):ObjectNode`；`requireReadable(Scope,String agentId,Ref):void`。

- [ ] 写独立建项不依赖售前bean的集成测试；售前打开的另一个测试配置创建两个发布版本，用existing PresalesIntegrationTest的真实基线/发布流程，修改当前澄清后分别接收旧版/新版。

```java
@Test void independentProjectsDoNotRequirePresales() throws Exception {
  var p = project();
  assertFalse(p.path("id").asText().isBlank());
  api("GET", "/handoff-options?presalesProjectId=missing", "member", workspace, null, 409);
  assertEquals(p.path("id"),
      api("GET", "/projects/" + p.path("id").asText(), "member", workspace, null, 200).path("id"));
}
```

- [ ] Run：Maven `-Dtest='BiddingHandoffTest,BiddingMaterialsTest'`，Expected 未实现显式版本/独立开关路径 FAIL。
- [ ] V215 handoff表：workspace_id/project_id/presales_project_id/release_id/baseline_ref/solution_ref/snapshot_json/digest/actor_id/received_at；material表：workspace_id/project_id/source_kind/external_id/version/digest/content_json/access_ref_json/validity。接收幂等仍用operation表，允许同售前不同标段建不同项目，不把 release_id 设全局唯一。
- [ ] 新handoff精确定位PUBLISHED release、该版solution/baseline与已保存的原始artifact摘要；不要调用要求“最新baseline”的现有releaseGate。复核该发布自己的decision与来源引用；历史发布缺少可证明快照时返回409 `HISTORICAL_SNAPSHOT_UNAVAILABLE`，不能从当前数组拼造过去。
- [ ] 历史发布时的澄清只从其冻结引用读取；没有冻结澄清可返回 `historicalClarificationsAvailable:false` 和空历史列表，不能宣称“当时没有未决项”。接收当时新增事项放独立 `receivedNotes`，标时间与来源。对今后发布，在发布事务里持久化handoffSnapshot（含release当时的refs），不改既有批准规则；售前回归覆盖发布文件摘要和snapshot。

```java
var release = releases.stream()
    .filter(r -> releaseId.equals(r.path("id").asText()))
    .findFirst().orElseThrow(() -> new BiddingApiException(404, "NOT_FOUND", "发布版本不存在"));
if (!"PUBLISHED".equals(release.path("status").asText())) {
  throw new BiddingApiException(409, "PUBLISHED_RELEASE_REQUIRED", "请选择已发布版本");
}
```

以上片段用于投标接收侧；售前服务使用其现有异常类型，避免引入 presales→bidding 编译依赖。`releases` 是 get(scope,id).withArray("releases") 转换的 ObjectNode 列表。

- [ ] `RECEIVE_HANDOFF` payload `{presalesProjectId,releaseId,expectedDigest,receivedNotes}`，界面预览摘要与接收时不符409；双scope鉴权后事务存快照+receiver+material refs，customerConfirmationStatus 保持 UNCONFIRMED。投标用 ObjectProvider<PresalesService> 可选获取，未启用不影响app启动。
- [ ] `BIND_MATERIAL` payload `{kind:"WIKI_PAGE",knowledgeBaseId,pageId,expectedDigest,applicability}`；服务端检查KB workspace、员工可见KB和页类型权限，page所属KB/有效状态。把当前授权内容保存固定修订，任务只引用显式选定材料。后续读取重查origin权限和引用链；一页被撤权，不得通过已复制JSON、任务诊断或下载继续泄漏。失效材料保留记录但不可新用。
- [ ] 知识库检索仅返回候选，不能自动把所有命中当权威事实；售前结论/历史案例必须显式用途适配。每项引用标来源、时间、版本、适用项目；不能由skill扩大绑定范围。
- [ ] 测试：重复接收同operation一条、不同请求409、接收旧版后源发布更新不覆盖、撤权后材料/任务快照/派生成果403、伪KB/page关联404；售前关闭独立建项通过。Run：上述2测试+PresalesIntegrationTest，Expected PASS。
- [ ] Commit：`git commit -m "Preserve the exact presales context accepted by each bidding project" -m "Rejected: Reading latest mutable clarifications | changes historical facts" -m "Tested: Versioned handoff, material authorization and presales regression"`。

### Task 2: P2-02 目录技能、覆盖关系与人工确认

**Files:**
- Create: J`BiddingOutlineService.java`。
- Create: `mateclaw-server/src/main/resources/skills/bidding-outline-planning/SKILL.md`, `input.schema.json`, `output.schema.json`, `references/rules.md`, `examples/valid.json`, `examples/invalid.json`（同技能目录）。
- Modify: J`BiddingSkillValidator.java`, `BiddingCommandService.java`, `BiddingTaskService.java`, `BiddingDependencies.java`, `BiddingAnalysisService.java`, `BiddingEmployeeBindings.java`。
- Test: T`BiddingOutlineTest.java`, `BiddingOutlineValidatorTest.java`。

**Interfaces:**
- Consumes: confirmed ANALYSIS_BASELINE Ref、Materials.snapshot、TaskService.enqueue。
- Produces: `BiddingOutlineService` implements `BiddingResultHandler`，skillIds返回目录技能ID；`BiddingOutlineService.dispatch(Scope,Command):ObjectNode`；`accept(Claim,ObjectNode):Ref`；`save(Scope,Command):ObjectNode`；`confirm(Scope,Command):ObjectNode`；`static requireAcyclic(Map<String,String> parentById):void`。

- [ ] 写目录循环和未确认baseline越级测试，建立真实baseline fixture后测强制技术条款/强制目录未映射不能确认。

```java
@Test void outlineRejectsCyclesAndUnknownParents() {
  var e = assertThrows(BiddingApiException.class,
      () -> BiddingOutlineService.requireAcyclic(Map.of("a", "b", "b", "a")));
  assertEquals("OUTLINE_CYCLE", e.code());
  assertThrows(BiddingApiException.class,
      () -> BiddingOutlineService.requireAcyclic(Map.of("a", "missing")));
}
```

- [ ] Run：Maven `-Dtest='BiddingOutlineTest,BiddingOutlineValidatorTest'`，Expected gate/tree校验缺失 FAIL。
- [ ] 写输入Schema `{schemaVersion,baselineRef,profile,requirements,criteria,eliminationItems,materials}`；输出 `{schemaVersion,chapters,unmappedItems,warnings}`。chapter `{id,parentId:null|string,order,title,instructions,mandatoryOutlineRefs,requirementRefs,scoringRefs,materialRefs}`，仅技术正文；商务待办可引用但不得悄悄编入技术承诺。
- [ ] validator验证ID唯一、父节点存在、无循环、深度≤6、最多300章、排序唯一。缺失映射可以是候选但不能确认；mandatoryOutline和必响应技术条款都须落叶章节映射，一条映射多章允许并显示。用DFS三色或沿parent追溯做检测，不只检查 parent!=self。

```java
for (String start : parentById.keySet()) {
  var seen = new java.util.HashSet<String>();
  for (String id = start; id != null; id = parentById.get(id)) {
    if (!parentById.containsKey(id)) throw new BiddingApiException(422, "OUTLINE_PARENT", "目录上级不存在");
    if (!seen.add(id)) throw new BiddingApiException(422, "OUTLINE_CYCLE", "目录存在循环");
  }
}
```

- [ ] 先补 Dependencies 对固定业务修订 Ref 的校验：workspace/project/kind/id/version/digest 精确匹配，baseline/outline 必须是当前选定且已确认，递归检查 input_refs_json 的来源/材料授权，禁止循环/缺失/未知引用；读路径保持 reader 授权，执行路径保留 actor 授权。不得只把业务 Ref 省掉来绕开验证。沿用 Task1 已实现的材料重授权，避免依赖环。
- [ ] P2 编写岗位只要求目录和正文技能，不要求 P3 才交付的导出技能。更改岗位要求不静默重写旧固定包，项目必须显式重新绑定才能固定新增技能；P3 再补导出要求。Task2 单独工程验证可用明确 fixture 包；在 Task3 交付正文技能前不得宣称实跑岗位配置已完成。
- [ ] 强制目录项没有既有 ID：由已确认 baseline 的 mandatoryOutline 数组顺序和该条规范化内容摘要生成稳定键 `mandatory-outline-<index>-<sha256>`，随输入 baselineRef 固定。不得给 P1 原结果补造 ID 或用标题作唯一键。该版全部 TECHNICAL requirement ID 必须被落叶章节覆盖；COMMERCIAL 只作待办，不能凭不存在的 mandatory 字段筛选。投影从 baseline.payload.analyses[四个稳定技能名] 取实际字段，引用和覆盖校验必须使用同一投影。
- [ ] 注册 `DISPATCH_OUTLINE`、`SAVE_OUTLINE`、`CONFIRM_OUTLINE`。SAVE payload为完整目录候选，比较outline ref；CONFIRM payload `{outlineRef}`，真实approver且baseline当前有效，事务写decision/选定ref。模型返回的approve/status字段由additionalProperties=false拒绝。人工目录改动生成新revision，不覆盖skill原结果。
- [ ] 在P1的CONFIRM_ANALYSIS事务接通目录自动派发（operationId派生于decision ID，唯一）；仅 CONFIRM_ANALYSIS 的显式 payload.autoPlanOutline=true 才表示用户本次确认后编写目录的意图，并把该意图随 decision 持久化；员工/技能配置可用时创建QUEUED，缺配置存可见待办。旧确认与未勾选默认不自动派发。重放确认不重复dispatch；目录确认后不越过人工意图自动重写已有正文。
- [ ] 测试：未确认baseline不派发、目录候选不解锁批量写作、空目录/循环/跨项目refs拒绝、角色不足403、强制覆盖缺失422、同decision重放只有一组任务。Run前两测试+P1 analysis回归，Expected PASS。
- [ ] Commit：`git commit -m "Require an approved and traceable technical outline before writing" -m "Tested: Outline structure, coverage and confirmation gates"`。

### Task 3: P2-03 分章写作、批量任务与候选采用

**Files:**
- Create: J`BiddingWritingService.java`, `BiddingContentBlocks.java`。
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-writing/SKILL.md`, `input.schema.json`, `output.schema.json`, `references/rules.md`, `examples/valid.json`, `examples/invalid.json`（同技能目录）。
- Modify: J`BiddingSkillValidator.java`, `BiddingTaskService.java`, `BiddingCommandService.java`。
- Test: T`BiddingWritingTest.java`, `BiddingContentBlocksTest.java`。

**Interfaces:**
- Consumes: confirmed outlineRef、baselineRef、Materials.snapshot、TaskService.enqueue、Dependencies.isCurrent。
- Produces: `BiddingWritingService` implements `BiddingResultHandler`，skillIds返回写作技能ID；`BiddingWritingService.dispatch(Scope,Command):ObjectNode`；`accept(Claim,ObjectNode):Ref`；`edit(Scope,Command):ObjectNode`；`adopt(Scope,Command):ObjectNode`；`assemble(Scope,Command):ObjectNode`。
- Produces: `BiddingContentBlocks.validate(ObjectNode chapter,List<Ref> allowedMaterials):void`。

- [ ] 写不可信内容块拒绝测试、真实DB的迟到候选/两章节并行测试。恶意路径不应走到渲染器。

```java
@Test void writingCannotEmbedLocalFilesOrRawHtml() throws Exception {
  var j = new com.fasterxml.jackson.databind.ObjectMapper();
  var chapter = (com.fasterxml.jackson.databind.node.ObjectNode) j.readTree("""
    {"chapterId":"c1","blocks":[{"type":"image","path":"/etc/passwd"}]}
    """);
  assertThrows(BiddingApiException.class, () -> new BiddingContentBlocks().validate(chapter, List.of()));
}
```

- [ ] Run：Maven `-Dtest='BiddingWritingTest,BiddingContentBlocksTest'`，Expected 缺少块/采用校验 FAIL。
- [ ] 写作skill输入 `{baselineRef,outlineRef,chapterId,requirements,criteria,materials,previousChapterRef?,selectedFindingRefs?}`；输出 `{schemaVersion,chapter:{chapterId,blocks},responses,citations,missingMaterials,unresolvedItems,warnings}`。禁止补造证书、案例、性能承诺；缺依据返回missingMaterials/unresolvedItems，不藏在流畅正文中。
- [ ] 内容块有限类型：heading `{level:1..6,text}`、paragraph `{text}`、list `{ordered,items:[text]}`、table `{columns:[text],rows:[[text]]}`、image `{materialRef,caption,alt}`。无任意HTML/脚本/路径/URL；每表行列一致；每块文本按2MiB总限检查。image必须为显式授权图片material，表格尺寸超模板可排版范围时标格式问题，不能悄悄裁列。
- [ ] `DISPATCH_WRITING` payload `{outlineRef,chapterIds,materialRefs,retryOfTaskId?}`，每落叶章节一个task；去重 chapterIds，比较该章targetRef及已确认outline。批量任务一章失败不影响成功兄弟。派发时已选正文Ref入input，不使用当前project.version作为全部章并发锁。
- [ ] `EDIT_CHAPTER` payload `{chapterId,blocks,responses,citations,missingMaterials,unresolvedItems}` 存人工revision，ref CAS；人工编辑操作更新该章节head；`ADOPT_CHAPTER` payload `{chapterId,candidateRef}` 比较当前章及candidate.inputRefs，STALE不可采用。skill成功只创建candidate，不改变selectedChapterRef；编辑或采用后触发依赖失效而不改其他章正文。

```sql
UPDATE mate_bidding_head
SET selected_ref_json=:newSelection, version=version+1
WHERE workspace_id=:workspaceId AND project_id=:projectId
  AND kind='CHAPTER' AND object_id=:chapterId AND version=:expectedVersion;
```

head 已由 P1-01 创建，当前选择 CAS 只修改对应章节指针；所有历史 revision 内容不可变。0行则409，同一operation重放返回已保存结果，不重复递增版本。
- [ ] `ASSEMBLE_MANUSCRIPT` payload `{outlineRef,chapterRefs}`，按确认目录顺序创建不可变MANUSCRIPT，所有选定章节与引用精确包含；有缺材料可存草稿但不能伪装审定。相同refs摘要去重。整本写作完成后自动派发审核接线在P3-01实现，P2显示“待审核”业务状态。
- [ ] 测试：并行两章均能完成；人工改章后旧任务标STALE且不更新指针；重复采用不双增版本；空章/未映射技术需求不能组装完整稿；跨项目材料拒绝；输出超限明确失败，JSON成功不代表有材料依据。
- [ ] Run前两测试+P1 Task/Retry回归，Expected PASS。Commit：`git commit -m "Protect human chapter revisions while digital employees write independently" -m "Tested: Chapter CAS, batch isolation and explicit candidate adoption"`。

### Task 4: P2-04 补遗、材料变更与局部重新确认

**Files:**
- Modify: J`BiddingDependencies.java`, `BiddingSourceService.java`, `BiddingAnalysisService.java`, `BiddingOutlineService.java`, `BiddingWritingService.java`, `BiddingCommandService.java`。
- Test: T`BiddingChangeImpactTest.java`。

**Interfaces:**
- Consumes: P1 Dependencies.validate/isCurrent/invalidate、所有业务revision.inputRefs及当前head。
- Produces: `BiddingDependencies.impact(Scope,Ref changed):ObjectNode` 返回 `{affectedRefs,unaffectedRefs,unknownRefs,formalBlocked}`；`reconfirm(Scope,Command):ObjectNode`，action `CONFIRM_CHANGE_IMPACT`。

- [ ] 为真实DB构造source→baseline→outline→两chapter→manuscript引用链；source变更仅直接引用一章，另一章有可证明未影响的refs。测试最初两章都不能绕过未知影响直接导出，确认影响后只恢复未受影响部分。

```java
@Test void missingImpactReviewPreventsFormalProgress() throws Exception {
  var p = project();
  command(p, ref(p), "CONFIRM_CHANGE_IMPACT",
      Map.of("changedRef", Map.of("kind", "SOURCE", "id", "foreign", "version", 2, "digest", "x"),
             "unchangedRefs", List.of(), "resolutions", List.of()), "owner", 404);
}
```

- [ ] Run：Maven `-Dtest=BiddingChangeImpactTest`，Expected 新动作/引用检查 FAIL。
- [ ] 输入关系从已经保存的inputRefs读，反向遍历到正文/审核/产物；sourceSet变更先让正式门禁false，发现未完整记录依赖时列unknownRefs，不能判无影响。来源补遗分析产生新增/修改/取消/矛盾项，不自动覆盖原文。

```java
var queue = new java.util.ArrayDeque<Ref>();
var visited = new java.util.HashSet<Ref>();
queue.add(changed);
while (!queue.isEmpty()) {
  Ref current = queue.removeFirst();
  if (!visited.add(current)) continue;
  queue.addAll(repository.dependents(scope, current));
}
```

本任务为 BiddingRepository 增加 `dependents(Scope,Ref):List<Ref>` 与 `markNeedsReconfirmation(Scope,Collection<Ref>):void`；引用SQL包含scope。不使用全表加载到内存过滤。
- [ ] `CONFIRM_CHANGE_IMPACT` payload `{changedRef,unchangedRefs,resolutions:[{ref,decision,reason,evidenceRefs}]}`，approver确认，验证resolution覆盖冲突、证据属于新旧输入；强制材料缺失不允许以“接受风险”取消阻断。未受影响正文可保留原bytes，但产生新的关联/重新确认revision以绑定新baseline；受影响章节重写/人工改后重新审核，旧review不可续用。
- [ ] 权限变更即便没有revision变更也由validate在查询/执行/采用/下载时重查；同一个actor的失权不能变成全项目永久封禁，其他有权actor可重新确认来源。运行中任务input已过期只能保存诊断；不可自动迁移task到新input。
- [ ] 测试：只改截止时间无需全章重写，但文件批准失效；改变强制技术指标影响命中章；未知引用先阻断；材料撤权后拒绝快照读取；新旧版本并存可追溯；晚到candidate不覆盖人工选择。
- [ ] Run本测试和Source/Analysis/Outline/Writing范围回归，Expected PASS。Commit：`git commit -m "Invalidate only provably affected bidding work when source versions change" -m "Tested: Supplement impact, authorization changes and stale results"`。

### Task 5: P2-05 接通版本接收、目录与正文工作区

**Files:**
- Create: `mateclaw-ui/src/features/bidding/components/BiddingHandoffDialog.vue`, `BiddingMaterials.vue`, `BiddingOutline.vue`, `BiddingWriting.vue`, `BiddingCandidateCompare.vue`（同 components 根）。
- Modify: `mateclaw-ui/src/features/bidding/pages/BiddingWorkbench.vue`, `api/biddingApi.ts`, `api/types.ts`, `shared/state.ts`（同 bidding 根）；`components/BiddingAnalysis.vue` 增加明确的确认后编写目录选项。
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingOutline.test.ts`, `biddingWriting.test.ts`, `biddingHandoff.test.ts`。
- Create: `docs/bidding/acceptance/2026-09-23-p2.md`。
- Required blueprint: `docs/superpowers/specs/2026-09-26-bidding-p2-ui-blueprint.md`。

**Interfaces:**
- Consumes: P2全部已定义Commands与精确Ref；P1 TaskDrawer/EvidenceDrawer；共用成员/员工列表。
- Produces: API `handoffOptions(ws:string,presalesProjectId:string):Promise<HandoffOption[]>`，HandoffOption `{releaseId:string,digest:string,title:string,publishedAt:string}`；统一 command 方法承载 receive/save/confirm/adopt，不新建重复endpoint。

- [ ] 在已有Vue测试基座写基线未确认时禁止目录派发、目录未确认批量写作不可用、旧候选不能采用、手动编辑冲突保留草稿、交接必须选择明确版本的交互测试。共享state加 `canAdopt(candidateState:string,currentVersion:number,expectedVersion:number):boolean`。

```ts
import { expect, it } from 'vitest'
import { canAdopt } from '../shared/state'
it('keeps stale candidates out of the selected chapter', () => {
  expect(canAdopt('STALE', 3, 2)).toBe(false)
  expect(canAdopt('CANDIDATE', 3, 2)).toBe(false)
  expect(canAdopt('CANDIDATE', 3, 3)).toBe(true)
})
```

- [ ] Run UI范围测试，Expected 未实现组件/规则 FAIL。
- [ ] 交接对话框先选售前发布版本→预览需求/方案/风险/摘要→接收；source更新只显示有新版本，不替换。材料面板显示适用范围和有效性，撤权内容不在浏览器缓存继续显示；404/403清空正文。
- [ ] 目录显示章节树与覆盖未映射项；“保存目录”与“确认目录”分离。正文左栏260px/中间minmax(0,1fr)，证据按需抽屉，不永久三栏塞满；章节标题+状态+紧凑动作栏，长内容独立滚动。窄于1100px章节树折叠，不压缩正文至不可读。

```ts
export const canAdopt = (state: string, current: number, expected: number) =>
  state === 'CANDIDATE' && current === expected
```

- [ ] 候选比较显示本次输入版本、变化内容、缺材料与选定状态，“采用”与“继续修订”蓝色动作组；采用前后Ref保持后端为准。批量状态按章节显示，单章失败有自己的重试；不能一个大spinner挡住成功章节。
- [ ] 脏数据同时拦路由切换/workspace切换/关闭页签；保存409保留内容并允许重新读取对比，无自动覆盖。目录变更/补遗影响展示受影响章与必须确认事项，不堆叠实现说明。
- [ ] Run UI范围测试+售前回归+eslint/precision/vue-tsc/临时build；真实浏览器验收版本接收、目录确认、分章与批量、候选采用、刷新/409/撤权、局部重试。对完整正文重新读DB验证选定Refs及来源摘要，报告模拟/真实模型分别状态。
- [ ] Commit：`git commit -m "Let bid owners review and adopt chapter work without losing version context" -m "Tested: Outline, handoff and writing interactions"`。

## P2 完成判定

可以从独立项目或售前版本交接得到可追溯技术标草稿；目录与章节的人工选择均持久化；补遗不会悄悄让旧内容保留有效批准。此阶段尚无正式审核批准与正式DOCX下载，不能把组装草稿叫最终标书。
