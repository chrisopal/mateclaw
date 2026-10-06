# 全项目开发与审核检查台账

适用全部后续开发，检查项可按真实情况演进；调整规则本身需要证据和审核。此台账是评审入口，不是 PASS 清单。设计见 [IMPLEMENTATION_DESIGN](IMPLEMENTATION_DESIGN.md)，实测见 [SETUP_EVIDENCE](SETUP_EVIDENCE.md)。

最新实施状态见[整改收口表](ACCEPTANCE_PROGRESS.md)和[46项工程分类](acceptance-progress.json)。分类依据实际证据，不替代[正式签收台账](acceptance-register.json)。后续历史切片段落保留当时状态，不能据此重复立项已修复问题。

## 每次变更默认审核

1. **需求与范围**：任务/受影响用例明确，未夹带功能和用户 WIP；文档中的代理启动指令不自动扩大授权。
2. **架构**：公共层不依赖业务实现；Controller 不访问 DAL；跨域只消费公开接口；复用现有服务，重大决策记录 ADR。
3. **安全与数据**：可信 actor、Workspace、employee/source 交集、业务审批政策、不可变发布、幂等/取消/CAS 保持；不改旧迁移，三方言分别验收。
4. **前端与契约**：ID string、unknown 验证、稳定 DTO、真实 HTTP 状态、scope 固定、transform/二进制、409 保留输入、主题/i18n 不退化。
5. **质量与证据**：dev → 适用回归 → commit 精确树 → 最新合并树 CI；真实退出码、非零测试数、未测项和风险清晰，检查后修改必须重跑。
6. **发布与审核**：本地检查不能代替业务或独立审核；控制面调整说明原因和覆盖变化；无远端 required check/独立审批证据不能宣称强制生效。

## 可执行检查配置

| 检查 | 触发/命令 | 本轮接入 | 局限与整改任务 |
|---|---|---|---|
| AR-001 runtime 反向依赖 | gate.py / verify dev | 已安装 ratchet；六个宿主 main 路径已封口 | 未来新增路径需审定映射；正式签收另验 |
| AR-002 Controller DAL | 同上 | 已安装 ratchet；三个工作台 main 路径已封口 | 编译 ArchUnit 已执行；新路径需审定映射 |
| AR-003 跨 feature 内部依赖 | 同上 | 已安装 ratchet | AQ-04 后封口，动态计算导入需补 AST/审核 |
| AR-004 semantic 基础身份借用 | 同上 | 已安装 ratchet | AQ-02 |
| AR-005 跨域直接 SQL | 同上 | 已安装 ratchet | AQ-02/03/06；不能代替权限行为测试 |
| TS-001 / UI-001 | 同上 | 已安装 ratchet | AQ-05/09，any/内联双语 |
| TEST-001 | 同上 | 已安装 ratchet | 防新增 skip/only；不能证明所有断言强度 |
| STYLE-001 / STYLE-002 | 同上 | 已安装 | 长行存量 ratchet；修改行尾空白硬失败 |
| DB-001 / DB-002 | 同上 | SQL/Java 入口已实现 | 旧入口只读与新入口三方言；源码规则不等于真实 DB 验收 |
| DB-003 / DB-004 | 同上 | 冻结清单与基线项保护已实现 | 初装摘要/闭包人工核对；技术审阅不是维护人审批，远端强制仍 NOT_VERIFIED |
| Python 门禁自测 | verify 所有模式 | 已安装并执行 | 数量和日志见实测；非应用验收 |
| Java 格式 | Spotless + AOSP，commit/CI 适用时 | 根 POM 已配置 | 增量格式，不格式化原文/历史字节 |
| 前端格式 | Prettier 3.6.2 + 固定 config | package/lock 已配置 | 只检查适用变更文件；不自动 --write |
| Java 测试 | 根 reactor clean verify，解析 XML | wrapper 已配置 | 当前基线结果见实测，不忽略失败 |
| UI lint/type/tests/build | 非修复 ESLint、vue-tsc、Vitest、Node、precision、两 mode build | wrapper 已配置；Vitest 最多 2 个 worker、单测 20 秒超时 | 构建不是浏览器/人工验收；共享机器负载仍可能影响耗时 |
| 独立成本分析工具 | Python 依赖探测、unittest、Python/JS 语法 | wrapper 已配置 | 模拟样本通过不等于真实经营数据验收；缺 openpyxl 阻断 |
| ArchUnit | mateclaw-server/src/test/java/vip/mate/architecture/WorkbenchArchitectureTest.java | LOCAL_COMPILED；生产字节码 + 非空聚合 + 正反例 | AQ07_ARCHUNIT_INSTALL_ACCEPTANCE；逐包完整性、维护人批准和远端强制另验 |
| policy 封口 | .quality/policy.json | AR-001 六个宿主 main 路径、AR-002 三个工作台 main 路径零容忍 | 9 个源快照反例拒绝旧违规；未改 baseline，独立技术审核不是维护人批准 |
| pre-commit / pre-push | 当前工作区 .githooks | 已安装 worktree-local | 可绕过；其他工作区需显式独立安装 |
| 远端 CI | 所有 pull_request + merge_group | PR可信base实际runner与Required汇总已运行通过 | 最新候选仍须核验；目标分支强制保护未启用 |
| 控制面审核 | CODEOWNERS + PR 模板 + 根 AGENTS/Skill | 文件已设置 | 账号候选 @chrisopal；独立审阅人可用性待维护人核实 |
| 分支保护 | Engineering Gate Required + 过期审批失效 | NOT_CONFIGURED_BY_THIS_TASK | 不在本轮改仓库管理设置；AQ-08 实测 |

