# AQ05 基线批准职责拆分：工程验证

## 修改与边界

PresalesService 从1124行降至1067行，新包内 PresalesBaselineApproval 115行集中一项职责：从已授权项目和当前可信事实准备内部批准基线。它保留原有 reason/需求/澄清/范围/绑定/ontology/声明修订/需求修订/来源摘要的校验顺序，按原字段与证据顺序构造 references。内部批准仍标记 UNCONFIRMED，不升级为客户确认。该变化是职责拆分，不是代码总量缩减。

Service 继续拥有 admin 判定、模块可用性、项目锁、回放/CAS、事务、saveItem、项目/revision/receipt 写入。准备器不接触 Repository，不拥有事务/提交或独立入口；保留 ObjectProvider 的原有延迟读取。领域拒绝复用 PresalesRejected；Service 复用 legacyRejection/checkSourceAccess，维持原状态、码和消息。无新依赖、Spring bean、表、Flyway、权限或全局序列化变化。

## 验证范围

- 原实现先执行2类69/69：61项 ReleaseSnapshotContract 与8项 HTTP/H2 Integration。新增12项刻画覆盖前置失败优先级、三种澄清缺陷、空/未绑定图、重复绑定ontology过期、后续需求失败不留部分批准、后续来源拒绝保持原错误与载荷、引用完整JSON顺序及UNCONFIRMED。
- 原HTTP集成的member拒绝、非可信声明拒绝、原始资料摘要变化拒绝增加了项目body/version、全部项目修订、workspace回执三表前后相等断言。它们使用既有真实Service/Flyway/H2/授权与语义服务，维持既有夹具和外部能力替身范围。
- 提取后相关51类567项：566通过、0失败/错误、1既有 PresalesPresentationCompilerTest 跳过。实际Java21编译、WorkbenchArchitectureTest及SemanticCoreArchitectureTest包含在内。原测试断言保留，未增加skip或改门禁阈值。
- 正式base Spotless检查通过，git diff --check通过。初始 dev `2yqgwtm_` 与切片 `l0t_yvyr` 均SCAN_PASS、submission_ready=false。最终实际树身份和命令日志在 final-report.gz/final-manifest.gz；不把文档自述当作可提交授权。
- 独立技术审阅结果保存于 independent-review.gz；任何COMMENT/无阻断意见都不替代维护人批准。工具不可用必须按审阅记录的限制报告。

## 实际命令和证据

工作区 `/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`；Maven显式使用本机JDK21。

```sh
mvn -B -pl mateclaw-server -am -Dtest=PresalesReleaseSnapshotContractTest,PresalesIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

修改文件为main的PresalesService/PresalesBaselineApproval，test的PresalesReleaseSnapshotContractTest/PresalesIntegrationTest，及本片计划/结果/README/证据。完整路径、最终4份源码SHA与实际执行类计数见 baseline-approval-results.json。按每次日志Running类归档对应Surefire XML，且在下一次Maven前完成；保存迁移前服务及最终源码，日志脱敏后压缩/解压摘要均回读校验。Maven、formatter、门禁执行期间没有编辑仓库。

## 尚未证明与回退

本片未重跑MySQL/Kingbase，未执行全应用、浏览器、真实模型或Office；不能把前批方言证据算作本批结果。完整commit门禁、独立维护人批准及远端required CI尚未验证；本片未提交或推送。46正式AC仍NOT_RUN。

总服务仍有其他用例，稳定领域DTO、对象/修订/依赖/attempt迁移、历史回执hash兼容和真实历史恢复继续开放。完整V2/Delivery规格输入尚需对齐，不能编造schema或签收。当前反射刻画证明服务接线行为，HTTP回归证明代表性真实授权/持久化路径，均不等于全矩阵客户验收。

回退本片将prepare正文放回原baseline方法并去掉协作者字段/构造，不需要数据迁移。不可同时撤回前批精确版本、来源校验和冻结成果修复。
