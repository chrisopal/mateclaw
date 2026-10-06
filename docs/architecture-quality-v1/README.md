# 架构检查与实施设计入口

**当前范围调整（2026-10-06）**：按用户要求采用新数据完成售前 V2、真实生成和 Delivery；不验收旧数据/旧消费者兼容；Kingbase=TODO。实施与覆盖差异见[新数据设计](../plans/2026-10-06-presales-v2-newdata-design.md)，该调整不等于相关功能已通过。

本目录以用户提供的 1.0.0 规范为需求来源。原始 ARCHITECTURE_SPEC、RULES、CHECKS、ACCEPTANCE、CODEX_TASKS 等保留原文；其中“执行下一阶段”的示例提示词不扩大本轮授权。

当前状态以[整改收口表](ACCEPTANCE_PROGRESS.md)及[工程分类JSON](acceptance-progress.json)为准：售前主要 Service/Workbench 结构整改已收口；46项中39项已有相称工程证据（含本轮新数据 V2 四项及 Delivery），正式签收仍分开。本轮剩余真实模型阻塞，Kingbase延期、旧兼容排除；另有4项远端治理配置缺口，不以代码行数继续追加拆分。

下面按切片保留的工程记录描述各自执行时点；其中“尚未提交”“结构未完成”等历史文字不覆盖当前收口表。原始要求和正式验收台账保持，历史失败证据不删除。

根据项目负责人补充要求，这套检查现已写入根 AGENTS、工程门禁 Skill、PR 模板和审核台账，默认适用于全项目后续开发。可依据实际调整，但调整也必须提供覆盖差异与验证证据并经过审核。

- [实施设计](IMPLEMENTATION_DESIGN.md)：现状、公共契约、阶段依赖、事务/迁移/回退与 ADR。
- [检查台账](REVIEW_CHECKLIST.md)：AQ-00–11、AR/TS/UI/DB 规则与 AC-01–46 的责任和证据。
- [当前验证记录](SETUP_EVIDENCE.md)：实际命令、结果、存量、未测范围和启用边界。
- [机器可读验收台账](acceptance-register.json)：所有 AC 场景默认 NOT_RUN，不从脚本自测推导应用验收。
- [原始架构要求](ARCHITECTURE_SPEC.md)、[验收要求](ACCEPTANCE.md)、[CI 启用流程](CI_SETUP.md)。

## 日常执行

```sh
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
# 仅当预期内容已完整暂存且没有未跟踪输入时：
python3 -B scripts/quality/verify.py --mode commit
# SSH 远端推送：保留完整 pre-push 门禁，并维持长检查期间的连接
scripts/quality/push-checked.sh -u origin <branch>
```

dev 只证明词法增量与门禁自测，输出 SCAN_PASS 不等于架构合规或可提交。commit 模式拒绝部分暂存；不能通过清理用户材料、降低规则或跳过测试绕过。CI、CODEOWNERS 和分支保护需要实际合入、远端运行与独立签收；本地安装不是远端强制已生效。


最新 SQL 列表投影片：[AQ06_LISTING_PROJECTION_ACCEPTANCE.md](evidence/2026-10-03/AQ06_LISTING_PROJECTION_ACCEPTANCE.md) / [manifest](evidence/2026-10-03/listing-projection-test-results.json)。现有表派生事实、四类原子 writers、旧筛选/故障顺序与 wire 保持；真实 MySQL 29 项及 205 条 mysqldump 恢复证据不替代 Kingbase/完整 V2/生产回退/业务 QA。

Java 迁移入口与冻结源码检查已补强，见 [AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md](evidence/2026-10-03/AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md)。尚有版本化请求 hash/历史回执兼容、闭包人工审阅、独立维护人批准和远端强制等工作；46 正式 AC 仍 NOT_RUN。

AQ07 字节码检查已落入实际测试目录，AR-001 宿主六个 main 路径及 AR-002 工作台三个 main 路径已在 policy 中封口；验证与覆盖边界见 [AQ07_ARCHUNIT_INSTALL_ACCEPTANCE.md](evidence/2026-10-04/AQ07_ARCHUNIT_INSTALL_ACCEPTANCE.md)。纯语义应用自有 port 可用，Controller 不可依赖持久化包/类型；非空聚合检查不证明每个未来包存在。维护人批准及远端强制仍未验证。

