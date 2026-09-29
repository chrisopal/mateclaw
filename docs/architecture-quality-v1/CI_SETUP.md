# 本地钩子与远端强制门禁的启用

## 1. 当前观察，不是已启用结果

本次 GitHub 分支响应中，`dev` 的 `protected=false`，规则集接口返回空数组；读取固定提交的 `.github/workflows` 返回404。该结果仅描述本次可见范围，不代表用户其他仓库或外部CI没有政策。[R1,R9]

本包没有修改GitHub设置。必须由仓库维护人完成下面的一次性启用，才能把“建议检查”变成受保护分支的合入条件。

## 2. Bootstrap：第一次如何装上门禁

1. 在独立分支/工作树检查现有AGENTS、CI、Hook管理器和数据库配置。先运行安装器dry-run；有同名文件时人工合并，不能覆盖。
2. 审查脚本、规则、自测、依赖版本、POM与package/lockfile改动；实际运行Python自测、formatter、Maven和前端套件。新增门禁文件应和业务实现分开。
3. 首次安装时base分支还没有可信runner，因此随包workflow会明确失败 `Require bootstrap already installed on base`，**不会自动退回执行PR自己的检查器并称可信**。由维护人和独立审阅者审核bootstrap，通过受控首次安装流程将检查器合入base；保留人工核验记录。
4. 随后的普通PR从已安装base读取runner。做一个“故意引入违规”的临时PR，确认required job失败；修复后确认成功、上传证据可读。该测试PR不得带真实客户资料。
5. 最后启用required check/强制评审/禁止绕过，记录截图或API只读回读。不要在可信runner尚不存在时宣称闭环已经生效，也不要留永久bootstrap绕过开关。

Bootstrap只能由维护人处理；代码Agent无权自动合并、改管理员政策或创建绕过令牌。单人开发者不能自己批准自己的PR；严格双人政策需要另一位具备权限的人。若组织采用不同评审政策，应由负责人明确批准，不假称独立审查。

## 3. 本地Hook安装

```bash
python3 -B scripts/quality/install_hooks.py          # dry-run
python3 -B scripts/quality/install_hooks.py --apply  # 明确执行
```

安装器不设置全局Git config；已有非本包core.hooksPath、任意活动默认hook、Husky等会阻断，要求维护人整合调用链。不要直接覆盖已有 pre-commit 或其它生命周期hook。

多worktree默认拒绝可能影响其他worktree的共享设置。只有仓库已经启用worktreeConfig时，才使用worktree-local配置。安装器不会擅自开启Git仓库扩展。手工整合已有管理器时，必须保证原hook和本门禁任一非零都会阻断，并测试真实git commit流程。

Hook文件需executable bit。Windows采用已有Git Bash/WSL工作流并提供python3命令；仅能调用python的环境应由维护人设置受检包装器，不将命令缺失吞掉。

## 4. GitHub分支保护/规则集

对 `dev` 及实际发行主干分别配置，不用删除已有规则来替代：

- 所有变更通过PR；禁止直接push和force push；保护删除。
- Required status check使用**唯一job名 `Engineering Gate Required`**，核对实际界面中的check上下文及GitHub Actions来源。
- 要求分支与base保持最新或使用合并队列。本workflow包含merge_group，验证队列生成的合并结果。
- 要求Code Owner审查；审查范围至少包括 `.github/`、CODEOWNERS自身、scripts/quality、policy、hooks、AGENTS/Skill、formatter、测试配置、POM和lockfile。
- 新push使过期审批失效，或启用“最新一次push须由不同人员批准”；确保实现者不能自批。
- 不给Code Agent token、bot、维护脚本绕过名单；组织允许的break-glass需独立审计，不作为正常开发路径。
- 对管理员/可绕过角色也适用限制（按平台实际权限选项配置）；GitHub拥有者能改政策这一管理事实不能被仓库内脚本消除。[E2,E3]

`.github/CODEOWNERS`内容使用integration片段人工合并。最后匹配规则优先，必须防止后续宽泛规则覆盖敏感路径。`@chrisopal`是本仓库已知维护人候选；代码所有者与独立审批可用性需要实际确认，不虚构团队账号。

## 5. CI可信边界

workflow执行PR合并树；另外checkout base SHA。从base目录运行verify.py，policy和固定命令来自base，检查目标是candidate。缺base、缺可信runner、缺报告、失败或取消均非success。

汇总job使用 `if: always()`，只接受verification job真实success；不能因为前面的job skipped/neutral而绿灯。workflow层不设置paths过滤使required job消失。仅文档变更仍跑静态/自测并记录工具链NOT_APPLICABLE。

workflow不使用pull_request_target执行不受信代码，不给写令牌、生产密钥或生产数据库访问。checkout `persist-credentials=false`；actions已固定commit。依赖和build本身仍是不受信代码，因此使用隔离托管runner，不复用有客户内网权限的机器。

本设计不能单独抵抗有权修改workflow或管理员设置的人。必须同时保护workflow文件、required check名称/来源和审阅。更强组织级required workflow可后续按账户能力建立，当前不声称已具备或已经配置。

## 6. 依赖和性能

workflow内工具版本是可重现提案，不表示最新安全版本。AQ-00复核并按组织允许版本统一锁定；升级应单独PR并重跑自测/构建。不要每次CI下载 `latest`，也不缓存跨不可信PR的凭证或执行产物。

当前wrapper以安全为先：后端变更触发根reactor和前端兼容检查，UI变更触发完整UI套件。运行成本过高时可增加经回归验证的影响矩阵，但只能缩小无关任务，不能漏掉共享代码的下游消费者。首次优化范围规则也属于控制面审查。

## 7. 强对抗边界

base checkout策略防止PR通过直接修改检查器文件/配置来改变同一次检查标准；它不等于同一runner内任意恶意构建脚本的文件系统隔离。需要强对抗时，应把可信控制面以只读卷挂载到独立容器，构建过程在无凭证的另一执行环境运行，最终校验由组织管理的required workflow承担。普通开发门禁与恶意代码隔离是两类控制，不互相冒充。
