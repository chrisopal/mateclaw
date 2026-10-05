# AQ05 精确修订比较工程验证

本批缺陷修复完成，未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`、HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`；最终 WIP 身份见 final-gate.report.json.gz，旧工作保留。

## 修改与边界

- `PresalesProjectItems`：在既有项目项修订边界复用精确正整数读取/匹配，非法输入返回无效结果，不把失败转换为可匹配的 0，不新建版本框架/bean。
- `PresalesSolutionPolicy`：方案的可选 baselineVersion 与实际基线精确比较；当前基线版本本身必须有效，捕获时不会截断成另一版本。
- `PresalesService`：基线批准的 statementRevision 匹配、requirementVersion 捕获，以及发布 gate 的需求版本/语义修订匹配使用同一规则。公开错误保持 SEMANTIC_REVIEW_REQUIRED/BASELINE_STALE，逐引用版本→绑定图→ontology→可信事实→证据的校验顺序保持。
- `PresalesSolutionPolicyContractTest` 增加14项真实 HTTP/H2合同；`PresalesReleaseSnapshotContractTest` 增加39项实际 Service gate 合同（外部依赖为mock），既有断言保留。

只接受正 int 范围的整数修订；历史文本允许 Integer.parseInt 精确解析的十进制整数（trim、加号、前导零），拒绝小数/指数文本、浮点节点、容器、bool、missing/null及溢出。合法 HTTP payload 仍遵循既有形状规则，例如 baselineVersion 不因此允许字符串。SAVE_REQUIREMENT 的未知文本仍可保存草稿，进入信任提升时必须匹配可信修订。

这是明确的异常数据行为收紧：`4294967297` 不再等于修订1；无效当前/冻结修订也不能因默认0相等获得发布许可。历史坏引用需重新绑定真实修订，本批不批量修改旧记录。没有 schema/Flyway、全局 JSON、hash、权限、来源端口、事务、写入顺序或已发布只读字节变化。模型结果最终方案写入同样经过 SolutionPolicy；模型初筛不替代此信任边界。

## 实际验证

所有 Maven 使用 JDK21。按日志实际 Running 类匹配当次 Surefire XML归档；每次后续 Maven 前已保存前次报告。

```sh
# 原实现 RED：4类101项，37失败、0错误
mvn -B -pl mateclaw-server -am '-Dtest=PresalesSolutionPolicyContractTest,PresalesReleaseSnapshotContractTest,PresalesCommandPayloadTest,PresalesProjectItemsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 独立审阅补充的历史文本正例，在原生产实现先通过：1/1
mvn -B -pl mateclaw-server -am '-Dtest=PresalesSolutionPolicyContractTest#historicalIntegerTextBaselineIsCapturedAsIntegerWithoutRewritingHistory' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 修复后全范围相关回归：51类514项，513通过、0失败、0错误、1既有PPT skip
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

RED 的实际 HTTP响应包括保存方案时把当前基线 `4294967297` 捕获为 `baselineVersion:1` 并返回200。失败先发生于HTTP状态/期望异常断言，后续无变更断言没有执行；不得将RED称为数据库差异断言已通过。

GREEN 的政策HTTP合同18/18（原4+新14）：两个低32位别名、九种坏基线、合法1/最大int、历史`" +01 "`省略请求版本的捕获；拒绝后 project/revision/operation 事实均保持，合法历史数组不改写。发布/批准合同49/49（原10+新39）：三处发布版本比较、双方缺版本、批准语义引用、捕获需求版本、历史数字/文本正例；拒绝前后项目树不变且无渲染/存储调用。Service合同通过反射调用实际私有用例，使用mock外部图/事实/渲染/存储；不能等同HTTP审批、真实来源或数据库事务验收。

全回归还包含原 HTTP/H2事务、权限/来源、架构字节码、模型执行、handoff消费者及 CommandPayload 文本草稿保护。编译和正式base Spotless通过。独立原生审阅 `/root/artifact_read_review` 对计划、实际差异、历史正例和RED给出COMMENT，无实质发现；Java LSP NOT_RUN，不构成维护人审批。最终dev真实任务ID/检查身份见归档报告；初始dev为`48ikrwbk`。

## 未完成范围与回退

本批 MySQL/Kingbase、真实旧数据、完整应用/浏览器、真实模型与客户验收 NOT_RUN。项目/条目版本递增耗尽、完整DTO/用例分离、对象单写迁移/恢复和schemaVersion校验仍是后续工作；不以本片精确引用比较完成替代整个版本生命周期或46项正式AC。

commit门禁NOT_RUN、维护人批准/远端required CI NOT_VERIFIED；dev SCAN_PASS只是增量扫描，不代表submission_ready。没有提交、推送、部署或仓库管理操作。回退仅恢复这三个生产文件的旧比较并去掉新增读取方法，无数据库回退；旧实现会恢复已复现的版本别名问题。

证据：[计划](AQ05_EXACT_REVISION_PLAN.md)、[机器结果与源码摘要](exact-revision-results.json)、[最终门禁](exact-revision-tests/final-gate.report.json.gz)、[归档清单](exact-revision-tests/final-manifest.json.gz)。