后续成果读取修复见 [AQ05_ARTIFACT_READ_ACCEPTANCE.md](evidence/2026-10-04/AQ05_ARTIFACT_READ_ACCEPTANCE.md)：下载/预览不能通过一起替换存储内容和 digest、未声明文件或空冻结摘要绕过发布原字节校验。修复已通过 HTTP/H2 回归、[真实 MySQL 24 项组件验证](evidence/2026-10-04/AQ05_ARTIFACT_MYSQL_ACCEPTANCE.md)及 [MySQL HTTP/来源授权 17 项验证](evidence/2026-10-04/AQ05_ARTIFACT_MYSQL_HTTP_ACCEPTANCE.md)；本次空库迁移至 V219 并 validate，合成 Office 文件原字节与有限结构可读。MockMvc/既有 Wiki/PAT/I18n mocks 不等于完整生产 HTTP，Kingbase、升级恢复、真实历史/Office 交付仍未验收，46 正式 AC 未关闭。

最新 handoff 冻结读取修复见 [AQ05_HANDOFF_FREEZE_ACCEPTANCE.md](evidence/2026-10-04/AQ05_HANDOFF_FREEZE_ACCEPTANCE.md)：与指定版本共用冻结 snapshot，发布后澄清/新基线不改写交付事实，Service 净减少 21 行；384 项回归中 383 通过/1 既有 PPT skip，真实 MySQL 发布与读取 10 项通过。旧记录缺 snapshot 的 latest 现返回 409；工程证据不替代历史样本、浏览器、正式 AC、完整 commit 门禁或维护人批准。

最新已发布成果与冻结 fit 来源读取切片见 [AQ05_PUBLISHED_ARTIFACT_READ_ACCEPTANCE.md](evidence/2026-10-04/AQ05_PUBLISHED_ARTIFACT_READ_ACCEPTANCE.md)：历史下载/预览复用完整冻结事实，候选保持实时 gate；补齐 fit-only 证据撤回、删除、exclusion 的共同复核。47 类400项（399通过/1既有skip）、真实MySQL24项通过，复用现有持久化端口，无新bean/表/依赖。独立COMMENT已回读；commit门禁和正式AC未完成。宽class库存校正为4089绝对classpath目录库存，不代表loaded coverage。

启动时禁用语义的后续工程验证见 [AQ05_DISABLED_STARTUP_ACCEPTANCE.md](evidence/2026-10-05/AQ05_DISABLED_STARTUP_ACCEPTANCE.md)：新增8项启动缺bean/四读/来源及员工撤权合同，H2含既有状态测试共9/9；两个独立MySQL各9/9，额外CI环境明确核验source_kind列collation与SQL大小写命中后仍拒绝坏kind。32相关生产源码保持前批身份。该定向Spring/合成历史夹具不替代完整应用、普通聊天、完整AC05或真实旧数据验收。

候选发布快照职责拆分见 [AQ05_RELEASE_SNAPSHOT_ACCEPTANCE.md](evidence/2026-10-05/AQ05_RELEASE_SNAPSHOT_ACCEPTANCE.md)：Service 1175→1153 行，包内构建器负责内容选择与独立复制；原事务、权限、存储及发布更新保留。原实现23项刻画/消费回归通过，提取后49类411项（410通过/1既有skip），最终格式调整后三项刻画通过。无新依赖/bean/表；本批未重跑数据库方言，完整结构整改及正式AC仍未完成。

发布规则收敛见 [AQ05_RELEASE_POLICY_ACCEPTANCE.md](evidence/2026-10-05/AQ05_RELEASE_POLICY_ACCEPTANCE.md)：Service 1153→1117 行，复用 SolutionPolicy 管理纯基线/评审规则，删除重复评审遍历和 coverage 转发；实时证据检查仍逐条保持原顺序。提取前26/26，提取后49类418项（417通过/1既有skip），正式 base Spotless 通过。整体重构、正式 AC、完整提交门禁和远端强制仍未完成。

