# AQ-05 发布实时授权职责工程验证

本片按[计划](AQ05_RELEASE_AUTHORIZATION_PLAN.md)将发布前实时事实检查从大型Service提取；不是完整AQ-05、V2迁移或正式业务验收。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，分支`codex/aq01b-execution`。仅工作树`/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`，初始108项dirty，保留累计WIP。

## 实现与兼容

新增包内PresalesReleaseAuthorization，三个具体操作requireEnabled/validateEvidence/reviewId集中模块状态、精确需求与声明revision、当前本体、证据来源及独立人工评审检查。复用SolutionPolicy/ProjectItems/SourceAuthorization和公开语义服务；每次调用读取当前事实，不缓存授权，不新增bean、依赖、表或通用规则框架。

PresalesService保留当前成员/admin检查、来源先于receipt回放的顺序、CAS、事务、文件持久化和状态迁移；边界将PresalesRejected/SourceAuthorization.Denied转回旧SemanticApiException的同status/code/message。保留逐项访问和短路顺序，不批量去重。可用性仍检查semantic/graph/statement；不把query provider变成额外前置条件，证据为空的历史记录兼容性由直接合同保护。UNKNOWN fit-gap仍跳过，其余证据仍在最终人工评审之前复核。

Service 911→861行；策略94行，新增测试178行。生产源码合计增加44行，换取职责独立和明确异常边界；已删除搬出的循环及不再使用的字段/帮助方法。Service仍承载其余写用例与JSON兼容职责，不宣称整体结构整改完毕。无前端、门禁配置、依赖、旧迁移或生产数据改动；已有测试断言未修改。

## 实际验证

JDK21下先运行原5类110项，再在拆分后重跑110项，均0失败/错误/跳过。增加8项直接策略测试后定向118/118。新测试覆盖构造后禁用/撤销provider、query缺失而无证据的历史发布、成功后本体变化/事实撤回、来源拒绝身份及短路顺序、UNKNOWN与已知fit-gap的检查顺序。原61项ReleaseSnapshot合同继续保护Service对外错误优先级、精确版本、独立评审、失败不写文件/回滚及快照身份。

最终格式化后扩展55类721项：720通过，0失败/错误，1既有skip：PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes缺少PRESALES_PPT_SKILL_ROOT。日志中的负向Flyway失败是预期异常合同，相关测试XML通过；不据单条ERROR判断整组失败。编译1648份生产源与适用测试实际通过，WorkbenchArchitectureTest包含在扩展组。

```sh
# 下列Maven命令均在上述工作树，JAVA_HOME为/usr/libexec/java_home -v 21的结果
mvn -B -pl mateclaw-server -am test -Dtest=PresalesReleaseAuthorizationTest,PresalesReleaseSnapshotContractTest,PresalesSolutionPolicyContractTest,PresalesArtifactPersistenceContractTest,PresalesHandoffReadContractTest,PresalesDisabledStartupReadContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl mateclaw-server -am test '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -B -pl mateclaw-server -am -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

首次显式格式化命令误用glob的spotlessFiles及不存在的quality profile，exit1；改为正则筛选且移除profile后3文件格式化成功，正式Spotless非修复检查exit0。保留失败/修正日志；未修改工具版本或仓库配置。镜像metadata checksum告警保留，不降低验证设置。格式化与检查分开。

初始dev e4yet_s4、片后dev 2fg_vsnu均SCAN_PASS/submission_ready=false；dev application-toolchains为NOT_RUN，上述应用命令为独立实跑。最终文档/归档后重跑dev并在仓库外核对gate.snapshot与报告target_identity，避免回写报告改变被检tree。

独立审阅结论另见[AQ05_RELEASE_AUTHORIZATION_REVIEW.md](AQ05_RELEASE_AUTHORIZATION_REVIEW.md)。它不等于维护人、QA或控制面合并授权。

`release-authorization-tests/`归档before Service、原/新增测试日志、XML、格式化失败/成功日志、源码和results.json；archives.json记录压缩及解压SHA并逐项回读。JWT及生成测试密码已脱敏。只采用本轮日志列出的55类XML计数，排除target目录陈旧报告。

## 未测项、下一步和回退

本片没有提交/推送/部署；精确暂存树commit门禁未运行，remote required CI未证明启用。正式46项AC状态均NOT_RUN；真实浏览器、维护人/QA/客户、MySQL/Kingbase本轮与生产历史数据未测，不能由H2/模拟provider推导通过。

Service其余业务用例及DTO边界、前端模板结构、正式V2/Delivery规范迁移、坏/耗尽历史RUNNING恢复仍开放。尤其不绕过版本约束伪造任务终态，也不把本次发布前复核解释成跨实例原子事实锁。

开发回退恢复归档before Service并删除本片策略/独立测试；保留此前查询、发布快照、摘要和工作台重构。无数据库回退动作。
