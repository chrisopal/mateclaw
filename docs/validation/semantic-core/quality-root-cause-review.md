# M1–M3 审查、修复与质量根因验收

日期：2026-09-08。基线：`129a48cd9982c1e0dcefe48f3008083202ac3e0d`；本轮修复留在 `codex/enterprise-semantic-core` 工作区，未推送。

## 结论与架构边界

已完成 M1–M3 的功能、架构及前端复核，并修复本轮可复现的问题。质量根因场景通过自动化测试、认证页面操作、持久化 API 回读和真实 Agent 工具调用验收。这里验证的是调查结论的证据治理，不是自动证明因果关系。

保留三层边界：`mateclaw-semantic-core` 提供无 Spring/数据库依赖的本体、事实、冲突及证据规则；`mateclaw-server` 管宿主权限、事务、存储、来源、审核和工具；`mateclaw-ui/src/features/semantic` 提供管理工作台。未搬迁整个 Semantica4j，也未增加独立服务或依赖。

知识库仍是资料及访问控制的权威；本体提供类型、属性、关系和约束；语义图持有实体与事实，并绑定精确的已发布本体修订。发布 v2 不改变已绑定 v1 的图，非空图不能直接换绑。原生 Wiki 图不作为语义可信事实的第二个写入主系统。

## 发现的问题与修复

| 问题 | 修复及验证 |
|---|---|
| Tool 仅有用户/工作区，未完整继承 Agent 的知识库范围；底层 KB 移动/删除后仍可能访问图 | 保留 Agent ID，校验启用状态、当前工作区、原生 KB 可见性；所有图入口检查当前 KB。自动化负例和真实受限 Agent 调用均验证拒绝访问。 |
| 冲突只适合事实修订，修改提案及三方冲突易出现悬空成员或绕过单值约束 | 增加 STATEMENT/CHANGE_PROPOSAL 类型，支持提案参与裁决；批准后替换其他冲突中的成员、使同基线提案过期并去重；审核和裁决统一检查有效证据与单值约束。覆盖三方冲突、过期 CAS、重放、撤回证据等反例。 |
| 审核命令重放与资源绑定不足，旧格式重放可能失败 | 将资源身份纳入命令一致性检查；保留历史无 kind 的事实冲突重放格式；拒绝跨操作类型复用和空成员。 |
| 来源撤回/排除、事实裁决缺少统一事务审计记录 | 新增治理事件及管理员读取 API，和业务决策同事务写入；通过故障注入证明审计写失败会回滚业务变化。 |
| 类型、布尔值、时间及无关字段组合校验不够严格 | 严格检查 discriminator、UNKNOWN 时间、布尔字面值、ENTITY/标量字段互斥、单位、重复证据；非法输入返回 400。 |
| 数量上限、禁用状态与导入重试边界未完整落实 | 限制 1000 实体、10000 事实身份、3 次重试；禁用图拒绝重试/新证据；计数显式返回数字、ID 保持字符串。 |
| 原文范围截取未形成完整 Unicode 合约 | 按 code point `[start,end)` 返回文本片段及总长度，验证含 emoji 的精确引用。 |
| 中文关系/目标名称无法检索；真实模型只收到 ID 无法说出根因名称 | 批量读取图内实体及固定本体标签用于检索；返回仅本次结果相关的 entityLabels/predicateLabels，保留事实、版本、证据和 trace ID。真实模型已能回答“刀具磨损”。 |
| 可信查询逐事实查证据造成重复数据库访问 | 批量计算当前修订支持状态，查询事务设置 5 秒超时；不增加缓存或新的事实权威。 |
| 空图/隐藏标签页初始化导致 ECharts 零尺寸错误 | 使用浅引用、nextTick、尺寸检测及 ResizeObserver；空图释放图表。补充节点和关系的键盘可访问入口。 |
| 工作台分页/状态、候选切换及错误后输入体验不一致 | 修正总数与分页，候选输入保留，增加未保存离开保护，切换字段清除不兼容值；中文状态、有效时间及提案证据展示一致。 |
| 短标签页垂直居中、标题弱、部分 loading 指令未导入、绑定列表只取第一页 | 顶部对齐并统一标题；补齐 loading；加载全部本体选项；抽屉适配窄屏。 |
| 正式 UI 构建引用不存在的 Snowflake 精度检查脚本 | 补齐无依赖脚本；误将数据库 ID 转数字的反例被拒绝，明确注释的计数器转换允许通过。 |

## 真实质量根因用例

使用隔离的合成验收资料：批次 `LOT-QA-0908`、设备 `CNC-07`、孔径超差 `0.08 mm`，候选根因“刀具磨损”和“夹具偏移”。不代表真实工厂调查数据。