错误边界整改见 [AQ05_ERROR_BOUNDARY_ACCEPTANCE.md](evidence/2026-10-05/AQ05_ERROR_BOUNDARY_ACCEPTANCE.md)：共享内部拒绝从 ProjectItems 独立，HTTP 错误数据改为固定 ErrorData；原 Service SemanticApiException 出口与 wire 保持。原实现89/89，迁移后50类423项（422通过/1既有skip），格式检查通过；旧嵌套源码/编译类均已移除。完整 DTO、其余用例及正式验收仍开放。

版本输入边界修复见 [AQ05_VERSION_INPUT_ACCEPTANCE.md](evidence/2026-10-05/AQ05_VERSION_INPUT_ACCEPTANCE.md)：四个 HTTP 入口统一拒绝版本取整/字符串转换/溢出，PATCH 改为保留完整原载荷的元数据 DTO。真实 RED 23项失败，修复后新38/38、全回归51类461项（460通过/1既有skip），旧命令回执 hash 对照及格式检查通过。数字字符串等输入兼容明确收紧；外部客户端、方言、正式 AC、完整提交及远端强制尚未完成。

精确修订比较修复见 [AQ05_EXACT_REVISION_ACCEPTANCE.md](evidence/2026-10-05/AQ05_EXACT_REVISION_ACCEPTANCE.md)：方案基线、批准语义引用、发布需求/语义修订不再通过asInt截断形成别名。复用既有Items边界并保留历史整数字符串、草稿未知文本；真实RED37项失败，最终51类514项（513通过/1既有skip），新增53项通过。版本递增耗尽、完整结构拆分、迁移及正式验收仍开放。

受检版本递增见 [AQ05_VERSION_LIFECYCLE_ACCEPTANCE.md](evidence/2026-10-05/AQ05_VERSION_LIFECYCLE_ACCEPTANCE.md)：四类递增及生成accepted版本统一防止溢出，直接writer核对JSON/列一致性，生成入口检查启动和收尾容量；回放优先保持。RED94项22失败、追加一致性2失败，最终51类544项（543通过/1既有skip）。已耗尽RUNNING记录仍需受控迁移恢复；未实施长期扩容、并发配额或正式业务验收。

生成/取消应用边界拆分见 [AQ05_GENERATION_USECASE_ACCEPTANCE.md](evidence/2026-10-05/AQ05_GENERATION_USECASE_ACCEPTANCE.md)：Controller 201→42行，生成/取消/员工查询移入应用服务，请求记录移至Dtos，原事务/权限/回放保持。原实现61/61；迁移后51类555项（554通过/1既有skip），真实HTTP/H2独立连接证明先提交再排队。完整DTO、其他用例、V2迁移及正式AC继续开放。

基线批准职责拆分见 [AQ05_BASELINE_APPROVAL_ACCEPTANCE.md](evidence/2026-10-05/AQ05_BASELINE_APPROVAL_ACCEPTANCE.md)：Service1124→1067行，可信需求/澄清/图绑定/声明/来源与引用捕获集中到包内批准准备器；admin、事务、回放与最终保存保留。原实现69/69，提取后51类567项（566通过/1既有skip），增强真实HTTP/H2拒绝后三表不变证据。完整结构整改与正式验收继续开放。

累计工作树完整应用工具链预验收见 [AQ_FULL_TOOLCHAINS_ACCEPTANCE.md](evidence/2026-10-05/AQ_FULL_TOOLCHAINS_ACCEPTANCE.md)：Java执行6416项通过、70项既有条件跳过，Vitest1093、Node5、cost16项通过，前端类型/精度/两种构建及适用格式/lint通过。手工应用检查不是精确暂存树commit门禁，正式AC、未集成迁移、维护人批准与远端强制仍未完成。

