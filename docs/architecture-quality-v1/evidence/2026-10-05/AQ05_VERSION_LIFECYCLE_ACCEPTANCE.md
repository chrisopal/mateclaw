# AQ05 受检版本递增工程验证

本批修复完成，未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`、HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`。最终WIP身份见真实final-gate报告；保留其他工作。

## 修改文件与行为

- `PresalesProjectItems`复用精确正整数读取增加nextRevision，非法当前版本409 VERSION_CONFLICT，int上限409 VERSION_EXHAUSTED。在删除可变旧项/设置不可变previousId之前检查，正常最大前一版本仍能递增到MAX。
- `PresalesService`使用精确项目版本CAS检查；保持授权、来源、回放、CAS、归档及payload形状前置，业务副作用前计算一次nextVersion并用于JSON和物理列，删除两处重复未受检算术。
- `PresalesGenerationController`保持回放优先；精确CAS后、员工/context/pin调用前检查启动与收尾的两次递增能力。snapshot使用已检查的acceptedVersion，MAX-2仍可接受并重试，MAX-1/MAX拒绝新执行。
- `PresalesGenerationCoordinator`的失败兜底及启动恢复在直接写入前复核JSON版本与物理列精确一致，并使用受检递增；失败沿用日志路径，恢复继续处理其他项目。
- 对应四个测试文件 `PresalesProjectItemsTest`、`PresalesVersionInputContractTest`、`PresalesGenerationControllerTest`、`PresalesTerminalReceptionTest`净增加30个执行案例，无删减既有断言。

没有新增bean/依赖/数据表/Flyway/全局JSON/hash或权限来源变更。合法wire、同操作回放、CAS和取消规则保持；旧坏版本不再取整/回绕/静默修复。新的容量拒绝在后续命令特定业务校验之前，所以耗尽项目可能优先返回VERSION_EXHAUSTED；不能称所有异常输入的旧错误优先级完全不变。创建项目的版本0→1契约保持。

## 实际验证

所有Maven使用JDK21，每次后续Maven前按实际Running类归档XML，未混入历史报告。

```sh
# 原实现：4类94项，22失败、0错误
mvn -B -pl mateclaw-server -am '-Dtest=PresalesProjectItemsTest,PresalesVersionInputContractTest,PresalesGenerationControllerTest,PresalesTerminalReceptionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 独立审阅补充SQL/JSON一致性：原实现2项，2失败
mvn -B -pl mateclaw-server -am '-Dtest=PresalesTerminalReceptionTest#directWritersRejectBodyVersionMismatchWithoutRepairingIt' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 最终：51类544项，543通过、0失败、0错误、1既有PPT skip
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

RED包括真实HTTP返回溢出版本，以及实际H2终态兜底/恢复改写body的失败断言；各失败断言之后未执行的检查不算已验证。追加一致性RED两分支首个别名样本失败，未声称旧实现已遍历完整循环。

最终四类全部通过：Items14、VersionInput47、GenerationController3、TerminalReception32。HTTP/H2验证最后一次合法写入及原回放、进一步写入拒绝、坏JSON版本不能别名匹配、可变/不可变条目失败时project/revision/receipt不变；生成耗尽前不调用执行依赖。Items纯测试验证失败时原项和输入对象均不变。终态H2测试覆盖非法列版本、不一致/坏JSON、最后一次可写、两种直接writer、同ID跨Workspace不变，以及恢复跳过异常项目继续更新健康项目。生成排队测试为直接Controller加mock，未运行真实模型。

首轮全回归544项为0失败/1错误/1skip：新循环在第二次when重设桩时执行旧throwing Answer，错误栈位于测试helper。仅改用等价doAnswer，不执行旧stub；保留Answer、四种循环数据和全部断言。after-first日志/XML、before-stub-fix-test与最终源码均归档。生产代码未因该夹具错误改变。

独立原生审阅 `/root/artifact_read_review` 对计划、四源差异、追加一致性边界、夹具修正及最终51份XML给出COMMENT，无实质发现；非维护人审批。编译与正式base Spotless通过。初始dev `_k_maud1`，最终任务ID/checked tree/结果见归档真实报告。Java LSP NOT_RUN；dev SCAN_PASS只代表增量扫描与门禁自测，完整commit门禁仍NOT_RUN。

## 运行限制、剩余与回退

两次容量检查不是并发配额预留。已有生成过程中其他写入仍可能耗尽剩余空间；耗尽/不一致的持久化RUNNING任务保留原状并记录无法写入，不能伪造FAILED或绕过版本。此类记录需要后续受控版本/数据迁移后恢复，本批没有实现长期扩容或自动修复，不声称完整任务终态恢复要求在耗尽数据上已经完成。

本批MySQL/Kingbase、真实旧数据/进程重启、完整应用/浏览器/真实模型/客户验收NOT_RUN；恢复测试直接调用事件处理器。完整DTO/用例拆分、单写迁移/恢复、维护人批准、远端required CI和46项正式AC仍未完成。没有提交、推送、部署或仓库管理操作。

回退恢复四源原算术，无数据库回退；会重新引入已复现溢出及不一致覆盖，不能视为安全的运行修复方案。

证据：[计划](AQ05_VERSION_LIFECYCLE_PLAN.md)、[机器结果与源码摘要](version-lifecycle-results.json)、[最终门禁](version-lifecycle-tests/final-gate.report.json.gz)、[归档清单](version-lifecycle-tests/final-manifest.json.gz)。
