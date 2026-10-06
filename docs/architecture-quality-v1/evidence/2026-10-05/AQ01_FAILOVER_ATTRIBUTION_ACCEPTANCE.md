# AQ-01：备用模型响应归属与终态流错误验收

本切片修复成功响应的实际模型归属，并确保可恢复的主模型失败不会提前变成面向用户的终态 SSE 错误。初始提交基线为 `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，`origin/dev` 为 `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；在累计 dirty worktree 上执行，未覆盖或清理其他工作。

实现把 provider/model 身份绑定到每个成功的真实模型响应，并由 ReAct/Plan 状态访问器合并写回；备用项保留其配置模型名，旧构造入口保持兼容。错误事件现在只在重试、切换和备用链收敛为终态失败后发布，成功回退不会泄露前一尝试的错误。原有 warning 切换提示、健康状态、provider pool 与错误分类策略保持不变。

真实生产入口的八种模块组合均启动独立 JVM、通过 HTTP 登录并执行普通聊天。loopback 主 provider 每个组合都返回一次 401；真实 fallback provider 执行两次模型请求和 `getCurrentDateTime` 工具桥，最终答案由 HTTP 返回并回读到会话记录。八份结果均记录 `primary_failed_requests=1`、备用身份 `ac01-local/ac01-probe`、用户/助手消息各一条、项目表查询为零、Spring context 关闭。生产 helper 回归额外覆盖成功回退不发送 error 事件、整条 fallback 链失败恰发一次终态 error；完整 8 项启动矩阵及定向归属/故障回归合计 55/55 通过。结果来自合成 loopback 模型和 H2，不能代表真实供应商或生产数据库方言。

验证命令与结果：

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=ModuleStartupMatrixTest,NodeStreamingChatHelperAttributionTest,NodeStreamingChatHelperFailoverTest,NodeStreamingChatHelperFallbackChainTest,NodeStreamingChatHelperPoolTest,StateGraphReActAgentAttributionTest,PlanStateAccessorUsageTest,StateGraphPlanExecuteAgentAttributionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
55 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS

JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
BUILD SUCCESS
```

首次真实 HTTP 回退验收发现主 provider 401 错误提前广播的问题；失败结果及测试先行 RED 证据保留在 `ordinary-chat-tests/`。最终 Maven 日志、JUnit XML 和八组合 startup/result/sandbox 记录以 gzip 归档在同目录，并登记于本地 archive manifest。最终 dev gate：`python3 -B scripts/quality/verify.py --mode dev --base origin/dev` 返回 `SCAN_PASS`，`submission_ready=false`，`application_acceptance=NOT_RUN`；architecture-ratchet、guard-self-tests 为 PASS，application-toolchains 为 NOT_RUN（dev quick scan）。报告目录/task id 为 `mateclaw-quality-y23e1n75`，base 为 `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，运行记录的 worktree `target_identity` 为 `d430a016a6168f9c41916a26b48cd9866558229f0d785a6f899b65aefdcb07e7`。该 identity 对应写入本段前的报告目标快照；追加报告元数据后还会再次运行 dev gate。

工程测试证据不关闭正式 AC-01–AC-46；登记状态继续保持 `NOT_RUN`。真实厂商调用、多数据库方言、浏览器/完整 QA、正式业务签收和远端 required CI 尚未由本切片验证。回退本片会重现备用响应归属缺失和提前 error SSE 问题；未修改既有 Flyway migration、权限、工具白名单或事务边界。
