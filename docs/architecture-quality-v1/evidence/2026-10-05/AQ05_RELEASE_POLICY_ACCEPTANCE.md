# AQ05 发布规则收敛工程验证

本批完成发布纯规则归位与重复逻辑删除，未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；最终 WIP 身份见 final-gate.report.json.gz。之前 WIP 与证据保留。

## 修改与兼容

修改 `PresalesService`、`PresalesSolutionPolicy` 和既有 `PresalesReleaseSnapshotContractTest`。Service 1153→1117 行；复用现有策略类承接 releaseBaseline 和 releaseReviewId，无新 bean、依赖、表、公开接口或策略注册层。

releaseBaseline 按原次序检查 provisional、基线查找、覆盖、最新基线整节点相等和需求数量；releaseReviewId 按原规则判断最后一个合格人工评审的 blocker，并返回同一 reviewId。Service 在两段纯规则之间仍逐条执行需求版本→图绑定→ontology→trusted statement→evidence/currentSource，再检查 fit 引用。没有将所有需求版本验证提前批处理。删除了只供 releaseGate 使用的 coverage 转发方法，以及创建候选时再次遍历评审列表的重复逻辑。

保留缺 provisional 默认拒绝、缺 authority 的旧 HUMAN_REVIEW 资格、同作者/不同 solution/UNTRUSTED_DRAFT/非人工评审排除、后续合格评审覆盖较早结论、BLOCKER 的 ACCEPTED 不等于 RESOLVED。reviewed 布尔仍与 reviewId 独立，不用空 ID 作为无评审哨兵。原 Rejected 转 HTTP status/code/message 的边界、审批/发布/预览/下载路径、原字节/事务/保存顺序不变。

## 实际验证

全部 Maven 使用 JDK21，生产实现前先通过基线刻画；没有放宽断言。

```sh
# 提取前：4 个实际运行类，26/26
mvn -B -pl mateclaw-server -am '-Dtest=PresalesReleaseSnapshotContractTest,PresalesSolutionPolicyTest,PresalesSolutionPolicyContractTest,PresalesIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 提取后：49 个实际运行类，418 项，417 通过、1 既有 PPT skip
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

新增七项刻画与原三项一起通过；测试文件从基线通过至提取后逐字节保持。新增检查覆盖资格排除、最后合格评审的决策/落盘身份、OPEN/ACCEPTED blocker、旧 authority 和 warning 兼容、provisional/coverage/旧 baseline/数量/版本错误次序，以及图绑定失败早于后续需求版本失败、fit 缺失早于评审失败。拒绝路径还断言项目内容未变、未触发渲染或成果存储。

这些 Service 内部边界测试使用 mock 外部服务，图绑定的优先级案例不代表完整 source digest/ontology/trusted 错误矩阵；既有 HTTP/H2 及来源、权限、启动缺 bean 回归另行实际执行。没有用当前 XML 目录中旧文件推算数量，仅按当次 Running 类读取并在后续 Maven 前归档对应 XML。测试、编译、Spotless 与 dev 的作用分别记录，SCAN_PASS 不代替完整提交门禁。

独立原生审阅 `/root/artifact_read_review` 对计划、实际提取和证据给出 COMMENT，未发现实质缺陷；特别复核了空 reviewId 与缺省 authority 兼容及逐条外部检查顺序。Java LSP NOT_RUN；该意见不是维护人批准。

## 尚未关闭

这次不改 SQL、持久化或事务，没有重跑 MySQL/Kingbase；前批方言证据不冒充本次实测。完整应用/浏览器/业务验收、真实旧数据升级恢复、历史 Office 样本仍 NOT_RUN。46 正式 AC 未关闭，commit 门禁 NOT_RUN、submission_ready=false，维护人批准与远端 required CI NOT_VERIFIED。没有提交、推送、部署或修改仓库管理设置。

回退仅撤回本片三个 Java 文件差异，无数据库回退。售前仍需处理其余命令用例、稳定 DTO/错误边界、完整业务验收；1117 行 Service 不代表整体重构完成。旧 hash replay 政策待决，仍未整合。

证据：[计划](AQ05_RELEASE_POLICY_PLAN.md)、[机器结果](release-policy-results.json)、[最终门禁](release-policy-tests/final-gate.report.json.gz)、[归档清单](release-policy-tests/final-manifest.json.gz)。
