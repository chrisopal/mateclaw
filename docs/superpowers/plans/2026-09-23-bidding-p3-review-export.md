# P3 技术标审核到正式成果 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付独立审核、定向修订、候选DOCX、人工审定与同字节正式下载。

**Architecture:** 审核结果与人工决定分别持久化；受控导出工具生成固定版本候选，批准绑定实际文件摘要。正式下载仅服务数据库原字节，并重新校验来源权限与输入有效性。

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

前置：P2 已形成按确认目录组装的不可变 MANUSCRIPT；目录、章节、材料权限和版本关系可验证。Java 根 J=`mateclaw-server/src/main/java/vip/mate/bidding/`，测试根 T=`mateclaw-server/src/test/java/vip/mate/bidding/`。本阶段完成最后两项skill，共八项；正式成果只代表技术标。

| 任务 | 单一职责文件 |
| --- | --- |
| P3-01 | J`BiddingReviewService.java`；审核skill、整改决定与重审 |
| P3-02 | J`BiddingDocxRenderer.java`, `BiddingArtifactService.java`, `BiddingExportTool.java`；导出skill、固定模板、候选字节 |
| P3-03 | J`BiddingApprovalService.java`；精确文件批准、下载重查 |
| P3-04 | UI 审核、候选/正式文件组件；沿用任务与证据抽屉 |
| P3-05 | 全链路测试与真实样例证据；单实例运行与备份手册 |

### Task 1: P3-01 — 独立审核、问题处置和定向修订

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingReviewService.java`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/SKILL.md`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/input.schema.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/output.schema.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/references/rules.md`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/examples/valid.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-technical-review/examples/invalid.json`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSkillValidator.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingWritingService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingMaterials.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingReviewTest.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingWritingTest.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingMaterialsTest.java`.

**Source preflight (2026-09-27):**
材料只读审核入口必须校验当前绑定 reviewer 的独立知识库/页面类型权限，并保持现有 writer 读写规则；禁止为通过审核把 reviewer 冒充 writer。未绑定/未就绪审核员工时保留有效 P2 组装草稿，明确审核尚未派发。

**Interfaces:**
- Produces: `BiddingReviewService.read(Scope):ObjectNode`；`GET /projects/{id}/review` 返回当前正文绑定的审核任务、逐章/跨章覆盖、findings、处置和 HUMAN_TODO；读时重查 reader/material/reviewer ACL，明确历史与当前状态，只有 refs/config/两层 coverage 匹配才报告当前完整审核。
- Consumes: MANUSCRIPT/outline/baseline精确Ref、材料快照、独立reviewer、P1 TaskService。
- Produces: `BiddingReviewService` implements `BiddingResultHandler`，skillIds返回审核技能ID；`BiddingReviewService.dispatch(Scope,Command):ObjectNode`；`accept(Claim,ObjectNode):Ref`；`resolve(Scope,Command):ObjectNode`；`requireReviewed(Scope,Ref manuscript):void`；`static blocksTechnicalApproval(String category,String severity,boolean resolved):boolean`。

- [ ] 测试强制缺失不能以“接受风险”解锁、模型不能批准、旧review绑定旧manuscript。黄金问题由输入内容推导，不硬塞最终“审核通过”fixture。

```java
@Test void unresolvedMissingMandatoryProofBlocksApproval() {
  assertTrue(BiddingReviewService.blocksTechnicalApproval("MISSING_MANDATORY_PROOF", "BLOCKER", false));
  assertTrue(BiddingReviewService.blocksTechnicalApproval("UNSUPPORTED_COMMITMENT", "BLOCKER", false));
  assertFalse(BiddingReviewService.blocksTechnicalApproval("STYLE_SUGGESTION", "SUGGESTION", false));
}
```

- [ ] Run：Maven `-Dtest=BiddingReviewTest`，Expected review gate不存在/错误解锁 FAIL。
- [ ] 输入Schema `{schemaVersion,baselineRef,outlineRef,manuscriptRef,chapters,requirements,criteria,eliminationItems,evidenceSnapshot}`；输出 `{schemaVersion,findings,coverage,limitations,warnings}`，finding `{id,severity,category,chapterRefs,requirementRefs,evidenceRefs,description,recommendation}`，禁止批准/关闭人工待办字段。必须覆盖全部章和必须技术条款；不完整coverage使review候选不能成为有效整本审核。
- [ ] reviewer必须!=writer，独立会话且只读，不加载写作记忆；超过上下文按章节分任务并加一次跨章一致性审核，任务都固定同一manuscript。分章通过不等于整本通过；跨章审核至少检查名称、关键数值、接口/性能承诺、交付边界一致性。
- [ ] `DISPATCH_REVIEW` payload `{manuscriptRef}`；P2 assemble 完成后以manuscript digest为自动派发幂等键；指纹/输入变化旧审核不能复用。保存全部findings结构化结果和原引用；服务器计算门禁，不相信模型summary“通过”。

```java
return !resolved && ("BLOCKER".equals(severity) || java.util.Set.of(
    "MISSING_MANDATORY_PROOF", "UNANSWERED_TECHNICAL_REQUIREMENT",
    "UNSUPPORTED_COMMITMENT", "SOURCE_UNREADABLE", "VERSION_CONFLICT").contains(category));
