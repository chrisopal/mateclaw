# AQ05 发布候选快照职责拆分工程验证

本批完成候选快照构建职责拆分，尚未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `origin/dev` = `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。最终 WIP 身份以归档的 final-gate.report.json.gz 为准，不用 HEAD tree 代替。之前工作区改动保留。

## 代码边界

`PresalesService` 从 1175 行降至 1153 行，候选 handoff 的组装由包内 `PresalesReleaseSnapshot` 承接。构建器接收已校验项目、方案及候选记录，复用现有项目条目查找并返回独立 JSON 树；不访问数据库、鉴权服务或运行时。移除了重复基线查找及同值 historicalClarificationsAvailable 赋值，未引入 bean、库、表或公开接口。

Service 保留原权限/实时发布检查、ID 生成、原字节渲染与持久化、release 保存/重分配、事务和原错误映射。PUBLISH_RELEASE 的审批/发布状态与澄清来源刷新仍留在原路径。schemaVersion=1、字符串 ID、字段与数组顺序、PENDING 候选状态、文件 manifest、深拷贝和风险投影不变。不改历史读取，也不接入待决的旧 hash replay 策略。

## 实际验证

全部 Maven 使用 JDK 21。

```sh
# 修改生产实现前：5 类，23 项全部通过
mvn -B -pl mateclaw-server -am '-Dtest=PresalesReleaseSnapshotContractTest,PresalesIntegrationTest,PresalesHandoffReadContractTest,BiddingHandoffTest,BiddingHandoffReceiptTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 提取后：49 类，411 项，410 通过，1 既有 PPT skip，无失败/错误
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 格式调整后：3 项全部通过，重新编译最终源码
mvn -B -pl mateclaw-server -am '-Dtest=PresalesReleaseSnapshotContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

新增三项刻画通过实际 Service 创建候选的内部边界验证十五个字段的插入顺序、选定 fit 与风险顺序、未选择项排除、完整记录/manifest、非空来源的双向深拷贝，以及缺少独立评审时在渲染/存储前拒绝。该局部夹具使用 mock 语义端口，不代表授权或业务流程验收；后两者仍由既有集成回归保护。刻画在原实现上先通过，提取后测试文件逐字节保持，未降低原断言。缺省节点保持是源码等价审阅结论，不声称新增穷举测试。

首次夹具未配置 graph/statement providers，导致三项在 SEMANTIC_DISABLED 提前结束（2 errors、1 failure）；独立审阅指出后补齐夹具。这不是产品 RED。首次格式检查因新构建器方法签名换行失败，单独 scoped apply 后正式 base 检查通过。全量回归之后只有这一空白变化，归档保留前后源码与空白等价校验；最终源码另跑三项刻画，不重复执行整套未变化逻辑。

## 证据与限制

独立原生审阅 `/root/artifact_read_review` 对本批职责、顺序、复制、错误与原实现对照给出 COMMENT，未发现实质缺陷；完整验证结果和意见归档见下方清单。Java LSP NOT_RUN；意见不是维护人批准。Spotless、Maven 编译/测试、真实 Workbench/Semantic 架构规则和 dev 的结论分别记录，SCAN_PASS 不等于可提交。

此次没有 SQL/事务/存储行为变化，未重跑 MySQL/Kingbase，不把前批数据库验证算成本次运行。完整应用、浏览器、客户验收、真实旧数据升级恢复及历史 Office 样本均 NOT_RUN，46 正式 AC 保持未关闭。commit 门禁 NOT_RUN、submission_ready=false；维护人批准、远端 required CI NOT_VERIFIED。没有提交、推送、部署或仓库管理变更。

回退仅需撤回本批 Service 差异与新构建器；无 schema 回退。下一批仍需推进命令用例、稳定 DTO/错误边界及剩余验收。Service 仍为 1153 行，不能把本片算成售前结构整改完成。

证据：[计划](AQ05_RELEASE_SNAPSHOT_PLAN.md)、[机器结果](release-snapshot-results.json)、[最终门禁与 WIP 身份](release-snapshot-tests/final-gate.report.json.gz)、[归档清单](release-snapshot-tests/final-manifest.json.gz)。