售前员工查询固定DTO见 [AQ05_EMPLOYEE_DTO_ACCEPTANCE.md](evidence/2026-10-05/AQ05_EMPLOYEE_DTO_ACCEPTANCE.md)：runtime/service/HTTP统一Employee记录，去掉开放Map拼装；旧36/36、最终52类589项（588通过/1既有PPTskip）、前端原42项解码合同通过。原筛选/顺序/字符串ID/viewer前置授权保持，完整领域DTO与正式验收仍开放。

项目列表查询用例拆分见 [AQ05_LIST_QUERY_ACCEPTANCE.md](evidence/2026-10-05/AQ05_LIST_QUERY_ACCEPTANCE.md)：Service1067→1024行，独立查询服务承接viewer/分页/原子投影读/故障映射；Controller直接调用，无转发或循环依赖。旧104/104、最终52类598项（597通过/1既有PPTskip），未知扩展/null/SQL故障顺序保持。完整领域DTO、V2迁移与正式验收仍开放。

完整应用模块组合验证见 [AQ01_FULL_CONTEXT_ACCEPTANCE.md](evidence/2026-10-05/AQ01_FULL_CONTEXT_ACCEPTANCE.md)：真实生产组件扫描与独立JVM八组合8/8，真实登录/工作区HTTP核验BIDDING_DISABLED、SEMANTIC_DISABLED、PRESALES_UNAVAILABLE，以及售前项目创建/回读。待初始化启动加隔离owner夹具不替代默认seed、普通聊天、方言或QA；正式AC-05继续NOT_RUN。

普通聊天全应用链路及无 usage 模型归属修复见 [AQ01_ORDINARY_CHAT_ACCEPTANCE.md](evidence/2026-10-05/AQ01_ORDINARY_CHAT_ACCEPTANCE.md)：真 main 入口八模块组合均完成普通聊天/真实工具/HTTP持久化回读，最终22类136项通过。仅失败尝试不伪造模型归属；独立复审与中间隔离失败留档。合成单模型、H2有限SQL观察不替代真实供应商/备用模型归属、后台cron、正式AC与提交/远端CI。

备用模型归属与终态流错误修复见 [AQ01_FAILOVER_ATTRIBUTION_ACCEPTANCE.md](evidence/2026-10-05/AQ01_FAILOVER_ATTRIBUTION_ACCEPTANCE.md)：真实主模型401后由配置备用模型完成工具、回答及保存，八模块组合均回读正确备用身份且无项目表查询；55项定向/启动回归通过。失败尝试不再提前广播终态error，备用成功零error、全链失败恰一error。loopback/H2不替代真实供应商、正式AC及远端CI。

售前执行版本精确匹配见 [AQ01_EXECUTION_VERSION_ACCEPTANCE.md](evidence/2026-10-05/AQ01_EXECUTION_VERSION_ACCEPTANCE.md)：快照、公共执行选项、工具前重验、入队/取消和终态CAS复用已有正版本解析，拒绝截断别名；新增75项、RED58失败，修复后50类660项中659通过/1既有PPT条件skip。历史合法整数字符串保持，坏记录受控修复和正式AC仍未完成。

重启恢复CAS竞争修复见 [AQ01_RECOVERY_CAS_ACCEPTANCE.md](evidence/2026-10-05/AQ01_RECOVERY_CAS_ACCEPTANCE.md)：恢复失败后最多3次重读重验，保留并发编辑并跳过终态/新运行/已删除项目；真实H2新增7项，RED3失败，修复后50类667项中666通过/1既有skip。连续冲突、坏版本记录的后续修复及真实进程重启/正式AC仍待验证。

真实进程重启工程验证见 [AQ01_PROCESS_RESTART_ACCEPTANCE.md](evidence/2026-10-05/AQ01_PROCESS_RESTART_ACCEPTANCE.md)：真实HTTP发起任务、请求到达合成模型后halt，两个后续独立JVM证明版本2→3→3、FAILED/INTERRUPTED_BY_RESTART和无二次写入；完整启动矩阵9/9。生产恢复代码本批未变，真实厂商计费、多方言/多实例、坏记录恢复与正式AC仍未完成。