| 步骤 | 执行与结果 |
|---|---|
| 本体与绑定 | 页面发布本体 v1、绑定验收 KB；登记 5 个实体。本体的 v2 发布及固定 v1、拒绝非空换绑在 H2/MySQL HTTP 用例覆盖。 |
| 来源与证据 | 3 份原生 Wiki 资料形成固定快照。页面选取复核及初步调查原文，形成两个根因候选；检验事实通过认证 API 准备。 |
| 候选隔离 | 初始仅 3 条已审核检验事实可信；两个相斥根因候选进入 OPEN 冲突，不进入可信查询。 |
| 管理员裁决 | 页面填写理由并保留“刀具磨损”；它成为 r2 ACCEPTED，另一候选 REJECTED，可信事实总数为 4。治理事件记录 RESOLVE_CONFLICT。 |
| 图与证据 | 图展示批次—设备、批次—质量问题、质量问题—根因；展开关系入口可查看事实修订及原文证据。 |
| Agent 取证 | 实际模型调用 semantic_search，回答“刀具磨损”，引用事实 `2097092829827596289`、r2、证据 `2097092829777264641`，同时说明有效时间 UNKNOWN。 |
| Agent 越权负例 | Agent 仅绑定验收 KB，访问另一 KB 图谱返回不可见；模型回答无法确认，无事实/证据泄露。 |
| 来源撤回 | 页面撤回“根因复核报告”：可信事实 4→3，rootCause 查询为空且标签字典为空；其他检验引用仍可读取，包括 emoji。 |
| 历史和模型失效 | 根因 r2 历史保留并派生 SUPPORT_LOST；记录 SOURCE_WITHDRAW 事件。新会话模型返回“无法确认孔径超差的根因”。 |

图谱：`2097084463260663810`；本体：`2097083062522511362`；KB：`2097083062568648706`。验收结束保留“来源已撤回”的状态，避免把演练结论误当成当前可信事实。Agent 的原有工具/KB 配置已恢复，全局 SemanticTool 恢复关闭。

## 验证结果

| 检查 | 结果 |
|---|---|
| Core 规则回归 | 25 通过：本体、事实、证据、冲突。 |
| Server `Semantic*Test` | 57 个发现，54 通过，3 个 MySQL 环境用例按条件跳过；0 失败。 |
| 真实 MySQL 单独运行 | 上述 3 个用例全部实际执行通过，包括完整质量根因场景及新建隔离库 V190→V196。 |
| 前端语义测试 | 16 文件、39 用例通过，覆盖图表、分页、候选保护、绑定加载、提案类型和有效时间。 |
| 类型、目标文件 ESLint、精度守卫 | 通过；企业与 classic 正式构建均通过。构建仍有既有大 chunk 提示。 |
| 页面 | 1280 桌面、390 窄屏、浅/深主题、证据抽屉和键盘关系入口检查。最终浏览器页面日志 0 error，1 条既有 i18n 实验编译器 warning。 |
| 最终运行物 | `/actuator/health` UP；运行 JAR 与最终打包 JAR SHA-256 相同：`88b908b9c30417dfc9ef581ed4b4b32a3e1f4f82c3a8cbf93a2a0c45a24be851`。 |

没有以仅 BUILD SUCCESS 代替数据库用例证据：最终 MySQL 报告明确为 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。

验证命令及结果摘要见 [verification.json](evidence/quality-root-cause/verification.json)。持久化回读见 [裁决后](evidence/quality-root-cause/before-withdraw.json)、[撤回后](evidence/quality-root-cause/after-withdraw.json)。真实模型回答见 [确认根因](evidence/quality-root-cause/model-accepted.json)、[权限拒绝](evidence/quality-root-cause/model-scope.json)、[撤回后](evidence/quality-root-cause/model-withdrawn.json)。这些文件仅含合成验收数据，不含认证状态或凭据。

截图：[冲突布局](evidence/quality-root-cause/02-conflict-layout.png)、[原文证据](evidence/quality-root-cause/03-root-cause-evidence.png)、[窄屏](evidence/quality-root-cause/05-mobile.png)、[深色可信图](evidence/quality-root-cause/06-dark.png)、[撤回来源](evidence/quality-root-cause/07-source-withdrawn.png)、[撤回后可信图](evidence/quality-root-cause/08-after-withdraw.png)。

## 变更文件与兼容性

后端集中在 `semantic/graph`、`query`、`security`、`source`、`statement`、`tool`、`web`，新增 `governance/SemanticGovernanceService` 和 `web/GovernanceController`。前端集中在 `features/semantic` 与中英词条；构建修复为 `scripts/check-snowflake-precision.sh`。完整文件清单见 [changed-files.txt](evidence/quality-root-cause/changed-files.txt)。

V195 增加冲突成员类型，旧记录默认 STATEMENT；V196 增加治理事件。H2/MySQL/Kingbase 三套脚本齐备，未修改 V194 及更早迁移。搜索响应增加标签字典，原 facts 字段保持不变。兼容字段 `winnerStatementId`/成员 `statementId` 在 kind=CHANGE_PROPOSAL 时代表提案 ID，调用方必须读取 kind，不能自行当成事实历史 ID。

本轮简化以复用为主：统一支持状态和治理写入路径、批量取标签/证据、移除逐条标签查询；未引入新框架或自动抽取控制层。

## 已知范围与未验证项

- Kingbase 迁移仅提供脚本，未在真实 Kingbase 上运行；不以 MySQL 通过替代它。
- 未运行整个 MateClaw 全量测试或生产部署验收；结论限于本轮语义模块及其实际集成链路。
- 可信查询默认跨全部业务时间返回当前已审核且受支持修订；不等于此刻有效。`atTime` 查询排除 UNKNOWN，并采用起点包含、终点不包含的区间。
- 现阶段沿用宿主 viewer/member/admin 权限，没有新增设计中建议的更细粒度本体能力令牌；UI 外观 profile 不改变后端授权。
- 治理事件从本轮部署后开始追加，不伪造历史审计；统一事件列表目前提供 API，页面展示现有事实历史、来源状态和裁决理由。
- 本轮不提供自动因果推理、自动抽取提交、批量本体升级迁移，也不新增可写 Agent 工具。新修复尚未提交/推送远端。
