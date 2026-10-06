# AQ01 委派取消与 SSE 输送隔离工程记录

基线 HEAD `679092a86009681ea8d7ce02927370bc6a9bcfdf`，tree `bf38a8cf007176f6781d810705b0518b92a42272`；dev base `origin/dev`=`ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，初始干净。用户已授权继续修复与提交推送；没有合并、部署或生产操作。

## 问题与实现

真实 ChatStreamTracker 在 requestStop 的 hooks/dispose 前同步广播 phase；子 relay 转发到根 SSE，根完成事件持锁慢发时会阻塞 B 的取消并阻止 C 收到请求。新增窄入口 requestStopWithoutNotification 复用持久 Stop 意图、当前状态校验、标记、hooks、dispose 和持久化异常兜底，仅并行批次使用；默认用户 Stop、cancelRun 仍先发 interrupting phase。批次取消不再发该中间通知，最终结果继续携带 timeout/cancelled。

已完成 future 的同步 whenComplete 注册也会在派发线程内发送通知。ChildResult 专用 registerParallelCompletion 承接真实时序职责：先注册 required-failure，再在既有 DELEGATION_EXECUTOR 异步通知；取消回调不通知。已有 CompletionEvents 继续负责去重、补发和关闭，完成事件先于 delegation_end。不新增 executor、服务、依赖、权限或数据库结构。

## 实际验证

1. 真实 tracker、真实 child relay、根 emitter latch：旧实现 timeout/failfast **2/2 RED**，明确失败于“根通知释放前两个模型 Disposable 必须停止”。首次测试重载推断编译错误修正为显式 String；编译失败单独保留，不作为业务 RED。
2. 注册算法按原逻辑提取后的 completedFuture required/optional **2/2 RED**：同步回调阻塞注册返回，required 分支还阻塞失败信号；不是概率调度或 sleep。改为异步后通过。
3. 首次真实链修复及旧完成事件合同 **7/7**；最终 **21 类143项，0失败/错误/跳过**。包括 Delegate*、ChatStreamTracker*、ToolExecutionExecutorCancellation、WebChatStopStream 和两个架构测试。直接 Tracker 新合同覆盖普通/静默取消、无活跃运行的持久意图、发布持久意图失败仍执行取消并抛原异常。
4. `mvn -B -Dquality.base=HEAD spotless:apply` 是显式开发格式化。Tracker 的原文件格式产生较大 diff；JDK21 JavacTask 比较证明除取消方法集合外，其他字段/方法/嵌套类型/注解 AST 一致；DelegateAgentTool 仅 imports、delegateParallel 与新注册方法 AST 改变。
5. 初始 dev `ocm4__6g` 与代码后 `5n3_s84m` 均 SCAN_PASS/submission_ready=false。源码 diff whitespace 检查通过。最终文档树门禁、commit/push、远端 CI 另在 PR #5 / 本机交付记录按实际身份回读，不能由本文预宣称通过。
6. 独立只读审阅 `sse_boundary_review`：COMMENT，无本批新增阻断；保留旧断言，技术审核不替代维护人/QA。Java LSP 与 ast-grep NOT_RUN（不可用），编译和 Javac AST 比较是独立实际证据。

回归命令（JDK21、仓库根）：

```sh
mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full '-Dtest=Delegate*Test,ChatStreamTracker*Test,ToolExecutionExecutorCancellationTest,WebChatStopStreamTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false test
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

45 文件证据包 `delegation-sse-cancellation`，SHA-256 `7113d5cf26db326b1f1fad4c12a3a2e25e8a434b2c443fd59a9cf13b37ffa7eb`；本机 `~/.codex/artifacts/mateclaw/aq-cumulative-20261006-fcdteqlz/` 保存 RED/首轮GREEN/最终实际XML/源码/AST脚本和记录。不宣称此本机包已随仓库分发。

## 限制与回退

修复的是“批次取消绕过同步 phase relay”及回调注册不内联输送。目标 child 自身 state.lock、事件监听器、hook/dispose 仍可同步等待；永久根 SSE 阻塞仍可拖延 end/relay flush，未建立通用有界输送。既有持久 Stop 异常仍可在调用方中断后续兄弟与清理，独立审阅确认 HEAD 已存在；本片不声称所有异常取消路径完成。

真实网络/模型/性能/业务QA及正式46AC NOT_RUN；不把 latch 夹具当生产验收。回退仅恢复两类原取消/通知实现，会重现已证实风险，无数据库回退；保留此前迁移和 UI 修复。