## 阶段台账

Vitest 原默认并发在共享开发机的完整提交门禁中出现 worker 启动和跨文件用例超时，失败分布覆盖未改动的售前、语义和本体测试；定向 54/54 通过。将 worker 上限设为 2、单测超时设为 20 秒后，使用门禁相同的默认 Vitest 命令复跑 703/703 通过。调整只影响测试运行调度与超时，不删除、跳过或放宽断言；完整提交门禁和远端结果仍需按候选提交重新验证。

| 任务 | 当前状态 | 下一道出口 |
|---|---|---|
| AQ-00 | LOCAL_CONFIGURED；PR可信base与真实CI已执行 | 独立维护人签收；dev保护与部署状态另验 |
| AQ-01 | 公共策略/分派/模块组合已有工程证据；本批取消修复已提交 | 取消异常边界及正式验收；不重复已完成结构拆分 |
| AQ-02 | 公共主体/来源/领域批准边界已实现；AC07缓存模型外发已修复、AC09矩阵证据齐备 | 当前候选门禁/CI及正式业务签收；不重复已闭合的实现与补证 |
| AQ-03 | Controller依赖/任务查询已有编译与行为证据 | 原条款正式签收；新回归按源码变化触发 |
| AQ-04 | scope/transform/二进制/冲突已有有限工程证据 | 正式签收；不把真实模型等无关条件追加到传输合同 |
| AQ-05 | 本轮Service/DTO/错误/制品/渲染事务边界已收口 | 历史样本、正式验收；对象持久化迁移归AQ06 |
| AQ-06 | 局部CAS/幂等/恢复/SQL投影已实现；完整V2迁移未完成 | 正式V2/Delivery规格、独立对象依赖、单写切换及回退 |
| AQ-07 | Java迁移保护、编译ArchUnit与零容忍路径已安装执行 | 独立维护人审批、远端强制及未来路径映射 |
| AQ-08 | 真实CI已通过，分支保护未启用 | required检查、审批失效/Code Owner及绕过策略实际强制与签收 |
| AQ-09 | 本轮Workbench/组件/会话/状态/i18n结构已收口 | AC30完整浏览器状态覆盖与正式QA |
| AQ-10 | DESIGNED / NOT_IMPLEMENTED | 真实数据规模、负载/索引/可观测性测量与整改；不报告未测提速 |
| AQ-11 | DEFAULT_POLICY_CONFIGURED | 所有后续变更默认执行本台账；调整仍需证据和审核 |

## AC 全量追踪

以下状态是整改验收状态，全部初始 NOT_RUN。当前基线跑过相关旧测试也不自动改变状态；必须记录确切候选 tree、环境、断言和报告。机器版本为 [acceptance-register.json](acceptance-register.json)。

