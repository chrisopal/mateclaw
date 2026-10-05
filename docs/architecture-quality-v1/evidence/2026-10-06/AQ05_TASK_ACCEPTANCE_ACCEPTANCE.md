# AQ-05 任务结果接收职责工程验证

本片完成[计划](AQ05_TASK_ACCEPTANCE_PLAN.md)限定的后端接收规则拆分；完整AQ-05、V2迁移和正式业务验收仍开放。仅architecture-quality工作树，HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，分支`codex/aq01b-execution`。保留初始115项dirty累计WIP，无提交、推送、部署或生产数据操作。

## 职责与兼容

PresalesTaskAcceptance集中候选状态/不可信标记、完整运行身份比较、成功结果格式、权限围栏、runtime重验和模型校验。它是包内具体业务类，没有Service回调或新增bean，不写项目、不缓存授权。Service删除原围栏/身份帮助方法，保留任务保存、成功投影、角色与来源授权、幂等回放、CAS、revision/receipt及READ_COMMITTED/REQUIRES_NEW事务。围栏锁仍由调用者事务保持至提交/回滚。

保留原短路顺序与错误：结果object→snapshot object→runtime可用→当前任务及snapshot→完整身份→围栏→runtime重验→模型校验。FAILED只忽略status/finishedAt/result/error/rejectedOutput；成功仅忽略前三项，未知扩展字段仍必须精确匹配。已经取消或终态任务不能被迟到结果覆盖。匹配的失败回写仍可记录撤权诊断，无需再次获得执行权限；成功回写仍必须取得完整权限。ProjectItems的拒绝在Service转换为原SemanticApiException；模型错误不改。

Service 861→799行；新接收类93行，独立测试201行。生产总代码增加31行，换取单一接收边界和直接可验证性；不宣称整个Service重构完成。无前端、外部协议、表、旧迁移、依赖或检查规则调整。

## 实际验证

JDK21：原4类94/94在提取前通过；提取后同4类及新增17项共111/111，0失败/错误/跳过。新增测试包括来源/双actor围栏参数、锁先于runtime、runtime先于模型、provider动态撤销、错误优先级、取消/未知扩展身份变化、成功诊断注入和失败诊断保留。原真实Spring/H2合同继续验证事务代理、并发撤权等待、独立提交及拒绝后三表不变；不以mock证明数据库锁生效。

最终扩展56类738项，737通过、0失败/错误、1既有条件跳过：PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes缺少PRESALES_PPT_SKILL_ROOT。包括ProjectExecution、ProjectAuthorityFenceDatabase和WorkbenchArchitectureTest。日志内预期的负向迁移错误不代表测试失败；统计以本轮Running清单对应XML为准，排除陈旧target报告。

```sh
# JAVA_HOME由/usr/libexec/java_home -v 21取得，均在本工作树执行
mvn -o -B -pl mateclaw-server -am test '-Dtest=PresalesTaskAcceptanceTest,PresalesAtomicResultAcceptanceTest,PresalesRuntimeTransactionIntegrationTest,PresalesCommandPayloadContractTest,ProjectAuthorityFenceDatabaseTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -B -pl mateclaw-server -am test '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -B -pl mateclaw-server -am -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

显式格式化仅3个本片Java文件，与非修复Spotless检查分开；两者exit0。初始dev ejgjufme、源码后scaiuwwg均SCAN_PASS/submission_ready=false；dev本身application-toolchains为NOT_RUN，上述编译/测试/格式为独立实跑。最终文档归档后再执行dev并在仓库外核对target_identity，避免证据回写改变被检tree。

独立技术审阅见[AQ05_TASK_ACCEPTANCE_REVIEW.md](AQ05_TASK_ACCEPTANCE_REVIEW.md)，尤其核对新增测试控制面。`task-acceptance-tests/archives.json`记录before/after源码、日志、各轮XML与计数的压缩/解压SHA；JWT和生成测试密码脱敏，归档逐项回读验证。统计解析首次使用错误的单横线日志模式而无匹配，改从本轮Running行取类名；未重跑或篡改测试以获得计数。

## 未测项与回退

本片未运行精确暂存树commit门禁、远端required CI或维护人/QA/客户签收；46项正式AC继续NOT_RUN。MySQL/Kingbase本轮未运行；没有前端改动，未重复UI或浏览器验证。既有PPT环境跳过仍待具备技能目录后实测。LSP/AST审阅工具的实际能力另见独立审阅记录。

后续仍需完整强类型DTO及Service其他业务职责、V2单写迁移、历史坏/耗尽版本RUNNING任务恢复和真实工作台验收；本片不绕过版本约束或伪造终态。开发回退只恢复归档before Service并删除本片接收类/独立测试，保留此前修复与快照/哈希兼容逻辑，无数据库回退动作。