来源撤权修复策略拆分见 [AQ05_REPAIR_POLICY_ACCEPTANCE.md](evidence/2026-10-05/AQ05_REPAIR_POLICY_ACCEPTANCE.md)：Service1024→959行，窄修复命令与安全投影集中包内纯策略；修复缺stage历史对象读取时的输入修改。旧84/84、最终51类680项（679通过/1既有PPTskip），权限/事务/回放原位保持，完整结构整改及正式AC继续开放。

来源与语义查询用例拆分见 [AQ05_SOURCE_QUERY_ACCEPTANCE.md](evidence/2026-10-05/AQ05_SOURCE_QUERY_ACCEPTANCE.md)：Service959→898行，四只读入口移入独立应用服务并由Controller直连，证据返回现有固定DTO；语义Optional绑定只转换404，原权限/来源/错误顺序保持。53类695项（694通过/1既有PPTskip）及真实main矩阵9/9，完整前端/领域结构及正式验收仍开放。

售前查询生命周期拆分与空Workspace遮罩修复见 [AQ09_WORKBENCH_QUERY_ACCEPTANCE.md](evidence/2026-10-05/AQ09_WORKBENCH_QUERY_ACCEPTANCE.md)：页面1874→1696行，查询/成员/portfolio及请求取消集中模块，模板/style原字节保持。原394绿、真实RouterView两项RED、最终售前416及全UI1115/1115、Node5/5、类型/精度/非修复lint格式/两模式构建通过。独立COMMENT无可操作发现；真实浏览器、正式QA、完整结构整改与远端强制仍开放。

员工执行会话职责拆分见 [AQ09_EXECUTION_SESSION_ACCEPTANCE.md](evidence/2026-10-05/AQ09_EXECUTION_SESSION_ACCEPTANCE.md)：页面1696→1638行，员工查询/生成弹窗/生成和取消收敛到单一会话模块，保留页面scope、receipt、版本接纳及轮询。旧实现新增6项页面刻画通过，最终售前448/448、全UI1147/1147、Node5/5及双构建通过；独立COMMENT无发现，模板/style原字节保持。正式浏览器/QA及完整结构整改仍未完成。

操作回执请求摘要碰撞修复见 [AQ06_REQUEST_HASH_ACCEPTANCE.md](evidence/2026-10-05/AQ06_REQUEST_HASH_ACCEPTANCE.md)：Service接入V2新写与严格旧回执双读，歧义legacy明确409；前端保留草稿并禁止盲重提。真实HTTP RED 12项9失败，最终143/143、扩展712通过/1既有skip、全UI1151/1151、真实MySQL17/17与三JVM重启通过。独立COMMENT无缺陷，发布双读顺序、真实旧样本/Kingbase/浏览器/QA/正式AC和远端强制仍开放。设计基线见 [兼容设计](evidence/2026-10-05/AQ06_REQUEST_HASH_COMPATIBILITY_DESIGN.md)。

工作台提交与下载职责拆分见 [AQ09_WORKBENCH_MUTATIONS_ACCEPTANCE.md](evidence/2026-10-06/AQ09_WORKBENCH_MUTATIONS_ACCEPTANCE.md)：页面1638→1461行，command/save/approve/archive集中提交会话，file/handoff集中下载生命周期；原scope/receipt/锁/来源修复保持，模板/style字节不变。独立审核发现的测试递归和保存顺序覆盖缺口已修复，最终115定向、全UI1191/1191、Node5/5及类型/格式/lint/双构建通过；正式浏览器/主题窄屏/QA及完整结构整改仍开放。

发布实时授权职责拆分见 [AQ05_RELEASE_AUTHORIZATION_ACCEPTANCE.md](evidence/2026-10-06/AQ05_RELEASE_AUTHORIZATION_ACCEPTANCE.md)：Service911→861行，独立策略每次复核模块、本体、精确事实修订和证据来源；角色/事务/幂等/文件持久化仍由原用例负责。原110、定向118全部通过，扩展721项720通过/1既有PPT环境skip，正式Spotless通过。完整Service/DTO、V2迁移和正式验收仍开放。

