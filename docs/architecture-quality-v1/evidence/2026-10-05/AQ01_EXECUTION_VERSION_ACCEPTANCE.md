# AQ-01/AQ-06：售前执行快照的精确版本边界

本批修复执行链上 `asInt()` 的截断与默认值匹配：小数/超范围/非法版本不能生成可信快照或执行选项，也不能通过工具前重验、进入队列、预留取消或用于终态 CAS。基于 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93` 的累计工作树，初始 dev `25eyu33a` 为 SCAN_PASS/submission_ready=false。原始范围及行为约束见 [实施计划](AQ01_EXECUTION_VERSION_PLAN.md)。

## 实现与兼容

五个现有类复用 `PresalesProjectItems.positiveRevision/matchesRevision`，无新解析器、依赖、Bean、表或迁移：

- `PresalesContextProvider`：授权后准确读取正版本再构建快照；重验双方必须是有效且相同的版本，两个坏值不能经默认值相等而通过。
- `PresalesEmployeeRuntime`：构造公共执行选项前拒绝非法快照版本，保留 `TASK_SCOPE_CHANGED` 拒绝边界。
- `PresalesExecutionRevalidationProvider`：持久项目及任务快照均须精确等于服务器生成的 scope 版本；拒绝发生在模型 pin/来源读取前。
- `PresalesGenerationService`：持久化返回值和原快照均须精确等于已计算 acceptedVersion；直接传递该整数入队。取消在预留/持久化前准确解析版本。
- `PresalesGenerationCoordinator`：终态仅使用有效的当前版本作 CAS。非法版本进入现有 VERSION_CONFLICT 重试和失败恢复，不把截断数字写回仓储。

合法正整数、历史整数字符串（包括空白包围）继续支持。小数数值即使是 `2.0` 也不再被当作整数；超范围、零/负值、null、容器、布尔及非法文本均拒绝。operation 回放优先、权限调用顺序、来源检查和现有事务入口保持。若原始记录已损坏且回退存储也无法安全写入，记录仍需受控修复；本批不会自动改写这些数据。

## 验证证据

新增 `PresalesExecutionVersionContractTest` 75 项。原实现 RED 为 58 failure / 0 error；失败覆盖 12 种非法值的六处边界，3 项合法版本兼容控制通过。RED 后将工具边界的模型 pin mock 补齐以使缺陷失败更直接，未删除任何断言。修复后新合同 75/75 通过。

```sh
# 首轮定向：11类，174/174，通过
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesExecutionVersionContractTest,PresalesExecutionRevalidationProviderTest,PresalesEmployeeRuntimeTest,PresalesContextProviderTest,PresalesSourceScopeTest,PresalesGenerationControllerTest,PresalesGenerationCoordinatorTest,PresalesAtomicResultAcceptanceTest,PresalesRuntimeTransactionIntegrationTest,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test

# 扩大回归：50类660项，659通过、0失败、0错误、1既有条件skip
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test

JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

既有 skip 为 `PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes`，因为未配置 `PRESALES_PPT_SKILL_ROOT`。生产 ArchUnit 的 runtime/shared host、pure semantic、workbench Controller 聚合均非空；导入库存 2882，分别匹配 877/122/3，不代表完整运行时类覆盖。

`execution-version-tests/` 保存原实现5份源码、RED日志/XML、两轮GREEN日志/XML、格式日志及 `results.json.gz`（实际类/用例数、skip原因、当前源码SHA-256）；日志中的敏感字段按既有归档机制脱敏。dev gate `gz8gs7x5` 为 SCAN_PASS，submission_ready=false；architecture-ratchet和guard-self-tests通过，application-toolchains为NOT_RUN。受检worktree identity为 `cc847a14a5dc5fdf5d7c0c5eb2f33d40fa386707e1edd2c3be7aa307b8fd6e40`（归档报告及本段元数据写入前的快照），报告已归档为 `dev-report.json.gz`；最终证据归档后再次执行dev。SCAN_PASS不替代commit/ci。

## 未关闭事项与回退

本片是 H2/模拟外部依赖的工程证据；不声称真实模型、MySQL/Kingbase、损坏历史记录修复、浏览器、独立QA或远端required CI验收通过。AC-21只补充“取消/晚到结果的版本边界”证据，不能关闭重启/计费等整个场景；46项正式AC状态保持NOT_RUN。[独立代码审阅](AQ01_EXECUTION_VERSION_REVIEW.md)为COMMENT、0个可操作问题；缺少jdtls/ast-grep，未声明LSP或AST扫描通过。本记录不是维护人合并批准。回退五处修复会重新暴露截断匹配问题，不能把已拒绝的坏版本重新纳入合法输入。
