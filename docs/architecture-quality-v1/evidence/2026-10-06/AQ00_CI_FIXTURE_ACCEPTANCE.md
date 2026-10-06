# AQ-00 远端测试生命周期整改证据

基线 HEAD `692c212a8124029d05ea0a9cb5cb2e97ee500f2b`。远端 [37362577679](https://github.com/chrisopal/mateclaw/actions/runs/37362577679) 在 merge `bbf8fcc85f2a1352e81dcde983e05e9760c3357b` 执行失败，Java 32 errors；其余工程步骤通过。完整失败报告保留，不把本地修复结果覆盖为远端通过。

## 修复与行为边界

四个售前数据库 fixture 改用 try-with-resources 的 JDBC Statement 执行 H2 SHUTDOWN。Spring JdbcTemplate 在 DEBUG 下执行后的 warning 探测会访问已经关闭的 H2；直接关闭避免了这一次多余读取，不吞异常、不关闭 DEBUG。保留唯一内存库、SqlListing 的 external disposable URL 校验及 ownsTables 清理围栏。生产服务、数据库迁移、查询和授权断言不变。

DelegateAgentToolTest 的 fail-fast 用例用 latch 等待慢任务实际开始，再触发 required failure；finally 释放慢任务，并等待两个任务抵达 usage 更新后的 complete 清理点，再退出测试。原 2500ms、cancelled=1、已取消断言及 strict Mockito 保留，并断言慢任务已启动/调用。测试现在明确覆盖运行中的 sibling 取消，不假设 executor 启动顺序。该文件同时经现有 Spotless 显式格式化，其他方法只有格式变化。

## 实际验证

JDK 21、项目原有 H2/Spring/Mockito 依赖，无新增依赖。命令从隔离 worktree 执行：

```sh
mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full \
  -Dtest=PresalesSqlListingTest,PresalesArtifactRepositoryTest,PresalesProjectRepositoryTest,PresalesSourceAuthorizationTest,DelegateAgentToolTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlogback.configurationFile=/tmp/ci-lifecycle-debug-logback.xml test
```

- RED：原 SqlListing 31 errors；相同根因的另三类 18 errors，全部 teardown/getWarnings/90121。
- GREEN：相同 DEBUG 配置下 64 tests，0 failures/errors/skipped（49 SQL + 15 Delegate）。
- 调度回归：fail-fast 方法以 `-DargLine=-XX:ActiveProcessorCount=1` 连续运行 5 次，每次 1 test、0 failures/errors/skipped。
- 初始 dev `p5dl_zv8`，修复后 `z80ivm72` / `xr1j5va0`，均 SCAN_PASS，base origin/dev；不是提交许可。
- `mvn -B -pl mateclaw-server -Dquality.base=HEAD spotless:apply` 是显式开发格式化操作；`git diff --check` 通过。完整格式/编译/测试由正常提交门禁验证。

[ci-fixture](ci-fixture/) 保存 21 个日志/报告的脱敏归档和逐文件 manifest。归档 SHA-256 `a50be6ae889123f5645a18276513ab3e0628e6df205d62481bb6967e3de25d89`；32 处 JWT/测试密码脱敏，manifest 区分 original_sha256 和 archived_sha256；已重读压缩包逐项验证。DEBUG 配置随证据保存，可解压后改为实际路径复跑。

提交前正常 pre-commit 必须 exit 0、submission_ready=true，推送前正常 pre-push 检查完整推送范围；确切任务/tree/远端 run 后补到 PR #5，不提前宣称通过。真实 MySQL/Kingbase、生产环境、46 项正式 AC、维护人审批、分支 required enforcement 本批 NOT_RUN/未完成。

审阅初版提出异步退出等待和归档 Git 跟踪两个问题：已补充清理完成 latch，最终版再次通过 64 项 DEBUG 回归与 5 次单 CPU 回归；归档使用精确路径显式 `git add -f`，未改忽略规则。初版与最终版证据均保留，未覆盖原始失败记录。

独立复审 `/root/ci_fixture_review`：COMMENT，无剩余实现缺陷；要求最终完整暂存后再运行门禁。已重新暂存最终测试、文档、manifest 和归档并核对工作树与 index 一致。Java LSP/AST 不可用，未宣称静态 IDE 检查通过；实际 Java 编译与测试证据如上。这不是维护人批准。
