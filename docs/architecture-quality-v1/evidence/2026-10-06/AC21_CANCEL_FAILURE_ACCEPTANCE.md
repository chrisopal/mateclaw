# AC21 单个持久化 Stop 异常不再遗留并行子任务

Task：AQ-01 / AC-21。实现基线 HEAD `608d525a5f6ad6f966cc3ebc604c62408be56070`，dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。开始时工作树干净；仅修改 DelegateAgentTool、原并行取消回归及本批证据/进度记录。

## 问题、职责与兼容

ChatStreamTracker 在持久化 Stop 发布失败后仍执行本地取消，然后重新抛出异常。原并行结果收集未隔离该异常，导致当前 future 取消、后续兄弟任务停止、relay/registry 清理和 delegation_end 被跳过。

现在每个子任务独立捕获 RuntimeException，在 finally 取消它的 future，并继续处理后续任务。失败的 Stop 使用现有 error outcome，固定消息不包含内部异常正文，也不误命中 ChildResult.ofError 的 timeout 分类。不把持久化失败伪装成成功取消。真实本地取消仍由原 tracker 负责，不复制状态或引入新的执行器。

公共 API、成功结果顺序、权限/Workspace/来源政策、事务、数据库和已发布成果均未改变。没有新增依赖、迁移或门禁规则，没有删减原断言。

## 回归证据

- 初始 dev `s_c8wl_9`：SCAN_PASS；这不是提交许可。
- RED：生产代码未修复时，原类 6 项中新增两场景失败，4 项既有控制通过，0 errors / 0 skipped。失败断言为一个持久化停止失败不能阻止后续兄弟的本地取消；不是夹具编译或超时造成的假 RED。
- GREEN：同一类 6/6 通过。新增参数化场景分别覆盖超时和必需任务失败触发 fail-fast，真实 tracker 的 Stop publisher 对 B 抛出异常，C 仍收到停止。
- 可观察断言包括 B/C 的本地 hook/disposable、停止标记、全部 relay 停止、registry 清空、如实 error 结果、单次 delegation_end，以及迟到完成不会产生第二次结束/重新注册。测试不为了访问 private future map 增加生产接口；finally 的 future 取消由源码和独立审核补充验证。
- 扩展回归：**151 项，0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。包括委派、上下文、tracker、registry、用量累计及真实生产字节码 WorkbenchArchitectureTest。
- 显式 Spotless 格式化通过；JDK21 JavacTask 对两个 Java 文件格式化前后 AST 对照均为 `Changed AST: []`。实际 Maven javac 编译通过；Java LSP 不可用，不宣称 LSP 已通过。

仓库根实际命令（JAVA_HOME 为已安装 Temurin 21）：

```sh
mvn -B -pl mateclaw-server -am '-Dtest=DelegateParallelSseCancellationTest' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -Dquality.base=HEAD spotless:apply
mvn -B -pl mateclaw-server -am '-Dtest=Delegate*Test,DelegationContextTest,ChatStreamTracker*Test,SubagentRegistryTest,DelegatedUsageAccumulatorTest,WorkbenchArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false test
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

本机日志：`/tmp/ac21-stop-red.log`、`/tmp/ac21-stop-green.log`、`/tmp/ac21-stop-regression.log`、`/tmp/ac21-stop-format-ast.log`；RED/GREEN XML 在 `/tmp/ac21-stop-evidence/`。扩展日志 SHA-256：`bf592b0ee119a23322c42d6ae3db46309c255d18a78f60cb155647cf5a4be5c3`。

独立只读技术审核 `ac21_stop_failure_review`：COMMENT，无本批阻断；核验真实 RED/GREEN/151 项、编译、AST 及逐子任务 finally/error 语义。生产文件 SHA-256 `cc162d42d5e7b27b0819175966efff63f9ad34351bd020c2400dbe119c512fcf`，测试 `31b6263d964e2a6f82376838b6acf63c86fc73f0d52892988a5d8d3b2b99bd66`。技术审核不替代维护人/QA 签收。精确暂存 tree 的门禁、正常提交钩子、推送与远端 CI 以实际交付记录追加，不在本文预先宣称通过。

## 剩余边界与回退

本修复针对抛出的停止异常；任意阻塞的锁、hook/dispose 和永久阻塞的最终 SSE flush 不在此次证明范围内。坏历史记录/版本恢复仍单独核查，AC21 保持 IMPLEMENTATION_GAP，正式 NOT_RUN 不变。没有执行真实模型、生产数据或客户验收。

这是无迁移的代码修复，可回退本批生产变更及对应新增回归；回退会重开单子任务异常中断整批清理的缺陷，不建议单独撤掉错误结果或 finally。