材料、需求澄清与能力匹配面板拆分见 [AQ09_DISCOVERY_PANELS_ACCEPTANCE.md](evidence/2026-10-06/AQ09_DISCOVERY_PANELS_ACCEPTANCE.md)：页面1461→1226行，3个同域展示组件消费原权限和具体领域DTO；筛选生命周期仍由父级持有。拆分前81、新增刻画4、拆分后85及最终全UI1195/1195通过，类型/lint/格式/Node5/双构建通过。正式浏览器业务验收和完整架构整改仍开放。

任务结果接收职责拆分见 [AQ05_TASK_ACCEPTANCE_ACCEPTANCE.md](evidence/2026-10-06/AQ05_TASK_ACCEPTANCE_ACCEPTANCE.md)：Service861→799行，包内接收器集中完整任务身份、事务内权限围栏、runtime重验与模型校验；Service继续控制授权/回放/CAS及持久化。原94/94、新17项及定向111/111、最终738项（737通过/1既有PPT环境skip）。正式DTO/V2迁移、历史任务恢复及QA验收仍开放。


新建生成任务DTO见 [AQ05_QUEUED_TASK_DTO_ACCEPTANCE.md](evidence/2026-10-06/AQ05_QUEUED_TASK_DTO_ACCEPTANCE.md)：18字段改由固定记录声明，字符串ID、初始状态、显式null、顺序与快照保持，原事务/回执/保存后排队不变。原实现62/62，替换后64/64，最终744项（743通过/1既有PPT环境skip）。这是新任务类型边界，完整历史任务DTO/V2迁移与正式验收仍开放。

澄清保存类型化见 [AQ05_CLARIFICATION_DTO_ACCEPTANCE.md](evidence/2026-10-06/AQ05_CLARIFICATION_DTO_ACCEPTANCE.md)：Draft/Status/Decision集中状态、回答及协作者顺序，旧JSON兼容由codec负责；Service保持事务、来源、幂等及CAS。旧合同先通过，扩展753项中752通过/1既有环境skip，追加字段顺序37/37；独立COMMENT无剩余发现。完整项目DTO/V2迁移及正式验收继续开放。

人工评审保存类型边界见 [AQ05_REVIEW_DTO_ACCEPTANCE.md](evidence/2026-10-06/AQ05_REVIEW_DTO_ACCEPTANCE.md)：类型化等级/状态及有序校验与旧 JSON codec 分离；原授权、事务、回放和不可变修订不变。旧实现41合同通过，迁移后67/67；未重写历史格式或升级发布许可。完整 DTO、V2 单写迁移、坏版本任务恢复及正式验收仍开放。

项目台账展示职责见 [AQ09_PROJECT_LEDGER_ACCEPTANCE.md](evidence/2026-10-06/AQ09_PROJECT_LEDGER_ACCEPTANCE.md)：工作台1226→1062行，required model 与事件保留父级查询/Workspace/权限生命周期；旧页面89、最终售前500项通过，类型/lint/格式通过。两主题×两视口合成浏览器呈现和交互验证通过，独立COMMENT无发现；完整架构与正式业务验收仍开放。

真实远端 CI 见 [AQ08_REMOTE_CI_ACCEPTANCE.md](evidence/2026-10-06/AQ08_REMOTE_CI_ACCEPTANCE.md)：run37376295073/source08acb836/merge7d2ed26f 的验证和Required汇总均success；远端Java6652通过/71跳过、UI1195、Node5、cost16及适用检查通过，报告与GitHub身份已下载回读。目标分支protected=false；该结果不代表强制生效、正式AC关闭或后续提交已通过。

对象状态和错误消费者修复见 [AQ05_DOMAIN_STATE_ACCEPTANCE.md](evidence/2026-10-06/AQ05_DOMAIN_STATE_ACCEPTANCE.md)：七组对象状态不再从全局翻译词表认定合法；未知不归OPEN，未答复汇总保持冻结投影口径，错误unknown经字符串校验。真实RED后售前528/528、类型和四组组件浏览器通过，独立P2修复后COMMENT/0发现。原wire和存储未变；聚合对象迁移属于AQ-06，不能用无限新增DTO wrapper替代，正式AC与本批外状态仍开放。

