# AC07 缓存来源在模型外发前重新授权

HEAD `8d2c8b2a68c3d63280f79e8ea21623fbe2223b7b`，dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始工作树干净。本记录针对 AC07 中已证明的缓存上下文外发缺口，不代替整个来源链路或正式业务签收。

## 修复边界

同一项目 attempt 的工具结果已通过工具前后校验，并不表示下一轮模型请求仍可发送该结果。NodeStreamingChatHelper 现在固定保存服务器创建的 ProjectExecutionOptions 与既有 ProjectToolPolicy.Revalidator；每一次实际 stream 调用前、重试等待后再次 requireActive。缺少策略时拒绝；授权异常直接向上传播，不进入供应商错误分类、重试或 fallback。Reasoning、Summarizing、LimitExceeded 共用该入口，保留旧来源正文/精确版本而不删除上下文来规避问题。

初始历史压缩还有一条 ConversationWindowManager → ChatModel.call 同步路径。StateGraphReActAgent 的项目结构化执行入口将服务器 options 传到初始状态准备；仅摘要模型委托调用同一 revalidator 后才调用原模型。没有用“新 UUID 通常无历史”代替此校验。CWM 仍可按既有语义捕获摘要失败并返回裁剪历史；本修复保证拒绝时模型没有收到该摘要请求，后续图模型请求也会重新校验。没有宣称初始化一定立即抛原异常。

没有通用包装原模型：Reasoning/Summarizing 按具体 Anthropic/DashScope 类型选择协议 options，直接包装会改变这些行为。两个入口分别对应同步摘要和流式模型调用，不重复检查同一次外发，不增加业务底座依赖、权限规则、数据库迁移或依赖项。项目 plan_execute 当前明确拒绝；生产项目重试/fallback 已禁用，测试另验证通用 helper 即使发生重试/fallback 仍不能绕过授权。

## 实际验证

- 初始 dev `1lij96bz`、代码后 dev `p6i2uhco`：SCAN_PASS，submission_ready=false，应用工具链并非由 dev 代替执行。
- 添加构造器/装配测试接口而尚未添加行为校验时，18 项回归运行得到 **16 失败、0 错误、0 跳过**；普通 stream 和普通初始摘要两个对照通过。首次夹具 lambda 序列化错误及一次导包编译错误已分别修复，不算业务 RED。
- 新回归最终 **18/18 通过**。真实 ToolExecutionExecutor 两次检查通过后撤权，真实三个消费者均不再调用 ChatModel.stream；授权仍有效时三者仍发送精确来源正文。缺策略三节点均拒绝；供应商错误后的 retry/fallback 拒绝下一次外发，保留原授权异常。编译图由实际 AgentGraphBuilder 构建，验证 options 身份和装配。
- 初始摘要四种条件（授权、撤权、缺策略、普通聊天）使用实际 builder.buildReActAgent、真实 CWM 和非空超预算历史；隔离替换底层模型、输入历史及后续图。断言真实同步 call 的发出/未发出，证明 builder 的摘要策略装配。此夹具不把模拟提供商当真实网络验收。
- 相关回归 **35 类、213 项，其中执行 212 项，失败/错误 0**。唯一跳过项是既有 `AgentGraphBuilderBasePathResolutionTest.absoluteOverride_insideWs_usedAsIs_windows`，原因 `Disabled on operating system: Mac OS X`。保留 RestrictedProjectObservationTest 全部旧来源保留断言；包含来源策略/执行策略、全部 helper 回归、节点输出、CWM、Bidding 隔离及真实生产字节码 WorkbenchArchitectureTest。
- 显式 `mvn -B -Dquality.base=HEAD spotless:apply` 成功。JDK21 JavacTask 比较证明四个 Java 文件格式化前后 AST 完全相同；生产改动仅三文件 72 行新增、3 行删除，无旧文件整篇格式翻新。`git diff --check` 通过。
- Maven JDK21 编译和上述测试实际成功。可用 LSP 仅 tsc 包装器，Java LSP NOT_RUN，不以空 TypeScript 诊断宣称 Java 已检查。

实际回归命令（仓库根，JAVA_HOME 指向已安装 Temurin 21）：

```sh
mvn -B -pl mateclaw-server -am '-Dtest=ProjectModelOutboundAuthorizationTest,NodeStreamingChatHelper*Test,ReasoningNode*Test,SummarizingNode*Test,LimitExceededNode*Test,ConversationWindowManager*Test,RestrictedProjectObservationTest,PresalesSourceScopeTest,PresalesExecutionRevalidationProviderTest,BiddingRuntimeIsolationTest,WorkbenchArchitectureTest,AgentGraphBuilder*Test,StateKeyRegistrationCoverageTest' -Dsurefire.failIfNoSpecifiedTests=false test
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

本机原始记录：`/tmp/ac07-red-complete.log`、`/tmp/ac07-wiring-green.log`、`/tmp/ac07-regression.log`、`/tmp/ac07-format-ast.log`；RED XML、最终35类XML、源码和日志副本在 `/tmp/ac07-outbound-evidence/`。长期归档、独立审核、最终暂存 tree 的 commit/CI 检查由交付记录追加，不由本文预先宣称通过。

## 限制与回退

这是调用前即时授权检查，不是跨外部网络请求的数据库锁。已经发出的请求无法因稍后撤权而收回；既有结果接收授权检查继续负责拒收。不宣称真实模型、生产数据库、全部来源 UI/导出或正式 AC07 业务验收已执行。普通聊天无项目 options 时行为保持；未修改 provider 内部协议实现。

回退这三个生产文件及对应测试会重新打开缓存外发缺口，无数据库/发布物回退。后续不得移除同步摘要或共享流式入口之一的校验，也不得把缺 revalidator 降级为普通聊天。