```

- [ ] `RESOLVE_FINDING` payload `{findingRef,decision:"FIX|DISMISS_WITH_EVIDENCE|DEFER_SUGGESTION",reason,evidenceRefs}`；驳回需真实approver且证据完整；强制材料事实缺失不能驳回伪造。FIX仅选中问题，不改正文。`REVISE_CHAPTER` 交给writing.dispatch，输入current chapterRef及selectedFindingRefs；新candidate待人工采用，采用后新MANUSCRIPT必须重审受影响章及跨章一致性。
- [ ] 技术影响分类由真实 approver 通过 `CLASSIFY_HUMAN_TODO` 完成，payload `{todoRef,affectsTechnical,reason,evidenceRefs}`，expected=精确当前todoRef；采用已有ref/CAS/幂等/权限模式保存不可变决定和新revision。Schema有效 COMMERCIAL 输入形成 owner 真实的待办，初始影响未分类时保守阻断技术审定，不消费不存在的模型 `affectsTechnical` 字段。明确仅商务的分类不关闭待办；分类影响技术但未解决仍阻断。旧todo、无依据、model/member越权、来源变化拒绝；测试经过真实来源与结构化分析输出契约/人类command回读，不SQL硬塞影响字段。
- [ ] 商务待办用业务revision kind=HUMAN_TODO，真实owner/userId、来源与状态；`RESOLVE_HUMAN_TODO` 只真实成员且记录依据，model不能执行该动作。只有影响技术标的未决商务事实才阻断技术成果，技术标批准不改变商务待办状态。
- [ ] 测试同员工审核拒绝、审批工具拒绝、无证据驳回拒绝、修订不自动覆盖、接受修订后旧review失效、一般建议可暂不采纳留理由。Run BiddingReviewTest+Writing/Dependencies相关测试，Expected PASS。
- [ ] Commit：`git commit -m "Separate independent technical review from human acceptance of revisions" -m "Tested: Review coverage, blockers and revision re-review"`。

### Task 2: P3-02 — 受控 DOCX 候选生成与实际字节校验

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingDocxRenderer.java`.
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingArtifactService.java`.
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingExportTool.java`.
- Create: `mateclaw-server/src/main/resources/bidding/templates/technical-v1.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/SKILL.md`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/input.schema.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/output.schema.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/references/rules.md`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/examples/valid.json`.
- Create: `mateclaw-server/src/main/resources/skills/bidding-document-export/examples/invalid.json`.
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V216__bidding_artifacts.sql`.
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V216__bidding_artifacts.sql`.
- Create: `mateclaw-server/src/main/resources/db/migration/kingbase/V216__bidding_artifacts.sql`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingToolScope.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeRuntime.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeBindings.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingDependencies.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingSkillValidator.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingDocxRendererTest.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingArtifactTest.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingMigrationTest.java`.

**Source preflight (2026-09-27):**
现有 runtime 固定 tools 尚未包含导出，writer role 技能白名单尚无 export，Dependencies 尚未识别 TEMPLATE/FORMAT_REQUIREMENTS；本任务沿既有 Claim/角色授权/固定技能包校验 seam 补齐，不能只注册工具。保留 P2 员工绑定兼容，导出派发必须额外验证实际已授权且固定的导出技能包。当前材料绑定仅提供 WIKI_PAGE 文本，不提供 IMAGE_ASSET 原字节；renderer 验证授权图片 map，但生产遇到无受控授权字节的图片必须显式 IMAGE_NOT_AUTHORIZED 阻断，不读取任意 URL/path、不宣称生产图片接入已完成。

**Interfaces:**
- Consumes: P2 BiddingContentBlocks、MANUSCRIPT Ref、模板Ref、formatRequirements Ref；现有 Apache POI。
- Produces: `BiddingArtifactService` implements `BiddingResultHandler`，skillIds返回导出技能ID；`accept(Claim,ObjectNode):Ref` 验证实际候选与manifest后使其成为有效候选；`templates(Scope):List<ObjectNode>`。
- Produces: `BiddingDocxRenderer.render(ObjectNode manuscript,ObjectNode template,Map<String,byte[]> authorizedImages):byte[]`；`BiddingArtifactService.generate(Claim,Ref manuscript,Ref template,Ref format,String mode):ObjectNode`；`verify(byte[] bytes,ObjectNode manuscript):ObjectNode`；`metadata(Scope,String artifactId):ObjectNode`。
- Produces: `BiddingExportTool.generate(String manuscriptId,String templateId,String formatId,ToolContext context):String` 返回结构化 manifest JSON；注册工具名固定 `bidding_export_document`。ToolContext 为现有 `org.springframework.ai.chat.model.ToolContext`，从服务端绑定Claim，不接受模型传scope/path/approve。

- [ ] 写真实DOCX再开验证测试，输入统一内容块，不用Markdown renderer；测试字数、表格、标题与文件有效性。
- [ ] 同时覆盖 P2 实际持久化 MANUSCRIPT：`chapters[].chapter.title/blocks` 为叶子正文，目录父级与顺序从精确冻结的 `outlineRef` 读取。ArtifactService 可以构造受控 render DTO，但须验证转换、父级标题顺序和原文完整保留；不能只通过下方扁平示例而漏掉真实嵌套正文，不接受任意客户端别名。

```java
@Test void generatedDocxRetainsChineseTextAndTableCells() throws Exception {
  var json = new com.fasterxml.jackson.databind.ObjectMapper();
  var book = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
    {"title":"技术标","chapters":[{"title":"实施范围","blocks":[
      {"type":"paragraph","text":"一期两条产线"},
      {"type":"table","columns":["指标","要求"],"rows":[["响应时间","2秒"]]}]}]}
    """);
  var template = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
    {"version":"1","pageSize":"A4","font":"宋体","fontSize":12,"marginMm":25}
    """);
  byte[] bytes = new BiddingDocxRenderer().render(book, template, Map.of());
  try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
    assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("一期两条产线")));
    assertEquals("2秒", document.getTables().get(0).getRow(1).getCell(1).getText());
  }
}
```

- [ ] Run：Maven `-Dtest='BiddingDocxRendererTest,BiddingArtifactTest'`，Expected renderer/候选持久化缺失 FAIL。
- [ ] V216 artifact列：workspace_id/project_id/id/manuscript_ref_json/template_ref_json/format_ref_json/mode/format/digest/byte_size/content/checks_json/generator_attempt_id/status/decision_id/created_at；generator_attempt_id unique。大小超过50MiB返回413，记录导出失败；单项目候选累计容量配置可观测，首版不自动删除历史。
- [ ] JSON模板technical-v1使用A4/25mm、宋体12pt、标题1/2/3级、页码、目录字段、表头重复、行不拆分页、图片最大宽度≤正文宽度。招标格式要求优先，单位明确并验证值域，无法支持的强制格式显式阻断；不能用模板默认值盖掉招标要求。通过 member 授权、项目 expected CAS 与 operationId 幂等的 `PREPARE_EXPORT` command 显式固定模板字节到 TEMPLATE revision，并从已确认基础信息固定 FORMAT_REQUIREMENTS revision；返回精确 templateRef/formatRef。GET `/projects/{id}/templates` 仅读取已准备的 `{ref,name,format}` 及精确格式引用，首次未准备返回明确状态且不产生数据库写入。派发前必须完成准备，`DISPATCH_EXPORT` 必须显式提供精确 manuscript/template/format refs，缺失或失效拒绝，不能自动替换。人工调整格式须另存新revision、校验不得违反强制格式并invalidate旧文件。注册 `SAVE_FORMAT_REQUIREMENTS` action处理此调整，expected=当前formatRef，真实approver执行。
- [ ] POI逐块生成标题、段落、列表、表格、授权图片；只从authorizedImages按materialRef key取bytes，拒绝URL/path/外部relationship，不执行OLE/宏。段落转义，不使用任意HTML。图片先校验PNG/JPEG内容类型和尺寸；保持比例，不凭文件名信任图片。

```java
byte[] data = authorizedImages.get(materialId);
if (data == null) throw new BiddingApiException(422, "IMAGE_NOT_AUTHORIZED", "图片材料不可用");
String digest = java.util.HexFormat.of().formatHex(
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
```

片段中的 materialId 来自已校验 image.materialRef.id；bytes 为 render 返回的原文件。SHA算法封装在 BiddingArtifactService 内，不接受模型声明摘要。
- [ ] `DISPATCH_EXPORT` payload `{manuscriptRef,templateRef,formatRef,mode:"preview|candidate"}` 创建export task；skill真实load后调用受控工具，生成结果暂存当前attempt的不可变candidate。按task/attempt幂等，同一attempt重复工具调用返回同一原字节；禁止模型工具返回任意外部地址或用聊天文本伪文件。生成的中间文件无有效task完成状态前不可读，失败/取消可保留诊断但不可采用。
- [ ] verify通过POI重新打开原字节，校验主文档/章节顺序、全部文本/表格/授权图片、外链不存在、摘要/大小匹配、模板与格式refs存在；checks区分结构检查与人工分页检查。候选采用最终样式不加需删除的水印；preview可水印且不能批准。生成工具只返回候选manifest，不能批准或正式发布。
- [ ] 测试导出重试不重写正文、重复tool同attempt不重复artifact、非法外部链接/本地图片拒绝、无实际文件不能成功、DB提交失败不再次模型调用、候选和预览分离。Run上述测试+runtime受控工具回归，Expected PASS。
- [ ] Commit：`git commit -m "Persist verifiable DOCX candidates before any final approval" -m "Rejected: Rendering model markdown with local image paths | unsafe file access" -m "Tested: DOCX readback, artifact digest and idempotent generation"`。

### Task 3: P3-03 — 人工审定具体文件与同字节正式下载

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingApprovalService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingArtifactService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingReviewService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingController.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingCommandService.java`.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingDependencies.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingApprovalTest.java`.
- Test: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingArtifactTest.java`.

**Interfaces:**
- Produces: viewer-authorized read-only `GET /projects/{id}/artifacts/{artifactId}/approval-context`, deriving exact artifact/digest/manuscript/template/format and whole-book reviewSnapshot refs on the server. Return READY/BLOCKED/STALE/NOT_APPROVABLE with bounded reason codes; revoked permissions stay403 and unknown/cross-scope artifacts404. No GET writes/render and no browser hash reconstruction. POST rechecks current refs/CAS. Tests obtain every approval field through HTTP, including blocked/FIX/preview/stale/revoked contexts and GET side-effect checks.
- Consumes: Access.requireApprover、Dependencies.validate/isCurrent、ReviewService.requireReviewed、ArtifactService.metadata。
- Produces: `BiddingApprovalService.approve(Scope,Command):ObjectNode`；`BiddingArtifactService.download(Scope,String artifactId,String mode):byte[]`；`BiddingApprovalService.requireDownloadable(Scope,String artifactId,String mode):void`。
- Produces: ReviewService 提供写入审定与 viewer 读取分别授权的完整审核证据 seam，汇总当前 reviewKey 的全部章节与跨章 task/result refs。`reviewRef` 必须标识此服务端确定的整本审核快照；不能取任意单章 Ref 充当整本证明。人工 decision 持久化该不可变快照与全部精确 result refs，批准与下载重新验证完整组、正文闭包与当前审核员工材料权限；不得借批准人的身份替代当前下载人的权限。
- Source preflight: 现有 requireReviewed/Dependencies.validate 要求 member，正式 viewer 下载应使用读取权限 seam 和 validateForRead。现有 Repository.invalidate 只传播 revision 依赖，不自动更新 artifact 表；本阶段采用 metadata/approve/download 实时重查全部 refs 并返回安全失效状态，保留历史 decision/bytes，不假定事件已把 artifact 标为 STALE。不新增 Repository 修改职责。

- [ ] 写实际完整candidate生成后的批准/下载回读测试；用fixture创建真实解析/目录/正文/审核，不直接SQL伪造批准。HTTP下载用MockMvc字节数组与DB bytes比较（api JSON helper不适用二进制）。

```java
@Test void previewCannotBecomeFormalByChangingOnlyTheDownloadMode() throws Exception {
  var p = project();
  command(p, ref(p), "APPROVE_ARTIFACT",
      Map.of("artifactId", "missing", "digest", "forged", "reviewRef", Map.of()), "viewer", 403);
  api("GET", "/projects/" + p.path("id").asText() + "/artifacts/missing",
      "owner", otherWorkspace, null, 404);
}
```

- [ ] Run：Maven `-Dtest=BiddingApprovalTest`，Expected 文件批准门禁缺失 FAIL。必须补下述实际bytes场景通过才能完成，不能只依靠示例中的权限测试。
- [ ] `APPROVE_ARTIFACT` payload `{artifactId,digest,manuscriptRef,templateRef,formatRef,reviewRef,inspection:{opened:true,layoutChecked:true,reason}}`，expected=artifact.ref；真实approver，current refs、完整审核、无未处置技术阻断、实际candidate digest一致，inspection是记录人的检查不是后端证明Office已打开。
- [ ] 一个事务锁artifact和相关head/decision，重查所有依赖，写human decision固定 artifactId+digest+refs，然后CAS状态APPROVED；并发两次批准返回同decision或409，不生成不一致approval。变更文档/模板/格式/新补遗让旧批准失效，不能在artifact记录上替换content。来源和人工处置写入沿同一项目锁串行化。导出生成同样在项目锁内重查 refs、attempt 幂等和累计容量后写入不可变 bytes。源码确认 claimDue/claimedWorkspaces 已保证同一 workspace 至多一个 RUNNING task；两个 scheduler 线程服务不同 workspace，不能为了测试放宽此策略或伪造并行 claims。并发回归使用同一个真实 active attempt 的重复受控工具调用，验证只生成/保存一次且两次返回同一原字节，并验证与来源/人工处置变更的项目锁边界。容量保留真实字节边界回读，不宣称已复现不存在的跨 attempt 同项目并发超额。

```sql
UPDATE mate_bidding_artifact
SET status='APPROVED', decision_id=:decisionId
WHERE id=:artifactId AND workspace_id=:workspaceId AND project_id=:projectId
  AND status='CANDIDATE' AND digest=:approvedDigest AND mode='candidate';
```

- [ ] 正式下载：权限和来源链重查→要求decision有效且完整refs未失效→取数据库content→核对digest→返回原bytes。不得调用generate/render、去水印、更新页码或修改zip元信息。header Content-Disposition安全文件名，Content-Type DOCX，Cache-Control:no-store；禁止model自定义下载路径。
- [ ] candidate下载同样重查授权，可供人工检查；preview拒绝APPROVE；已失效正式文件只在有权的历史候选入口显示“已失效”并不能作为当前正式成果下载。历史decision保留，绝不物理覆盖“被谁批准了哪个文件”。
- [ ] 集成测试：candidate bytes=DB bytes=批准后两次formal下载bytes；批准并发变更body/模板/格式403或409；重新render新artifact无旧decision；撤权后元数据/快照/文件都拒绝；强制问题未解决422；model/普通member批准403。对renderer加调用计数，下载不得触发。
- [ ] Run BiddingApprovalTest+BiddingArtifactTest+BiddingChangeImpactTest，Expected PASS。Commit：`git commit -m "Bind final approval to the exact technical bid bytes reviewed by a person" -m "Directive: Formal downloads must never re-render approved artifacts" -m "Tested: Digest equality, approval races and revoked access"`。

### Task 4: P3-04 — 审核、整改与文件审定界面

**Files:**
- Create: `mateclaw-ui/src/features/bidding/components/BiddingReview.vue`.
- Create: `mateclaw-ui/src/features/bidding/components/BiddingArtifacts.vue`.
- Create: `mateclaw-ui/src/features/bidding/components/BiddingApprovalDialog.vue`.
- Modify: `mateclaw-ui/src/features/bidding/pages/BiddingWorkbench.vue`.
- Modify: `mateclaw-ui/src/features/bidding/components/BiddingTaskDrawer.vue`.
- Modify: `mateclaw-ui/src/features/bidding/components/BiddingWriting.vue`.
- Modify: `mateclaw-ui/src/features/bidding/api/biddingApi.ts`.
- Modify: `mateclaw-ui/src/features/bidding/api/types.ts`.
- Modify: `mateclaw-ui/src/features/bidding/shared/state.ts`.
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingReview.test.ts`.
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingArtifacts.test.ts`.
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingTasks.test.ts`.
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingWriting.test.ts`.
- Test: `mateclaw-ui/src/features/bidding/__tests__/biddingWorkbench.test.ts`.

**Interfaces:**
- Consumes: P3 HTTP/Command契约；P1TaskDrawer/P2CandidateCompare与版本Ref。
- Produces: `biddingApi.download(ws:string,id:string,artifactId:string,mode:'candidate'|'formal'):Promise<Blob>`，使用scopedConfig固定ws；`canApproveArtifact(mode:string,state:string,blockerCount:number,canApprove:boolean):boolean` 在 shared/state。

- [ ] 写红灯交互测试：普通member无法审定；预览不能批准；未处理技术问题阻断；技术审定不自动关闭商务待办；审批时409保留待审内容并要求重新检查。

```ts
import { expect, it } from 'vitest'
import { canApproveArtifact } from '../shared/state'
it('approves only an unblocked candidate under the current permission', () => {
  expect(canApproveArtifact('preview', 'CANDIDATE', 0, true)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 1, true)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 0, false)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 0, true)).toBe(true)
})
```

- [ ] Run UI范围测试，Expected 新审核/产物组件和方法缺失 FAIL。
- [ ] 审核页面问题列表支持严重度/章节筛选、证据定位、选择整改、附证据驳回、暂不采纳建议。点击整改派发数字员工，返回候选后进入比较/采用，页面不会直接覆盖章节。审核覆盖/限制作按需展开，状态与按钮不要使用原始enum。
- [ ] 商务待办显示“待判断 / 影响技术标 / 仅商务事项”；真实 approver 通过 `CLASSIFY_HUMAN_TODO` 提交当前 todoRef、判断、理由和来源证据。未判断阻断审定，不能通过关闭待办绕过；仅商务事项可以保持待处理且不阻断技术审定。判断与关闭待办分别操作，409 保留输入并刷新当前版本；不能要求模型生成旧 schema 不支持的 affectsTechnical 字段。

```ts
export const canApproveArtifact = (mode: string, state: string, blockers: number, allowed: boolean) =>
  mode === 'candidate' && state === 'CANDIDATE' && blockers === 0 && allowed
```

- [ ] 文件区明确分预览/待审定/正式成果。先“生成待审定文件”→“下载检查”→“审定此文件”→“下载正式技术标”；审定对话框显示文件名、正文/模板版本与检查勾选，digest等技术详情折叠。只有明确需要的业务说明，禁止大段教学文案。
- [ ] 每个行操作共用蓝色边界按钮与固定gap，禁用态有简短原因；窄屏整组对齐。下载blob创建object URL后回收，403清理缓存；换workspace关闭对话框并丢弃旧网络结果。审批期间输入变化后刷新门禁，不靠按钮disabled代替API校验。
- [ ] Run UI范围+售前回归+eslint/precision/vue-tsc/临时build；浏览器点选审核、整改、接受、重审、候选检查、审定、formal下载并刷新，保存实际文件与服务端摘要核对。失败需修复后仅重跑受影响检查。
- [ ] Commit：`git commit -m "Make review and exact-file approval explicit in the bidding workbench" -m "Tested: Review actions, approval UI and download permission states"`。

### Task 5: P3-05 — 完整技术标验收与交付运行说明

**Files:**
- Modify: `mateclaw-server/src/main/resources/skills/bidding-document-export/SKILL.md` declare only the fixed `bidding_export_document` allowed tool and explicit bundle version so existing archive/install upgrades deliver it; preserve project scope, exact reference and candidate-only checks. Full-app HTTP export regression must prove actual tool execution, never fake an AvailableTool row or manually assemble runtime capabilities.
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingHttpFixture.java` narrow slice fixture adaptation to supply current employee/skill grant resolution dependencies introduced by the authorized pin repair; keep scoped visibility and revoked/foreign/private denial expectations, and do not mock authorization in the separate full-app EndToEndTest.
- Modify: `mateclaw-server/src/main/resources/skills/bidding-scoring-analysis/SKILL.md` explicit scoring bundle version only, using existing archive/install upgrade mechanism to deliver the already committed schema; do not relax schemas or rewrite existing project pins.
- Modify: `mateclaw-server/src/test/java/vip/mate/skill/workspace/BundledSkillSyncerTest.java` first install and historical unversioned scoring-directory upgrade regressions with isolated skill roots.
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingSkillPackagesTest.java` authorized new scoring pin includes current criterionIds schema while old project package digest/files remain unchanged.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingTaskService.java` task-list pinned skill-name visibility and MySQL-compatible numeric ID casts only; preserve exact workspace/project package join and current actor/project authorization. Root isolated MySQL8.0.46 probe rejects CAST AS VARCHAR(64) with1064 and accepts CAST AS CHAR(64).
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingTaskTest.java` task-list builtin/global visibility regression, retaining foreign private and wrong project/workspace package denial.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingOutlineService.java` same builtin/global pinned-name visibility compatibility only.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingWritingService.java` same builtin/global pinned-name visibility compatibility only.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingReviewService.java` same builtin/global pinned-name visibility compatibility only.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingArtifactService.java` same builtin/global pinned-name visibility compatibility only.
- Modify: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEmployeeBindingsTest.java` narrow current visibility/grant/pin regression only.
- Modify: `docs/superpowers/plans/2026-09-23-bidding-p3-review-export.md` root-controller scope reconciliation only.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingAnalysisService.java` only for confirmed builtin/global skill-pin visibility compatibility at dispatch.
- Modify: `mateclaw-server/src/main/java/vip/mate/bidding/BiddingEmployeeBindings.java` only if necessary to reuse its existing current authorized skill-resolution seam; no role/grant/model/pin rules relaxed.
- Create: `mateclaw-server/src/test/java/vip/mate/bidding/BiddingEndToEndTest.java`.
- Create: `mateclaw-server/src/test/resources/bidding/golden/technical-tender.json`.
- Create: `docs/bidding/acceptance/2026-09-23-full.md`.
- Create: `docs/bidding/skills.md`.
- Modify: `docs/bidding/runtime.md`.

**Interfaces:**
- Consumes: 八项skill、全HTTP流程和A01～A16；真实模型验收不使用BiddingFakeRuntime。
- Produces: 绑定提交/环境/输入/任务/attempt/revision/artifact/下载摘要的验收报告；八包字段版本与岗位绑定说明；备份恢复与单实例部署检查清单。

- [ ] 先写端到端负向门禁测试，保证测试框架真实走API，没用SQL把业务状态直接改为通过。

```java
@Test void cannotSkipAnalysisAndOutlineByPostingWritingCommands() throws Exception {
  var p = project();
  command(p, ref(p), "DISPATCH_WRITING", Map.of("chapterIds", List.of("c1")), "member", 409);
  command(p, ref(p), "DISPATCH_EXPORT", Map.of("mode", "candidate"), "member", 409);
}
```

- [ ] Run：Maven `-Dtest=BiddingEndToEndTest`。Expected 若可跳阶段则FAIL；已通过的门禁不为制造RED而删除现有实现。补正向串联与恶意引用测试，覆盖剩余未保护行为。
- [ ] 从零测试workspace经真实API创建3岗位绑定，上传脱敏招标、解析、确认、目录、分章、补材料、审核/定向修订/重审、候选、批准、下载；模拟测试仅fake模型输出但保持结构化校验/SQL/auth/renderer真实。再运行一份真实模型样例，两个结果分开记录。
- [ ] 创建golden JSON由人工逐条标注来源定位、废标条件、必须技术需求、分值/总分、强制目录、格式。计算匹配/遗漏/误判数；强制废标/技术条款/评分项不允许存在未解释漏项，歧义保留人工处置。总分不清楚就报告未知，不强制凑100。golden不从同一个模型答案自动生成。
- [ ] 故障矩阵：429带Retry-After、连接失败/5xx、部分JSON后断流、错误JSON、跨源引用、数据库提交失败、服务重启、取消迟到、撤权、补遗/模板变化、单章失败、审定并发修改。每项读task/attempt/revision确认状态；不得仅看toast。
- [ ] Office实检候选DOCX的目录、章节层级、表格跨页/表头、图片比例、中文字体、页眉页码、空白页、内容截断。POI验证不等于分页通过。检查使用只读副本，不用Word另存后的字节替换数据库文件。若必须另存更新字段才能满足格式要求，则本次格式验收失败；修正受控导出器后重新生成candidate、检查并审定。本地不具备Office预览则标人工检查NOT_RUN，不自动称DOCX已完成视觉验收。
- [ ] 输出八技能manifest（skillId、岗位、schemaVersion、固定摘要、实际加载attempt）；与数据库读取一致。验证不访问bid-agent的代码路径、网络端口、包或数据库；不要为了证明独立性停止用户无关服务，可在隔离测试环境禁用它并检查运行依赖。
- [ ] 运行总计划Java范围测试、改动运行时相关售前/技能回归、UI测试、eslint/precision/typecheck/build、`git diff --check`；MySQL/Kingbase迁移和备份恢复实际可用才PASS。指标只task/error/use，不含正文；监测队列最老等待、失败类别、模型用量、结果未知、晚到计数。
- [ ] 真实浏览器按A15走所有关键按钮，1280/1440布局、长标题、空数据/只读/失败/过期/409/切workspace均回读，正式下载摘要与DB一致。收敛未完成项到报告，不把A01～16部分通过包装为全部完成。
- [ ] Commit：`git commit -m "Record reproducible evidence for the independent technical bidding workflow" -m "Related: docs/bidding/acceptance/2026-09-23-full.md"`。报告中已区分各项实际状态；没有执行证据的检查不能声称通过。

## P3 完成判定

A01～A16均有明确证据，阻断缺陷为零；真实模型产生完整技术标，人工检查具体候选文件并审定，正式下载与已审文件字节一致。自动化测试通过但真实样例/Office/必要数据库验证未完成时，交付状态只能是“实现完成，相关验收未完成”，不能称完整上线就绪。外部投标提交、盖章、报价和商务标写作始终不在本阶段范围。

Task5 source-driven scope reconciliation (2026-09-27): new full-app workspace HTTP assignment obtains four valid numeric pins to globally visible builtin packages; dispatch then rejects ANALYSIS_SKILL_UNAVAILABLE because AnalysisService.pinForName requires the builtin row workspace to equal the project workspace. This contradicts the existing SkillRuntimeService.matchesWorkspace policy (builtin/global visible, foreign workspace-owned skills excluded) already used by employee readiness and claim validation. Preserve current actor/workspace/project membership, role grants, active package digest and runtime validation. Resolve pinned name using the existing authorized visibility rule, never an unrestricted ID/name lookup. Fix only the two declared production seams if required, retain the failing full HTTP test as RED and prove a fresh workspace can dispatch and claim globally visible builtin pins. Foreign non-builtin workspace skills must remain excluded; use existing binding authorization regression plus a focused check if the repair opens a new branch. No SQL-seeded business revisions, fixture ACL mocks, default-workspace workaround or custom clones of reserved builtin slugs. Record this proven integration fix rather than marking the required positive chain NOT_RUN.

Sol downstream preflight confirms the same exact-workspace contradiction in OutlineService writer outline pin, WritingService writer pin, ReviewService reviewer pin and ArtifactService export pin. Root declares those four narrow seams plus EmployeeBindingsTest before edits. Reuse one existing-binding authorized resolver; require the pinned numeric ID and exact fixed name, active visibility/current grants and original immutable project package digest; never switch to another name-matching package. Preserve ArtifactService scoped package count and all enqueue/claim/current config checks. Full fresh-workspace HTTP positive chain must exercise all five repaired dispatch stages, and a focused resolver regression must reject foreign non-builtin skills and revoked/non-active/grant-invalid pins.