项目修订容量扩展实施见 [计划](evidence/2026-10-06/AQ05_PROJECT_REVISION_CAPACITY_PLAN.md) 与 [工程记录](evidence/2026-10-06/AQ05_PROJECT_REVISION_CAPACITY_ACCEPTANCE.md)：仅项目 long/BIGINT、安全整数上限和 V220 三列；保留原数值 wire、低位任务信封、历史回执及条目 int 规则。合法旧上限 RUNNING 可恢复，坏/不一致版本仍拒写；部署须停止旧 writer，出现新范围写入后不能回滚旧二进制。具体测试、方言和提交状态按工程记录/PR分别核对，不代表完整对象迁移或正式业务验收。

委派事件测试夹具修复见 [工程记录](evidence/2026-10-06/AQ01_DELEGATION_EVENT_FIXTURE_ACCEPTANCE.md)：推送门禁暴露Mockito未注入超时配置造成0秒取消，补真实成功断言及显式测试预算，RED后55/55。保留严格校验和顺序断言；生产事件回调顺序风险独立登记，不能据此宣称并发治理全部完成。

制品物化与冻结交付边界见 [AQ05_ARTIFACT_BOUNDARY_ACCEPTANCE.md](evidence/2026-10-06/AQ05_ARTIFACT_BOUNDARY_ACCEPTANCE.md)：既有Reader扩展为包内Artifacts，Service793→711行，授权/事务/回放/状态保留；公共接口及旧读算法AST相同。原实现追加刻画115/115，最终售前/投标/架构88类982项（981通过/1既有PPT环境skip）。反射测试入口失败后迁至公共draftArtifact，全部旧断言保留。长事务、真实Office/方言、其余结构与正式AC继续开放，完整commit/push及远端CI按实际精确树记录。

发布渲染短事务见 [AQ05_RENDER_TRANSACTION_ACCEPTANCE.md](evidence/2026-10-06/AQ05_RENDER_TRANSACTION_ACCEPTANCE.md)：持久化固定输入后在事务外转换，独立短事务重验并原子接纳；同键跨命令占用、租约/旧批次围栏、PPT/当前及历史来源固定、MySQL RR 当前读取均有针对性证据。隔离 MySQL 8 完整迁移至 V221、runtime43及发布生命周期14项通过；完整提交/推送以确切 tree hook 报告为准。Kingbase、生产切换、性能与正式 AC 未因此完成。

AC46 原始公共 CI 来源核验见 [工程记录](evidence/2026-10-06/AC46_ORIGINAL_CI_ACCEPTANCE.md)：确切 run37418776854 的18个原始产物、15日志摘要及合成测试来源经过独立检查；工程证据齐备，正式签收保持 NOT_RUN。

AC21 取消/重启/晚到结果原条款的工程证据见 [完整提示与恢复隔离](evidence/2026-10-06/AC21_RESTART_NOTICE_ACCEPTANCE.md)：取消无error及重启/晚到/终态失败均诚实显示未知计费，122项UI与44项H2回归通过；独立复核结合既有3JVM恢复及拒收/批准边界，转为工程证据齐备待正式签收。

历史任务固定Skill包的候选实现与专项证据见 [AC22_TASK_PACKAGE_ACCEPTANCE.md](evidence/2026-10-06/AC22_TASK_PACKAGE_ACCEPTANCE.md)：内置Skill与S6引擎保存原始执行闭包，实际工具读取验证A/B隔离；三方言新增V222，H2/MySQL已执行，Kingbase另验。

AC30 原条款工程复核已收口，见 [浏览器证据对账](evidence/2026-10-06/AC30_BROWSER_RECONCILIATION.md)。34项工程证据齐备、12项仍有缺口；正式签收不变。AQ10 已有 [H2列表有限规模测量](evidence/2026-10-06/AQ10_LISTING_MEASUREMENT.md)及 [隔离MySQL 8.0.46测量](evidence/2026-10-06/AQ10_MYSQL_LISTING_MEASUREMENT.md)：各18组路径测量，新旧JSON字节一致，保留SQL计划、时延、分配及环境限制；不代表完整性能验收。