| ID | 场景 | 实施任务 | 责任 / 验证方式 | 状态 |
|---|---|---|---|---|
| AC-01 | 普通聊天未带项目选项 | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-02 | 售前执行改为通用接口 | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-03 | 指定项目执行却无策略 | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-04 | 同时注册多个 Revalidator | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-05 | 模块开关组合 | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-06 | actor/employee执行前被停用 | AQ-01 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-07 | wiki_disabled/仅KB-A/项目KB-B | AQ-02 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-08 | 执行中撤权/来源撤回 | AQ-02 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-09 | viewer/member/admin/owner/system admin | AQ-02 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-10 | 投标项目owner批准 | AQ-02 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-11 | Controller迁移 | AQ-03 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-12 | 审核任务解绑后回读 | AQ-03 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-13 | Workspace快速切换 | AQ-04 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-14 | multipart/自定义transform | AQ-04 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-15 | Blob/ArrayBuffer/下载 | AQ-04 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-16 | 409并发 | AQ-04 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-17 | 同对象并发修改 | AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-18 | 无关联系人变化 | AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-19 | 实际依赖版本变化 | AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-20 | operationId相同请求/异请求 | AQ-01/AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-21 | 取消/重启/晚到结果 | AQ-01/AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-22 | 固定Skill被更新 | AQ-01/AQ-06 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-23 | 迁移前后发布成果 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-24 | 旧消费者 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-25 | 旧迁移被编辑 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-26 | 新迁移方言 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-27 | 售前单写切换 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-28 | 新写入后的回退 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-29 | 基线/客户确认 | AQ-06 | 迁移负责人 + QA + 业务审核 / 迁移/恢复/真实数据库/字节对账 | NOT_RUN |
| AC-30 | 功能两主题/布局 | AQ-09 | 前端 + QA / 真实浏览器两主题/窄屏 | NOT_RUN |
| AC-31 | 格式化源码 | AQ-09 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |
| AC-32 | 新增跨域/Controller SQL/any/内联文案 | AQ-07 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-33 | 部分暂存 | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-34 | 中文/空格路径与重命名 | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-35 | 缺基线/缺工具/命令失败/超时 | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-36 | 零测试/全跳过/缺结果文件 | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-37 | 检查后代码/index变化 | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-38 | 已有Hooks/Husky/多worktree | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-39 | 新增Guard规则/修改policy | AQ-00 | 工程门禁维护人 + 独立审核 / 门禁正反例 + 真实仓库流程 | NOT_RUN |
| AC-40 | 本地绕过pre-commit | AQ-08 | 仓库维护人 + 独立审核 / 远端临时违规PR/管理设置回读 | NOT_RUN |
| AC-41 | workflow job skipped/cancelled | AQ-08 | 仓库维护人 + 独立审核 / 远端临时违规PR/管理设置回读 | NOT_RUN |
| AC-42 | base变更/PR更新 | AQ-08 | 仓库维护人 + 独立审核 / 远端临时违规PR/管理设置回读 | NOT_RUN |
| AC-43 | 直接push/强推dev/机器人绕过 | AQ-08 | 仓库维护人 + 独立审核 / 远端临时违规PR/管理设置回读 | NOT_RUN |
| AC-44 | 改workflow/CODEOWNERS/测试配置削弱门禁 | AQ-08 | 仓库维护人 + 独立审核 / 远端临时违规PR/管理设置回读 | NOT_RUN |
| AC-45 | 真实长文/模型质量 | AQ-08/AQ-11 | 业务负责人 + QA / 授权真实样本/真实模型/人工质量审核 | NOT_RUN |
| AC-46 | 日志与测试环境 | AQ-08/AQ-11 | 领域开发 + QA / 行为/集成回归 | NOT_RUN |

## Java 迁移与 SQL 投影的默认审核补充

每次涉及此边界，核对 Java 入口及全部冻结闭包，不只看 SQL 文件；比较实际 checksum 与历史版本，检查 Flyway 所有方言的发现和 validate 配置。固定 V1 算法不能为新业务需求直接修改，未来语义须新版本/迁移；编译器、JDK、Jackson 变化也需兼容证据。核对所有正文 writer 同事务维护派生版本、故障顺序、Workspace/CAS及失败回滚；旧 writer 混跑和新增写入后直接回退未获验证时不能部署切换。

AQ06 当时仅澄清 R-06，未修改 gate；历史反例/真实 MySQL 与 H2 证据见 [AQ06_LISTING_PROJECTION_ACCEPTANCE](evidence/2026-10-03/AQ06_LISTING_PROJECTION_ACCEPTANCE.md)。后续 AQ07 已补 Java/冻结源码检查，原因、覆盖差异、正反例及独立技术审阅见 [AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE](evidence/2026-10-03/AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md)。无 baseline/policy 放宽或豁免。真实 Kingbase、生产回退、版本化请求 hash/历史回执政策、闭包人工审核及独立维护人批准仍待完成。

Java 迁移审核须核对清单新增项与已发布 HEAD 源码摘要、全部直接/间接算法依赖及嵌套类型；不得只核对入口文件。清单移除、改摘要、同时修改历史源码应分别触发失败。验证使用 trusted base runner 的实际版本；未合入安装或未启用 required CI 不能宣称远端强制。AC-39/46 的正式状态不从门禁自测或 Agent 技术审阅自动升级。

## 检查规则如何调整

在相关变更/PR 中写清：现象与最小反例 → 调整理由及影响范围 → 旧规则与新规则覆盖差异 → 正例/负例/绕过场景 → 命令和报告 → 同步文档与审阅人。重大授权/迁移/执行边界调整另写 ADR。整改后强化 zero_tolerance；不允许删除存量债务、静默扩排除目录或重建 baseline。

一般文档/样式可由固定影响映射判不适用，未覆盖的新组件先补适配。控制面变更保持独立可审阅 diff，实施 Agent 不能自签检查削弱。审核通过前，新的规则不能被当成免除同一 PR 失败的依据；CI 继续使用 base-owned runner。
