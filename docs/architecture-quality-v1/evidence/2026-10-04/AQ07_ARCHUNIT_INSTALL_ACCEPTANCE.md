# AQ07 编译架构检查与清零路径封口

当前 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`，固定 base `origin/dev=ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。候选为未提交工作树，保留 AQ05/AQ20 WIP；不能用 HEAD tree 冒充候选 tree。计划见 AQ07_ARCHUNIT_INSTALL_PLAN.md，源码/日志摘要与实际测试清单见 archunit-install-results.json。

## 实际落地与调整理由

原 integration 文件仅为文档模板；实际 `mateclaw-server/src/test/java/vip/mate/architecture/WorkbenchArchitectureTest.java` 现使用既有 ArchUnit 1.3.0，无新增依赖。生产字节码导入排除测试类，测试专用 canary 显式导入。规则覆盖运行底座到具体工作台、工作台 Controller 到持久化、纯语义层到框架/工作台三个边界。原更严格 `SemanticCoreArchitectureTest` 保持 HEAD 原字节。

初次真实编译测试 15 项有 1 项失败：把 Controller 的 Repository 名称分类复用于语义层，误拒纯应用 `ExtractionPorts.ExtractionTaskRepository`。修复将语义框架限制与 Controller DAL 分类分开，补实际纯 port 正例；没有放宽原模板的框架/工作台禁用范围。独立审阅发现无 Repository 后缀的持久化包类型可能漏检，补 repository/mapper/dao 包匹配与 QueryPort 负例，保留根包/嵌套类型匹配。初次失败日志及修复后证据均归档。

各规则要求聚合候选非空，负例还必须包含具体禁用目标名，防止把空范围失败当作正确拒绝。正例验证公共执行契约、ObjectMapper/应用服务、纯语义应用 port。空输入与缺少某一规则候选的局部输入会失败；这不证明任意不完整 classpath 都会被发现，也不证明每个未来包存在。

实际生产导入 2,871 类：运行底座匹配 876、工作台 Controller 匹配 3、纯语义 core/application 匹配 122。没有将这些数量描述成逐包完整性或业务验收。

## 零容忍与审核约束

当前词法源快照中，选定 AR-001/AR-002 范围命中为 0；`.quality/policy.json` 对 AR-001 的 agent/common/auth/workspace/tool/skill 六个 main 路径及 AR-002 的 presales/bidding/delivery 三个 main 路径封口，全部以 `/` 结束。以后即使与 base 同指纹的旧违规重现也拒绝；其他范围保留原 ratchet。

对九条路径分别运行纯快照反例：同一旧违规未封口 PASS、封口 FAIL、新增 FAIL；相邻目录前缀不被误封。结果见 archunit-policy-mutations.json。没有修改 gate 算法、baseline、冻结清单、POM、工具版本或旧迁移，也没有把 source scanner 扩大宣称为完整类型分析。

README、RULES、审核台账、工程门禁 Skill、PR 模板已同步，默认用于后续开发。新增路径需同步审核映射。独立 `authority_writer_audit` 技术审阅 COMMENT，原 MEDIUM 已关闭；其读取编译日志及 mutation 记录，没有独立复跑。Java LSP NOT_RUN。此技术审阅不构成独立维护人批准、仓库管理授权或远端强制生效。

## 实际验证

| 检查 | 实际结果 |
|---|---|
| JDK21 Maven：WorkbenchArchitectureTest + SemanticCoreArchitectureTest | 17 tests，0 failure/error/skip，exit0 |
| JDK21 Maven：Presales* + ProjectAuthorityFenceDatabaseTest + 上述架构测试 | 356 tests，355 实际通过，0 failure/error，1 个既有 PPT 环境跳过，exit0 |
| scoped Spotless apply（开发动作，独立于检查） | 6 个文件，3 个实际格式变化，exit0 |
| 项目 Spotless check：`-pl mateclaw-server -Dquality.base=origin/dev spotless:check` | 117 文件，0 需修改，exit0 |
| 前一阶段初始 dev bz6m680n / 本次恢复复跑 dev 3k21duk5 / 封口后 dev lwa6pow8 | exit0 / SCAN_PASS；submission_ready=false，应用工具链 NOT_RUN |
| 9 个路径源快照反例 | 旧违规在封口范围内均 FAIL；新增违规均 FAIL |
| 当前 AQ05/AQ20 来源摘要回读 | 与前次候选源码摘要一致；本片未改变业务实现 |

合并回归命令为 `mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test`，JAVA_HOME 为安装的 JDK21。命令不绕过断言；定向回归不冒充全量 commit 门禁。64 个测试/源码/日志归档均包含摘要并完成 gzip 回读。文档整理后的 dev 实际报告另存为 `archunit-install-tests/final-dev-report.json.gz`，以其 target_identity 核对完整候选源快照；归档本身不修改受检源码。隔离 MySQL 29 项应用回归另见 AQ06_CURRENT_MYSQL_WRITES_ACCEPTANCE.md，V218 fixture 不证明 V219/versioned writer。

## 未完成与回退

46 正式 AC 仍 NOT_RUN；Kingbase、生产升级/恢复、真实角色/并发/浏览器/模型/Office、维护人批准、可信 runner bootstrap、远端 required CI 均未据此关闭。AQ20 新 hash 尚未接入 writer，旧回执兼容业务选择仍待用户回答；不提交未接入工具作为修复完成。

本片无数据库或 API 行为变化。独立控制面审阅后可用正常 Git revert 回退本片，不能自行删除封口或弱化断言以放行违规。commit/push 必须对应完整、精确暂存候选树的门禁 exit0 与 submission_ready=true；本记录不替代该检查。
