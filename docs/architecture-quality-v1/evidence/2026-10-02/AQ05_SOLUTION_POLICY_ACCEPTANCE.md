# AQ-05 方案政策与项目项修订工程验收

起始HEAD `ad0853831a4af313a67674f1cfa62b403694574a`，工作树干净；独立worktree，原项目WIP未触碰。初始dev62gg9kaf SCAN_PASS，后续任务及真实报告见manifest；dev不是提交或正式验收。

## 变更与职责

Service1344→1248行。纯PresalesSolutionPolicy140行拥有prepare（原标题/章节、presentation信任、基线精确版本、项目sourceRefs、provisional、coverage、最新fit refs）和coverage（原基线/实时scope、section关联、响应唯一性/状态/原因、顺序、计数、百分比/不适用null）。人工SAVE_SOLUTION与已复核S5/S6员工结果调用同一prepare；releaseGate调用同一coverage而仍先requireSemantic/provisional/baseline，再来源及独立人工评审。policy无HTTP/SQL/事务/授权。

PresalesProjectItems75行拥有原find/save/text/enum规则：string ID、UUID、mutable替换移到末尾、immutable追加previousId、原version/actor/UTC、Jackson coercion及错误/withArray副作用保持。内部Rejected只承载status/code/message；Service适配回原SemanticApiException，公开find及既有Java消费者catch契约不变。应用服务仍拥有source授权、修复、fence、事务、CAS、replay/receipt/持久化、批准及冻结字节。无新增依赖/DI Bean/迁移/控制面，原测试文件未修改。

## 实际行为与验证

先原实现28/28（新HTTP4+Integration8+Runtime事务16），迁移59/59，最终九类67/67，零失败/错误/跳过。HTTP基于真实JWT/MockMvc/H2和V211表，刻画完整草稿wire/未知字段/coverage顺序计数/fit refs/精确baseline/source inventories，不可变方案版本与previousId，400/404/409/422错误先后、失败逐字节body不写；既有CAS/回执/事务/来源撤权/frozen artifacts回归仍通过。8项pure合同覆盖UTF-16长度、enum/null coercion、UUID/UTC、原withArray异常、employee presentation边界、66.67舍入、baseline scope顺序、source数组100限制、校验顺序和深副本。

实际JDK21命令：

`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=PresalesProjectItemsTest,PresalesSolutionPolicyTest,PresalesSolutionPolicyContractTest,PresalesIntegrationTest,PresalesRuntimeTransactionIntegrationTest,PresalesAtomicResultAcceptanceTest,ProjectAuthorityFenceDatabaseTest,PresalesArtifactPersistenceContractTest,PresalesProjectPersistenceContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

`python3 -B scripts/quality/verify.py --mode dev --base origin/dev`

`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=HEAD spotless:check`

独立authority_review最终APPROVE_BOUNDED、零严重度发现；LSP/AST六Java诊断请求Transport closed，实际Maven编译/67项回归及手工diff为可用证据。source/log SHA与脱敏数量见solution-policy-test-results.json。

## 失败、范围和回退

新增HTTP夹具的字符串转义编译失败、误取顶层code而不是data.code、用非数字来源及raw表name列（实际title）导致失败，均只修新夹具。初次项目项新文件import语义内部异常被AR-004拒绝，按职责修复，规则/基线未变。纯测试错误猜测IllegalArgumentException，实际Jackson为UnsupportedOperationException，已补直接旧withArray对照并修正新增预期，生产未改；失败日志全部保留并脱敏JWT/JSON凭据/Spring开发密码。

本片不完成公共typed DTO、SQL分页、V2单写/对象迁移、多方言/备份恢复/黄金字节、真实浏览器/角色/模型/后台重启或维护人/业务QA。ADR-AQ-020保持Proposed，正式AC仍NOT_RUN，远端required CI仍NOT_VERIFIED。错误适配必须保留，否则既有Java调用方catch语义将改变。回退仅恢复Service内联方法并移除两模块，无数据库操作。完整暂存/提交/推送证据按实际树追加。

## 完整门禁发现的语义回执顺序缺陷

首候选tree `bbd900f62b6ae50f942740083fa5be726f016619` 完整门禁lojyxc1n exit1/FAIL/submission_ready=false，既有SemanticM2IntegrationTest accepted→相同operation replay整JSON相等断言失败，唯一差异为AssertionPayload.signatureIris顺序；未执行生产提交。完整原失败报告与Java失败摘要保存，不将其视作PASS。

按AQ05_ASSERTION_SIGNATURE_PLAN定位StatementApplicationService.command存result_json与replay/wire.decode的record重构造，Set.copyOf不保证遍历顺序。新增3项JDK-only签名合同，旧实现实际3项执行/2失败；第一次新增test目录缺失导致零目标日志仅为NOT_RUN，不计通过。随后使用既有OntologyAxiomDescriptor惯例，将构造器改为unmodifiableSortedSet(TreeSet)。signature成员/Set equality、权威functionalSyntax不变；新构造/历史对象解码时signature数组词法排序，是明确的wire稳定性修复。旧持久化JSON/回执/批准文件/冻结成果字节不重写或重新渲染。

修复后dev5tb4lj9_ SCAN_PASS；核心12/OWL11/服务端74共97项、零失败/错误/跳过，包含原SemanticM2IntegrationTest7项和售前67项。原回执整响应断言未变，未更新snapshot或门禁/依赖配置。完整树必须在修复后重新检查；ADR-AQ-021仍Proposed，正式AC-20及多方言/迁移/业务QA仍NOT_RUN。

独立authority_review扩大审阅：签名修复APPROVE_BOUNDED、零严重度发现；核心旧验证/语义回执/售前工程回归通过，格式化diff中唯一行为变动为排序构造器。LSP仍Transport closed。正式验收和完整树提交权限不由该意见授予。

## 精确源码树封口

源码提交 `05f3223de83be6b78e57624517225dd1b214a684`，tree `1166ebc3947187faf694cf127dc13a46767b7727`。修复后完整暂存s99n7uhq与正常commit hook qb4q38el均exit0/PASS/submission_ready=true并绑定此树。Java6063总数/5993实际执行/既有70跳过，零失败/错误；UI846/846、类型/ID/Node/enterprise/classic构建通过，仅无UI格式/lint目标为NOT_APPLICABLE。两份真实JSON报告与SHA归档；初次lojyxc1n FAIL仍保留。所有八份源码/测试与原日志SHA核对一致，正常源码提交后工作树干净。证据封口按固定文档影响另验，正式QA/远端required CI未升级。
