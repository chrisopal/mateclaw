# AQ-01/AQ-06：重启恢复的CAS冲突处理

修复恢复扫描忽略CAS返回0的问题。在扫描后发生并发编辑时，旧实现只尝试一次，旧RUNNING任务可能一直不进入终态。现有协调器按项目最多尝试3次；每次失败后重读最新行并重新计算任务资格、版本和列表投影。只把仍旧过期的RUNNING任务标记为INTERRUPTED_BY_RESTART，不复用失败尝试的已修改JSON。连续冲突达到上限时明确记录警告并继续下个项目。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，初始dev `m9i1g1tq` 为SCAN_PASS/submission_ready=false。改动限定于 `PresalesGenerationCoordinator` 恢复循环和 `PresalesTerminalReceptionTest` 新增7项合同；复用已有仓储的读取/CAS方法，无新依赖、表、调度器或迁移。职责与边界见 [计划](AQ01_RECOVERY_CAS_PLAN.md)。

## 证据

测试使用真实H2及真实仓储SQL。repository spy仅在CAS之前注入确定的并发写入顺序，不模拟CAS成功：

- 无关编辑引起首个CAS冲突后重读，恢复成功且保留编辑、其他项目集合和列表投影。
- 并发改成CANCELLED/SUCCEEDED、换成当前进程的新运行，均不再写入。
- 并发删除后不重建项目；所有测试均检查另一个Workspace同ID项目原字节与版本不变。
- 两次冲突后第三次恢复成功；连续三次冲突后停止，保留最新编辑并继续恢复健康项目，不调用模型、用户服务或来源服务。

原实现RED：39项中3失败，首个冲突只尝试一次（期望3实际1）以及恢复后版本仍为3而非4，0错误。修复后定向118/118；扩大50类667项，666通过、0失败、0错误、1既有skip。该skip是 `PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes`，缺少环境变量 `PRESALES_PPT_SKILL_ROOT`。实际命令：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesTerminalReceptionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesTerminalReceptionTest,PresalesGenerationCoordinatorTest,PresalesExecutionVersionContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

`recovery-cas-tests/`归档RED日志/XML/生产及测试源码、GREEN两轮日志/XML、格式检查、results.json.gz源码摘要及最终归档清单。Spotless通过。dev `04w3by6m` 为SCAN_PASS/submission_ready=false；architecture-ratchet和guard-self-tests通过，application-toolchains为NOT_RUN。受检worktree identity为 `851d3418768830cd9a75884df6b2e4f8dd9a85004b12afedcc42e814070ec069`（本段及报告归档前），报告保存在dev-report.json.gz；归档完成后重跑最终dev。[独立审阅](AQ01_RECOVERY_CAS_REVIEW.md)为COMMENT，无可操作发现；缺少LSP/AST工具，未声明对应扫描通过。不替代正式业务签收或完整commit/远端CI。

## 限制与回退

该证据是确定交错的真实H2 SQL测试，事件方法直接调用；未执行真实双线程数据库锁竞争、完整进程重启、多实例、MySQL/Kingbase或真实客户历史数据。单实例假设保持。持续3次竞争后仍可能遗留旧RUNNING，当前行为明确记录冲突耗尽；版本耗尽/非法记录仍拒绝写入，需受控修复，不以重置版本或强制覆盖解决。未验证或承诺模型计费状态。

AC-17/21只补本边界证据，46项正式AC仍NOT_RUN。无提交/推送/部署或数据运维操作。回退本片不会更改数据库，但会重新引入已复现的CAS失败后不恢复问题。
