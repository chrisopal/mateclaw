# MateClaw 架构与代码优化 Spec

**规格版本：1.0.0｜2026-09-29｜状态：待实施架构整改 + 已提供门禁工具源码**

目标仓库 `chrisopal/mateclaw`；本次核验 `dev @ ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。实施前重新记录 origin/dev、当前 HEAD、工作树和已有 AGENTS 规则，不回退到旧提交。本文不是 MateClaw 的发行版本，也不代表架构整改已经完成。[R1]

## 1. 决策与优先级

暂停扩大本体、售前、投标和 Delivery 的功能范围，先完成本规格 P0。允许为兼容、安全修复和门禁接入进行必要的小范围功能改动，但不得把新商机管理、原型生成等夹带进架构提交。

执行顺序：**门禁先落地 → 平台依赖方向 → 身份/资料授权 → Controller 与数据访问边界 → API/前端公共能力 → 售前聚合迁移 → 持续封口**。

本规格优先于先前《售前升级 Spec V2.0.0》和《Delivery Spec V1.0.0》的功能扩展排期；不取消其业务需求。重复的改造任务只实现一次，建立任务映射，不重复建设另一套公共执行平台。

## 2. 现状依据与整改目标

| 发现 | 固定提交中的证据 | 整改目标 | 优先级 |
|---|---|---|---|
| 通用执行器认识售前具体类 | `ToolExecutionExecutor` 持有 `PresalesToolPolicy`；同时存在通用 `ProjectToolPolicy.Revalidator` | 业务提供策略；Agent 核心只依赖接口 | P0 |
| 兄弟模块借用本体内部 helper | `presalesApi.ts`、`biddingApi.ts` 导入 `ontologyApi.scopedConfig` | 迁到宿主公共请求层，不改变工作区固定行为 | P0 |
| Controller 直接查数据库 | `BiddingController.task()` 查询任务/项目并遍历材料授权 | 查询服务返回已经授权的类型化详情 | P0 |
| 基础授权多处实现 | `PresalesAccess` 借用 Semantic 身份类；多个位置复制 Workspace/员工 KB 判断 | 共用主体与来源访问入口；保留不同业务批准政策 | P0 |
| 售前整项目聚合 | `V211` 与 `PresalesService` 共享 body_json/版本，列表内存分页 | 独立对象与修订、依赖级并发、服务端分页 | P0 后半段 |
| 类型和样式混杂 | `PresalesRecord` 广泛 any；l(中文,英文)；密集模板和声明 | 稳定 DTO、集中 i18n、按职责拆解、格式门禁 | P0/P1 |
| 现有检查易被误读 | ESLint 不负责统一排版；Node/Vitest 两套测试；精度检查脚本已存在 | 实际调用原检查、补格式/架构门禁，不把缺工具当成功 | P0 |

以上来自上一轮审查及本次核验。没有把旧 README 中“尚未实现/脚本缺失”直接当作现状；例如 `scripts/check-snowflake-precision.sh` 本次已读到真实脚本，新门禁必须保留调用。[R2–R8]

## 3. 不可变边界

1. 同仓库、同 Spring 服务、同 Vue 壳、同身份、同 Workspace；不引入独立账户、Node 后端或另一套 Agent Runtime。
2. 语义 core/application/OWL 的领域隔离保留；不为外观统一改成普通 CRUD。
3. 需求、事实、客户确认、业务批准、技术执行成功是不同概念，不在本次重构中互相转换。
4. 已发布修订、原始证据、历史批准、操作回执、成果原字节、handoff 摘要不得变化。
5. 所有模型输出仍是候选。重构不能将 AI、任务 owner 或浏览器参数升级为审批身份。
6. 权限检查失败、来源不可得、策略未配置均拒绝；不能因解耦去掉原先 fail-closed。
7. 用户内容不是代码、工具许可或系统指令。导出、模板、SVG、Skill 的既有安全约束继续有效。
8. 不批量重命名现有表/ID，不修改旧 Flyway 文件；不要用删除历史或重建空数据库降低迁移难度。
9. 模块停用不删除其历史；普通聊天、其他模块及历史查询按明确能力边界工作。
10. 所有新增指标都是待测目标；不能引用本包自测数量冒充应用测试覆盖率。

## 4. 目标依赖方向

```text
Vue feature pages/components
  → feature api + 明确 DTO
  → 宿主 workspaceRequest / http
  → Controller（HTTP/入参/错误封装）
  → Application Service（用例/事务/业务策略）
  → Repository / Mapper（SQL/CAS/锁）

