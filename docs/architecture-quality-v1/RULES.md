# Code Agent 必须遵守的工程规则

适用于本体管理、售前、投标、Delivery 及为其修改的宿主代码。仓库既有更具体规则继续有效；冲突应在修改前报告，不自行覆盖既有 AGENTS、权限或源码。

## 1. 每次任务的固定流程

| 时点 | 必须动作 | 不允许的替代 |
|---|---|---|
| 开始前 | 读根目录及目标路径 AGENTS；读本规格与相关 Skill；记录 HEAD、基线、dirty 状态；执行 dev 检查并区分已有问题 | 仅凭历史对话假定仓库未变化 |
| 设计前 | 列出修改对象、公共能力复用点、依赖方向、API/数据/权限兼容风险、拟执行的回归 | 先生成全部页面再补边界 |
| 开发中 | 小步修改；每个职责边界完成后执行 dev；新增行为或修 bug 同时写断言；独立格式提交 | 用 mock 计数或演示数据代替真实实现 |
| 提交前 | 检查 diff；暂存本次获授权的文件；执行 commit 全检查；确认树标识和检查结束后代码未变 | `git add .` 收进无关用户文件；测试工作区却提交另一份 index |
| 推送前 | pre-push 检查实际完整推送范围；必要时先获取远端基线 | 只比 HEAD~1 隐藏此前提交的问题 |
| 提交评审 | 提供任务 ID、变更清单、真实命令、状态、日志摘要、未验证项、风险和回退方式 | 将 SCAN_PASS/NOT_RUN/BLOCKED 表述为通过 |

命令从仓库根运行：

```bash
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
# 完成实现和检查 diff 后，仅暂存预期文件：
git add -- path/to/changed-file path/to/corresponding-test
python3 -B scripts/quality/verify.py --mode commit
# 只有该命令 exit 0 且 report.submission_ready=true 才可执行已获授权的 commit。
```

`origin/dev` 必须实际存在；执行前由操作者按现有流程 fetch。脚本不联网更新基线，不把缺失分支当空仓。工作树还有未暂存修改/未跟踪输入时，完整检查明确拒绝；不要自动 stash、reset、checkout、清除他人改动。

## 2. 架构硬规则

**R-01 依赖方向**：通用 Agent/工具/权限/共享层不依赖具体工作台；策略通过既有公共接口注册。兄弟域只使用公开端口，不能借 helper 依赖本体内部 API。

**R-02 数据访问**：Controller 不出现 JdbcTemplate、Repository/Mapper 注入或领域授权遍历。应用服务决定事务与用例；DAO 执行 SQL。适配器不能作为绕过服务权限的“合法通道”。

**R-03 主体与范围**：Workspace Header、projectId、actorId 都不是授权凭据。后台执行必须复核真实 actor、员工、来源和活动 attempt；UI、Context、工具、缓存、历史读取、导出不能分用不同权限逻辑。

**R-04 审批政策**：统一基础机制，保留已确认的领域批准规则。AI、项目 owner、内部基线、客户确认不能互相升级。不在重构时悄悄放宽角色。

**R-05 版本与幂等**：精确输入、独立对象修订、同键同请求回放、同键异请求拒绝。已发布字节不重新渲染。取消和过期结果不覆盖新业务对象。

**R-06 数据迁移**：旧 Flyway 只读；新增迁移覆盖支持的方言。备份/恢复/影子比对/单写切换必须有证据。删除、重建、回滚生产数据必须另行授权。 Java Flyway 入口及其冻结算法/工厂闭包同属只读约束；审阅必须核对入口发现、实际 checksum、JDK/Jackson 兼容及各方言 validate 配置。当前 DB-001/DB-002 只识别 SQL，不得把扫描通过写成 Java 迁移不可变已强制生效；补强检查走独立控制面审核。

**R-07 类型与 UI**：数据库 ID 保持 string；稳定 DTO 不新增 any；模型输入 unknown 经验证；i18n/主题复用宿主；严禁带演示数据假装加载成功。

**R-08 检查过程只读**：check 命令不得 `--fix`、修改测试期望、自动更新 snapshot、自动改 baseline 或格式源码。format 操作是显式开发动作，其修改必须进入 diff。

**R-09 真实失败**：缺依赖/基线、命令超时、零测试、全跳过、编译失败、丢失报告都不能返回“检查通过”。原有问题必须报告，不可通过捕获异常填充空成功。

**R-10 受控变更**：`.quality/`、`scripts/quality/`、`.githooks/`、`.github/`、测试配置、formatter、POM/lockfile、AGENTS/Skill 属于控制面。修改时必须单独说明，并由独立维护人审阅；代码 Agent 不能自我批准例外。

## 3. 绝对禁止的“为了通过”操作

禁止 `git commit --no-verify`、`git -c core.hooksPath=/dev/null commit`、跳过钩子环境变量、`|| true` 掩盖检查失败、`continue-on-error` 包装 required job、新增 test.skip/only 或 @Disabled、`-DskipTests/-Dmaven.test.skip` 用于验收、删除回归或随意降低阈值。

禁止在同一个功能 PR 中清空 zero_tolerance、调整 CI paths 令任务不触发、改报告解析逻辑令缺报告为成功，或以“临时”名义删除权限/摘要/幂等断言。

规则不表示技术上不可能绕过本地文件；最终约束由仓库权限、required CI、独立评审和受保护控制面共同提供。

## 4. 例外与存量

初始词法检查以固定 Git base 中同一路径/同一规范化代码指纹的既有问题为存量，不提供“重新生成基线”命令。新增重复出现会失败；改名搬走旧问题不会洗白。修复完成后将路径加入 zero_tolerance，使其不可回退。

本工具没有开发者可自行写的 waiver 开关。确需调整规则误报或业务边界，先提供最小反例/理由/责任人/期限/ADR，独立审阅控制面 PR。绝不能以该流程豁免跨工作区读取、旧迁移篡改、未批准发布或模型自批。

## 5. 任务回报模板

```text
Task: AQ-xx / 业务任务号
Base / HEAD / checked tree:
Changed responsibilities and public contracts:
Behavior preserved:
Checks: command → PASS/FAIL/BLOCKED/NOT_APPLICABLE/NOT_RUN → log/report path
Tests actually executed:
Database / browser / live model / Office evidence:
Remaining issues and migration risk:
No unrelated user modifications included:
```

不要把自测结论写成客户业务验收；不要把 read-only 静态扫描写成架构完全合规。
