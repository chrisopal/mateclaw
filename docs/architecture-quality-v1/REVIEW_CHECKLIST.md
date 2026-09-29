# 全项目开发与审核检查台账

适用全部后续开发，检查项可按真实情况演进；调整规则本身需要证据和审核。此台账是评审入口，不是 PASS 清单。设计见 [IMPLEMENTATION_DESIGN](IMPLEMENTATION_DESIGN.md)，实测见 [SETUP_EVIDENCE](SETUP_EVIDENCE.md)。

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
| AR-001 runtime 反向依赖 | gate.py / verify dev | 已安装 ratchet | AQ-01 后目标路径零容忍 |
| AR-002 Controller DAL | 同上 | 已安装 ratchet | AQ-03/07 字节码补充 |
| AR-003 跨 feature 内部依赖 | 同上 | 已安装 ratchet | AQ-04 后封口，动态计算导入需补 AST/审核 |
| AR-004 semantic 基础身份借用 | 同上 | 已安装 ratchet | AQ-02 |
| AR-005 跨域直接 SQL | 同上 | 已安装 ratchet | AQ-02/03/06；不能代替权限行为测试 |
| TS-001 / UI-001 | 同上 | 已安装 ratchet | AQ-05/09，any/内联双语 |
| TEST-001 | 同上 | 已安装 ratchet | 防新增 skip/only；不能证明所有断言强度 |
| STYLE-001 / STYLE-002 | 同上 | 已安装 | 长行存量 ratchet；修改行尾空白硬失败 |
| DB-001 / DB-002 | 同上 | 已安装 | 旧迁移只读，新迁移三方言；不等于真实 DB 验证 |
| Python 门禁自测 | verify 所有模式 | 已安装并执行 | 数量和日志见实测；非应用验收 |
| Java 格式 | Spotless + AOSP，commit/CI 适用时 | 根 POM 已配置 | 增量格式，不格式化原文/历史字节 |
| 前端格式 | Prettier 3.6.2 + 固定 config | package/lock 已配置 | 只检查适用变更文件；不自动 --write |
| Java 测试 | 根 reactor clean verify，解析 XML | wrapper 已配置 | 当前基线结果见实测，不忽略失败 |
| UI lint/type/tests/build | 非修复 ESLint、vue-tsc、Vitest、Node、precision、两 mode build | wrapper 已配置 | 构建不是浏览器/人工验收 |
| ArchUnit | integration/WorkbenchArchitectureTest.java | 仅模板，NOT_INSTALLED | AQ-01/03 清零后接入 AQ-07；不能冻结新增违规 |
| pre-commit / pre-push | 当前工作区 .githooks | 已安装 worktree-local | 可绕过；其他工作区需显式独立安装 |
| 远端 CI | 所有 pull_request + merge_group | workflow 已写入 | 首次 trusted base bootstrap 未完成 |
| 控制面审核 | CODEOWNERS + PR 模板 + 根 AGENTS/Skill | 文件已设置 | 账号候选 @chrisopal；独立审阅人可用性待维护人核实 |
| 分支保护 | Engineering Gate Required + 过期审批失效 | NOT_CONFIGURED_BY_THIS_TASK | 不在本轮改仓库管理设置；AQ-08 实测 |

## 阶段台账

| 任务 | 当前状态 | 下一道出口 |
|---|---|---|
| AQ-00 | LOCAL_CONFIGURED；工具链结果见实测；非 P0 签收 | 解决真实基线失败、独立 bootstrap 审核与提交检查 |
| AQ-01–06 | DESIGNED / NOT_IMPLEMENTED | 按实施设计逐切片行为刻画与改造 |
| AQ-07 | DESIGNED / NOT_IMPLEMENTED | 清零后 ArchUnit + policy 封口 |
| AQ-08 | DESIGNED / NOT_RUN | 全 P0 验收及远端强制回读 |
| AQ-09–10 | DESIGNED / NOT_IMPLEMENTED | P0 之后格式/UI/性能整改 |
| AQ-11 | DEFAULT_POLICY_CONFIGURED | 所有后续变更默认执行本台账 |

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

## 检查规则如何调整

在相关变更/PR 中写清：现象与最小反例 → 调整理由及影响范围 → 旧规则与新规则覆盖差异 → 正例/负例/绕过场景 → 命令和报告 → 同步文档与审阅人。重大授权/迁移/执行边界调整另写 ADR。整改后强化 zero_tolerance；不允许删除存量债务、静默扩排除目录或重建 baseline。

一般文档/样式可由固定影响映射判不适用，未覆盖的新组件先补适配。控制面变更保持独立可审阅 diff，实施 Agent 不能自签检查削弱。审核通过前，新的规则不能被当成免除同一 PR 失败的依据；CI 继续使用 base-owned runner。
