# AQ-05：来源撤权后的修复策略独立

PresalesService从1024行降至959行。修复命令的字段白名单与受限响应投影集中到包内纯PresalesRepairPolicy，删除Service旧私有实现及常量，三个调用点直接使用策略。复用既有Command解析、Listing阶段和来源集合契约，无新依赖、Spring bean、表或迁移。授权、来源复核、事务、CAS、归档、回执与回放顺序原位保持；策略不授予权限。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始dev 25g_5une SCAN_PASS/submission_ready=false。改动为PresalesService.java、新增PresalesRepairPolicy.java与PresalesRepairPolicyTest.java，计划见[AQ05_REPAIR_POLICY_PLAN.md](AQ05_REPAIR_POLICY_PLAN.md)。

## 行为与证据

先运行原有HTTP/H2/事务/恢复84项，全部通过。HTTP合同覆盖来源撤权后安全字段、12个来源集合清空、opaque binding及非法role转UNKNOWN、viewer/匿名/跨Workspace拒绝、越界载荷拒绝、CAS冲突/归档拒绝、unbind回放；真实Spring事务合同覆盖员工重新绑定。对坏版本恢复的核查确认现有39项中包含耗尽/无效版本跳过并继续其他项目，不重复实施或声称修复已解决行为。

新增13项策略测试覆盖最小允许载荷、附加字段和错误类型拒绝、非修复命令拒绝、边界值校验仍由命令层承担、字符串大ID/null保留、源字段清空及输入/输出隔离。首次13项两失败：一个role夹具误以为类型校验承担枚举值规则，改为对象role后，仅输入不变性一项失败。该RED证明旧restrictedProject的stage fallback会通过withArray在稀疏输入上创建空集合。唯一有意行为修复是调用既有stage时传深副本，输出语义不变，不推断已经造成数据库污染。

修复后定向97/97；扩大51类680项，679通过、0失败/错误、1既有条件skip（PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes，缺PRESALES_PPT_SKILL_ROOT）。Spotless和git diff --check通过。未改前端，本批不重跑无关前端工具链；已有H2组件/HTTP证据不替代本批未执行的真实MySQL/Kingbase、浏览器/模型或完整提交门禁。

实际命令：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesIntegrationTest,PresalesRuntimeTransactionIntegrationTest,PresalesTerminalReceptionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesRepairPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=PresalesRepairPolicyTest,PresalesIntegrationTest,PresalesRuntimeTransactionIntegrationTest,PresalesTerminalReceptionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

`repair-policy-tests/`归档原实现、失败源码/日志/XML、97项成功及680项最终XML、格式检查、结果/源码摘要与归档清单。实现后dev 58hdjlm8为SCAN_PASS/submission_ready=false；architecture-ratchet和guard-self-tests通过，application-toolchains NOT_RUN。检查identity `e5dd132a2426a1a8f2076ae0766f27344349cd2980836efd24930ba21594085c`（本记录/最终归档前），报告已归档，完成证据更新后重跑最终dev。

## 独立审阅与剩余范围

[独立审阅](AQ05_REPAIR_POLICY_REVIEW.md) COMMENT，0个可操作发现；Java LSP/AST检查不可用，不伪称通过或维护人批准。新增纯策略的源码行数不是总代码减少；实际简化是Service不再混合来源撤权修复政策和用例持久化。额外深复制只在stage缺失时发生，未测性能，不宣称优化速度。

剩余完整DTO/用例结构整改、坏/耗尽版本受控迁移、多实例和正式QA仍开放。46个正式AC保持NOT_RUN，未提交/推送/部署，精确暂存树commit及远端强制未验证。回退恢复原Service方法/常量和三个调用点，移除本片纯策略及新增测试/文档；会恢复旧稀疏输入修改行为，不涉及DB操作，不覆盖其他WIP。
