# AQ-05：来源与语义读取应用用例独立

四个只读入口（来源目录、capabilities、可信事实、证据）从PresalesService移到条件启用的PresalesSourceQueryService，Controller直接调用新bean，旧Service方法删除，没有保留转发。Service959→898行。项目相关查询单向复用Service.get完整viewer、当前来源、冻结历史授权链；写服务不依赖查询服务。新增查询仍不是独立持久化权威或完整领域DTO整改。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。初始dev ndn5db4_为SCAN_PASS/submission_ready=false。计划[AQ05_SOURCE_QUERY_PLAN.md](AQ05_SOURCE_QUERY_PLAN.md)。共17个源码文件，精确路径/摘要保存在source-query-tests/results.json.gz：5个生产文件（Service、Controller、Items、新SourceQuery、GraphApplicationService），11个已有Controller合同上下文导入真实新bean（其中QueryDto新增3个HTTP合同），1个新增GraphOptionalBindingContractTest（10项）。未mock新应用服务以绕过接线验证。

## 边界变化

- 图绑定的“没有结果”现在由GraphApplicationService.findBinding通用Optional入口表示。它调用原get全部权限/KB/图/修订校验，仅原404返回empty，既有get及HTTP错误保持；401/403/409及非领域异常继续传播。售前新查询不导入semantic.web异常或身份类，没有扩大AR-004豁免。
- 图必须绑定项目的不变量复用ProjectItems。命令服务通过原SemanticApiException适配保留已有Java/HTTP错误出口；查询使用本域拒绝，wire仍400 INVALID_REQUEST。权限→项目完整来源检查→图绑定→语义模块→证据读取顺序不变。trusted facts同样先完整项目读取再检查语义可用性。
- 来源200条上限、事实500条上限、图去重及迭代顺序、null/省略字段、大ID字符串、模块缺bean和禁用行为保持。evidence返回现有公共SemanticQueryDtos.EvidenceResult，Controller为R<EvidenceResult>，复用稳定DTO而不新造对象。
- 无SQL/依赖/迁移/授权策略变化，事务、CAS、幂等、生成/取消、冻结读取与发布写入位置不变。Graph源文件格式化212→309行；格式化前后同JDK21编译的javap -c -p输出逐字节一致。原语义差异只有新增Optional入口和import，前后源码/字节码都已归档。

## 已执行验证

改生产前4类23/23；首次及DTO收紧后5类各36/36。新增合同检验真实get内部授权/KB归属/图/修订，404缺失与原get继续404并存；401/403/409不变、legacy修订409和存储异常不被当empty、非法ID先授权；HTTP检验现有EvidenceResult完整字段、上游错误透传、来源/角色/绑定/模块拒绝顺序与未授权不调用来源目录。

最终适用回归53类695项，694通过、0失败/错误、1既有条件skip：PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes，缺PRESALES_PPT_SKILL_ROOT。完整main启动矩阵9/9，0失败/错误/skip；8种模块组合加真实三进程重启，11份当前进程结果均回读，成功沙箱已删除。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesQueryDtoContractTest,PresalesIntegrationTest,PresalesDisabledStartupReadContractTest,SemanticGraphBindingTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesQueryDtoContractTest,PresalesIntegrationTest,PresalesDisabledStartupReadContractTest,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=ModuleStartupMatrixTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

提取后dev7qi00ret、格式化及扩大回归后dev94dv4v0u均SCAN_PASS/submission_ready=false；architecture-ratchet与guard-self-tests通过，application-toolchains为NOT_RUN。后者受检identity `a86a9105277fdcbe60b4170ddbcdd2be3ffe7ad00b544722ecbc471f1109b31a`，为本文/最终证据归档前。完成全部证据后再跑最终dev。source-query-tests保存baseline/first/focused/full实际日志、XML、各阶段源码、17文件摘要与归档清单，日志脱敏，下一次Maven前先保全XML。

[独立审阅](AQ05_SOURCE_QUERY_REVIEW.md)为COMMENT，17个指定文件无可操作发现；Java LSP与AST工具不可用，不计作通过。Spotless和git diff --check通过。

## 剩余范围与回退

本批未运行真实MySQL/Kingbase、完整前端/浏览器、外部供应商、正式QA和精确暂存树commit。实际main仅隔离H2与合成模型，不扩大为供应商或数据库方言验收。完整领域DTO、项目命令/详情/成果用例、前端工作台结构及V2迁移仍开放；46正式AC保持NOT_RUN。技术审阅与本地SCAN_PASS不能代替维护人批准、业务签收或远端强制CI。未提交、推送、部署，保留原WIP。

回退恢复Service四方法、原graph绑定校验与Controller构造/调用，删除新查询bean及Optional入口并还原相关测试接线；无DB操作。不应把单向Query→现有Service.get临时复用误写成彻底CQRS拆分完成。