工作台 execution adapter → agent.execution 公共契约 → 原 Agent Runtime
工作台 integration adapter → Wiki/Semantic/上游工作台的公共服务契约
业务授权策略 → auth/workspace 基础授权 + source access 公共读取检查
```

禁止 `agent/common/auth/workspace/tool/skill → presales/bidding/delivery` 的具体实现依赖。需要装配时使用宿主配置层注册接口实现；不要把业务类改名成“公共”就移动到 core 掩盖依赖。

允许 `presales → Semantic 事实公共端口`、`bidding → Presales 发布公共端口`、`delivery → Presales/Bidding 接收适配器`。禁止跨域直接修改表，禁止经内部 Controller 调用实现服务复用。

建议后端包：`controller`、`service`、`dto`、`repository`、`security`、`integration`、`execution`。这不是一次全仓包名搬迁。先修职责，再逐模块整理。前端继续保留 `features/<domain>`。

## 5. AQ-R01：统一项目执行接入

### 5.1 复用而非重建

优先适配已有 `ProjectExecutionOptions`、`ProjectToolPolicy`、`ProjectToolPolicy.Revalidator`。不得另加 Presales、Delivery 专用字段到 `ToolExecutionExecutor`、Agent 节点、流收集器等核心文件。[R2]

若现有单个 Revalidator 注入不能容纳多个模块，增加宿主拥有的类型化注册/选择接口；注册由可信配置完成。按运行种类选择唯一策略；零个或多个匹配者均显式失败。禁止由模型提供的 kind、conversationId 前缀或字符串路径单独决定授权。

### 5.2 可信执行上下文

固定 `workspaceId / actorId / projectId / taskId / attemptId / inputRefs / skillPin / modelConfigDigest / operationId`。受信上下文由应用服务创建；普通工具参数不能覆盖。数据库存持久标识和不可变值，不序列化 Spring Service、凭证、线程安全上下文。

策略检查点：提交、实际开始、工具调用前、结果接收、人工采用、发布/导出/历史读取。用户或员工禁用、资料撤回、运行取消、attempt 过期后不再接收有效结果；晚到输出可记录为被拒结果，但不得覆盖业务对象。

普通聊天未声明项目执行时沿原通用流程。项目执行声明存在但策略缺失时不能退回普通聊天。

### 5.3 迁移与回归

先写行为刻画测试；引入接口适配后并行比较新旧“判定结果”，不双执行工具。切换后的旧运行按兼容记录读取；不可安全恢复的运行明确终止，人工重发，不自动重复计费。

验收：核心编译依赖无具体工作台；售前/投标/普通聊天均通过；禁用任一模块不拖垮其他模块；延迟工具调用、审批重放和后台身份检查保持。

## 6. AQ-R02：基础授权和来源访问公共化

### 6.1 统一机制，保留业务政策

公共基础服务负责：从可信认证解析当前用户；显式后台 actor 重新查验；Workspace 存在/启用/成员等级；Source 与 Workspace 归属；员工可访问 KB；资源当前可读状态。

工作台保留：谁可批准基线、是否允许项目 owner 批准投标、是否要求双人、客户确认材料怎样登记。现有售前和投标的批准规则不同，迁移时默认保持原规则；改变规则必须另立 ADR 与权限用例，不借“统一”扩大或缩小权限。[R5]

`capabilities` 与执行 API 共用同一政策判定，前端隐藏按钮不算权限控制。仍使用 Workspace 作为隔离边界，不把项目负责人字段伪装成私有 ACL。

### 6.2 统一资料链路

初始 Context 注入、工具读取、重试、历史快照、缓存、下载、预览、handoff 都调用同一受控来源入口。默认取：当前 actor 权限 ∩ 当前员工授权 ∩ 当前项目绑定 ∩ 本次任务固定来源。

若业务允许“用户显式附加资料”成为另一种授权依据，必须建模该依据及有效范围，不能通过任意原文塞进 prompt 绕过员工授权。先保留当前可证明政策，不补造授权。

`SOURCE_WITHDRAWN`（事实支持失效）、`ACCESS_REVOKED`（调用人不可见）、`SOURCE_VERSION_CHANGED`（版本变化）分开。错误可以告诉用户需要核实，但不能泄露不可见资料内容、名字或片段。

### 6.3 权限用例矩阵

至少覆盖 viewer/member/admin/workspace owner/system admin、项目 owner、非成员、停用用户；同步入口与后台任务分别测。覆盖员工 wiki_disabled、只绑定 KB-A、项目引用 KB-B、执行期间撤权、来源撤回、历史成果下载。断言权限不只停留在列表接口。

## 7. AQ-R03：Controller、事务与持久化

优先整改 `BiddingController.task()`。新增/复用 `BiddingTaskQueryService` 与 Repository：加载任务、区分历史审核任务、核验绑定员工、复核所有输入引用，然后返回 DTO。Controller 不解析 body_json、不拼 SQL、不遍历数据执行领域决策。[R4]

将售前/语义应用服务中适合下沉的 SQL 逐用例放到 Mapper/Repository。普通 CRUD 优先现有 MyBatis/MP；复杂 CAS/claim/不可变修订 SQL 可继续使用 JDBC。不得为了换 ORM 改变事务边界、锁序或幂等行为。

用例边界负责事务。远程模型/文件转换不得长时间占用数据库事务；先提交任务再执行；回写执行独立短事务并复核依赖。保存操作结果与业务修改同事务，防止重试双写。

保持真实 HTTP 状态与 `R<T>` 兼容响应；错误码稳定。控制层不把所有失败包装成 HTTP 200，也不借全局 catch 返回空对象假成功。

## 8. AQ-R04：前端公共请求与契约

从 `ontologyApi.ts` 抽取 `scopedConfig` 到 `src/api/workspaceRequest.ts` 或已存在的等价宿主位置。[R3]

必须保留：请求发起时固定 Workspace；Axios 全局拦截器后仍使用该值；AbortSignal；工作区切换后的过期响应丢弃；未保存内容提示；409 保留输入；上传/Blob/ArrayBuffer 的原行为。

**特别测试 transformRequest 组合**：新的 helper 不能覆盖调用方 multipart/自定义序列化函数；不能丢 FormData、误设 boundary 或把 Blob 解成 JSON。JSON 请求解包 `R<T>`；二进制返回原字节，不能共用错误的 envelope 解包。

稳定 DTO 类型包括 Requirement、Clarification、SolutionRevision、GenerationTask、Artifact、Handoff。字段存在性、枚举、空值和 ID 字符串明确。历史未知状态以 `UnknownStatus {raw}` 单独显示，不用 `Known | string` 抹掉枚举约束。

运行时 JSON 从 unknown 经 Schema/校验器映射。不要靠 TypeScript 类型断言证明模型输出可信。保留旧 HTTP wire shape，内部类型化和公开 API 版本化分开实施。

## 9. AQ-R05：售前聚合与存储迁移

与售前升级 Spec 的迁移任务合并执行，不建立第二套命名相近的表。

项目元数据、Requirement/Revision、Clarification/Revision、Solution/SectionRevision、Baseline/Decision、Task/Attempt、Artifact/Handoff 的更新粒度分离。允许单个不可变修订使用经校验 JSON，禁止把无限增长的集合重新装进统一业务 JSON。

业务对象 `version`、任务 attempt、资料 captureVersion、模板版本、Skill 版本互不替代。任务依赖固定到读取的对象版本，不因为无关联系人修改失效。相关需求/章节/产品版本变化必须明确 stale；采用新结果前再检查。

列表 SQL 下推条件与分页；计数不加载正文/录音/全历史。针对选定列表明确索引和执行计划。性能目标在真实环境记录，不用假数据卡片宣称已经提速。

迁移阶段：备份及隔离恢复演练 → 扩展新结构 → 只读回填与精确映射 → 影子读取比对 → 暂停新写/排空任务 → 单一权威切换 → 核验旧 API 与消费者。

旧记录内容、ID、操作回执和时间不补造。旧 HIGH 不直接等同 MUST；ANSWERED 不升级 VERIFIED；内部基线不升级客户确认。原发布文件复制原字节，验证旧 digest；旧 handoff 如依赖序列化次序则保留旧表示，不重新 canonicalize 后称相同。

切换后已存在新写入时，关闭开关不是无损回滚。回退须有经验证的反向转换或隔离恢复/对账步骤；没有则暂停写入、前向修复，不偷偷切回旧 JSON。

## 10. AQ-R06：风格、国际化与组件职责

前端 Prettier：2 空格、单引号、无分号、100 列、LF、单属性换行。Java 使用 AOSP 4 空格格式；版本与格式设定固定，工具升级单独 PR。具体依赖提案在 integration/；不宣称已经安装。

整文件格式化只影响本轮触及文件；格式提交与业务提交分开。OWL corpus、原文快照、DOCX/SVG 已批准字节等不能批量“美化”。格式化器的 includes 只覆盖源码。

界面产品文案迁到宿主 i18n；上传材料、客户原句和 AI 文稿不作为 UI 字符串强行翻译。使用已有 `--mc-*` / `--el-*`，公共 PageHeader/Toolbar/抽屉只抽真正重复交互，不造一套新 UI 框架。

页面容器负责路由和区域编排；需求编辑、生成任务、章节版本、审批等分别组件/composable。只在共享状态需要时使用 Pinia，不复制后端权威状态。避免用压缩代码行数掩盖复杂度。

## 11. 门禁与完成定义

`AGENTS.md + Skill` 规定行为；`gate.py` 做增量词法检查；`verify.py` 调用真实格式、lint、类型、测试、构建；Git hooks 在本地提交/推送前调用；CI 从基线分支加载检查器，保护分支要求固定 required job 成功。

本包词法扫描不是 AST，也不是安全证明。已给出 ArchUnit 测试模板；P0 完成前必须接入编译产物检查和关键行为测试。脚本检查通不过时不能改规则、删除测试或生成新 baseline 来“完成”。详见 RULES、CHECKS 与 CI_SETUP。

P0 完成必须同时满足：AQ-00–AQ-08 通过；核心反向依赖和 Controller DAL 对目标路径零容忍；所有关键行为用例通过；历史发布字节/摘要兼容；真实 CI 和分支保护已验收。仅门禁工具自测通过不算。

## 12. 未包含的工作

本规格不重构所有插件、桌面应用和 WebChat；工具检测到 desktop/webchat 修改时会要求扩展测试适配，不能假称本门禁覆盖。不开通数据库生产写、不部署、不自动添加 GitHub ruleset、不发送外部通知。后续功能开发必须先消费已建立的公共边界。
