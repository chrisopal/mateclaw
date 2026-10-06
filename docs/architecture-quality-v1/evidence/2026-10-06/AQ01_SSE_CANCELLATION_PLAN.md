# 下一片：委派截止取消不等待 SSE 输送

实施计划；基线 HEAD 679092a86009681ea8d7ce02927370bc6a9bcfdf / tree bf38a8cf007176f6781d810705b0518b92a42272，起始工作树干净。先读真实代码与独立只读诊断 delegation_sse_exit；尚未执行 RED，不预先声明修复。

## 明确问题

DelegateAgentTool 顺序 requestStop；ChatStreamTracker requestStopLive 先同步广播 phase 后调用取消 hooks。child phase relay 转发 root delegation_progress；如果 root child_complete 的 emitter.send 在 state.lock 内阻塞，B hooks 未执行且 C stop 未发送。有限慢发送足以暴露 deadline 失效，不能用 mocked tracker 测试证明真实链通过。

future.whenComplete 同步注册存在已完成 future 内联通知分支，可在派发线程卡住并阻止后续派发及进入 timeout 等待。现有先等兄弟启动再完成 A 的夹具没有覆盖此分支。

## 验证先行

1. 真实 tracker/relay，root emitter 通过 latch 阻塞 A child_complete；timeout/fail-fast 均要求释放前 B/C 独立 stop 标志、hooks、dispose 已发生。
2. 控制已完成 future 在注册前完成，避免随机 sleep；通知未释放时其余任务仍启动并进入截止取消。
3. 保留 completion-before-end、optional 即时通知、通知异常不改变结果；独立 stopper 身份验证，finally 释放全部 latch，等待测试线程退出。

## 最小候选设计

使用现有 DELEGATION_EXECUTOR 异步通知，required-failure 判定先注册。批次截止取消使用不等待 SSE 的窄入口，复用现有取消意图/标记/hooks/dispose；保留默认用户 Stop 的通知行为。先写回归再选择必要实现，不建立新依赖/通用事件总线。

仅将 hooks 移到通知前仍不足：B 返回前仍阻塞 C。仅把 cleanup 移到 finally、换 RunHandle 广播或 closeSubscribers 也无法保证退出，既有 relay stopper 还同步 flush。

永久阻塞输送线程退出/end 必达仍未解决，需在实际规范下另验，不由本片声明。后续写入前须读取规则、dev、计划入库，完成 RED 后再改代码；检查运行期间保持源码冻结。

## 边界和回退

仅修改 DelegateAgentTool、ChatStreamTracker 及针对性测试。取消仍保留持久 Stop 意图、当前运行检查、hook/dispose；不改变来源、审批、模型调用、结果分类或数据库。回退恢复两类原取消/通知实现，会重现本片 RED；无数据迁移。测试包括原 Delegate*、Tracker* 及运行取消相关回归；dev、Spotless、独立技术审阅与精确树 commit/push、远端CI分别验证。真正网络永久卡死、性能/真实模型与正式46AC不由latch夹具证明。

## 当前实现与验收范围

初始 dev `ocm4__6g` SCAN_PASS。真实 Tracker/relay+根 emitter latch 的 timeout/failfast 2/2 RED；首修连同旧完成顺序合同7/7 GREEN。回调注册逻辑原样抽取到 ChildResult 专用 `registerParallelCompletion` 后，已完成 required/optional future 的旧同步算法2/2 RED；最终先注册 required-failure，再复用既有 executor 异步通知。不新增服务、依赖、executor 或泛型包装。

`requestStopWithoutNotification` 复用持久 Stop 发布及失败兜底，仅省略同步 `phase=interrupting` 广播；批次结果仍携带 timeout/cancelled。默认用户 Stop 与 cancelRun 的通知语义保持。新增直接合同验证两种通知选择、无活跃运行的持久意图、持久意图发布失败仍取消且抛原异常。

本片保证已复现的“根 SSE 锁经 child relay 阻塞兄弟停止”被修复。目标子会话本身的 state.lock、任意 hook/dispose/event listener 仍可能等待；不能称通用非阻塞取消。永久输送阻塞、end/relay flush 的有界退出不在这两处修复中假称完成。

现有 Tracker 源码须按项目 Spotless 格式化，diff含大量原文件格式变化；JDK21 JavacTask 比对确认仅取消方法集合变化，其他字段、方法、嵌套类型和注解 AST 一致。DelegateAgentTool仅 imports、delegateParallel 和新增注册方法 AST 变化。
