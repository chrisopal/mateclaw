# AQ05 售前员工查询固定 DTO：工程验证

## 变更与兼容

PresalesDtos新增Employee(id, name, enabled, available)记录，PresalesEmployeeRuntime直接构造该记录，GenerationService与GenerationController完整保留此泛型。删除开放Map返回和字符串键拼装以及两个无用Map导入；四份生产源码合计净减少6行，没有新依赖、bean、表、事务或schema框架。

既有viewer权限检查仍在任何运行时查询之前；员工按原Workspace/enabled/deleted/agentType/runtimeType条件筛选，并保留上游顺序。id仍为字符串，只有四个公开字段，不携带Agent完整实体、模型或提示信息。name/id的null仍拒绝，空名称保持原样，不把上游失败转换为空成功。record的构造器null检查保留原Map.of的拒绝行为。结果仍是与实体后续变化隔离的不可修改列表。

本片沿用ADR-AQ-022查询DTO方向，未修改前端类型和解码器、生成/取消、请求/回执/持久化字节。Java返回类型变化；已搜索仓库消费者并完成相关编译，未宣称仓库外Java消费者兼容。JSON对象字段顺序可能改变，旧Map顺序本不稳定；字段和值及数组顺序保持。

## 验证

- 新增PresalesEmployeeQueryContractTest的22项在旧实现上先通过；连同原运行时与生成控制器共36/36。覆盖大整数ID、字段全集、筛选10种拒绝、native大小写/旧deleted=null、实体后续变更隔离、缺提供者、上游失败、null身份和401/403/404拒绝先于查询。
- HTTP验证使用MockMvc、真实Controller→GenerationService→EmployeeRuntime与异常处理器，AgentService、权限及外部依赖使用Mockito替身。只证明此入口传递viewer并先拒绝，不替代真实成员角色矩阵或登录环境验收。
- 替换后52类589项：588通过、0失败/错误、1既有PPT环境条件跳过。包含实际WorkbenchArchitectureTest/SemanticCoreArchitectureTest、来源授权与投标消费者。
- 现有前端queryResponse.test.ts 42/42，0 failed/pending；正式base Spotless和git diff --check通过。前端没有变化，本轮未重跑全部UI/构建，不能把上一轮全量结果当本轮执行。
- 初始dev任务ical3bct为SCAN_PASS。最终dev实际任务/检查身份见final-report.json.gz与final-manifest.json.gz；dev不是commit门禁。独立审阅结论见independent-review.txt.gz，COMMENT不替代维护人批准。

## 可回读证据

工作区 /Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw。HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。变更文件为PresalesDtos/PresalesEmployeeRuntime/PresalesGenerationService/PresalesGenerationController、新测试PresalesEmployeeQueryContractTest及本片文档/README/证据；实际源码SHA在employee-dto-results.json。新测试之外既有断言和跳过条件保持，所有运行期间未编辑仓库。

命令（Maven使用Java21）：

```sh
mvn -B -pl mateclaw-server -am -Dtest=PresalesEmployeeQueryContractTest,PresalesEmployeeRuntimeTest,PresalesGenerationControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
pnpm exec vitest run src/features/presales/__tests__/queryResponse.test.ts --reporter=json --outputFile=/tmp/mateclaw-employee-dto-ui.json
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

旧源码、新源码、两轮实际日志/对应XML、Vitest JSON和门禁结果存于employee-dto-tests。XML按日志执行类归档，不把上轮target残留当本轮结果；每次归档先于下一次Maven。日志脱敏并验证压缩/解压摘要。

## 剩余工作与回退

完整项目/命令DTO、其余用例、V2对象/修订/依赖/attempt模型、旧回执hash策略与迁移未完成。真实数据库方言/生产历史恢复、完整浏览器/模型/Office、46正式AC、维护人批准、精确暂存树commit门禁与远端required CI仍未验证；本轮未提交或推送。

回退只恢复本片四份生产源码与新刻画测试，无数据操作；不得撤销前批版本、冻结成果或来源授权修复。
