# T12 隔离运行验收（2026-09-12）

结论：10 个精选现有测试已实际重新编译、执行并通过；既有 T11 备份的隔离副本成功打开、导出 SQL、恢复到新 H2 文件，39 张语义表逐行规范串排序后的 SHA-256 与数量完全一致。未证明生产容量达标，也未完成 MySQL/Kingbase 原生迁移验收。

## 本次执行与边界

从仓库根运行 `python3 output/ui-acceptance/2026-09-12-t12/runtime/run-safe.py`；结果见 `safe-results.txt`。脚本复用本机 JDK21 与现有 surefire XML 中依赖 classpath，直接 javac 编译三个现有测试类及本目录两个 runner；反射调用指定测试与 BeforeEach，断言仍使用 JUnit。**不是完整 JUnit/Maven 测试生命周期**，产品类来自现有 target/classes，因此父任务统一 Maven 检查负责源码与构建一致性。

脚本不调用网络或模型，不启停服务，不打开原 `data/semantic-owl-runtime/database.mv.db`，仅复制已有 `backups/database-t11-verified.mv.db`。恢复过程使用独立随机目录，退出后删除含业务数据的 SQL 和副本，只保留计数与摘要。既有备份复核 SHA-256：`4e2ab82816607830a79dd81d982eb0d1c8b3650c0b539d7efb506a28df409f4a`，前后不变。恢复验证覆盖 SQL 序列化往返与全语义表内容，未进行原库故障切换、全应用登录回归或备份时点之后的数据恢复。

## 已测证据

| 验收项 | 实际验证 | 源码定位 |
|---|---|---|
| 任务中断恢复 | 过期租约被新代次接管，旧 worker 无法发布，新 worker 成功完成 | `mateclaw-server/src/test/java/vip/mate/semantic/SemanticExtractionLeaseIntegrationTest.java:40`；实现 `extraction/JdbcExtractionRepository.java:47-66` |
| 取消晚到结果 | 取消后拒绝旧执行结果 | 同测试文件 :41 |
| 抽取并发门限 | 两个 workspace，每 workspace 2、全局 4，第 5 claim 拒绝 | 同测试文件 :42；这是容量门限，不是 HTTP QPS 限流 |
| 重试耗尽 | 第三次过期后 FAILED/ATTEMPT_LIMIT，不永久悬挂 | 同测试文件 :60 |
| 长文档 | 100000 个 emoji 完整分成18块，逐块验证码点覆盖、300重叠；100001码点拒绝；空/6000/6001边界 | `mateclaw-semantic-application/src/test/java/vip/mate/semantic/application/extraction/SourceChunkerTest.java:5`；实现 SourceChunker.java:10-29 |
| 推理时限 | 真子进程超时返回 TIMEOUT，并验证 PID 退出 | `mateclaw-semantic-owl/src/test/java/vip/mate/semantic/owl/HermitReasoningAdapterTest.java:271` |
| 推理内存预算 | 16MB 子 JVM 实际分配64MB触发 OOM；两次返回 RESOURCE_EXHAUSTED，再验证普通 exit3 为 FAILED | 同测试文件 :297 |
| 推理并发预算 | 单槽被占用时第二个请求 RESOURCE_EXHAUSTED，第一个超时退出 | 同测试文件 :331 |
| 备份恢复 | 副本 SCRIPT/RUNSCRIPT 新库往返，39表数量/内容摘要一致 | 本目录 `SafeProbe.java`、`safe-results.txt` |

推理隔离实现见 `mateclaw-semantic-owl/src/main/java/vip/mate/semantic/owl/HermitReasoningWorker.java:108-186`（信号量、子 JVM `-Xmx`、超时销毁），默认配置见 `mateclaw-server/src/main/java/vip/mate/semantic/reasoning/SemanticReasoningProperties.java:8-11`（30秒/512MB/1并发）。这里测试故障预算机制，不证明任何给定真实本体能在默认预算内完成。

## 迁移与尚未覆盖

- `migration-inventory.json` 记录 H2/MySQL/Kingbase 语义迁移文件与 SHA-256，包含 V210。**存在脚本不等于原生执行成功**。本次仅抽取测试实际执行 H2 V197；未重新执行完整空库升级/跨版本迁移链。
- `environment.txt`：有 mysql 客户端，无 PATH 中 mysqld/ksql/kingbase；常用端口无监听；本地 Docker daemon 不可达。未安装或启动数据库。MySQL/Kingbase 原生验证为缺口。
- 可复用 `scripts/semantic-owl-reset/run-mysql-migration-rehearsal.sh`，但现有 README 的范围仅到 V207，不能用它声称 V208-V210 全链覆盖。Kingbase 需要匹配驱动、隔离实例与当前迁移链后另行执行。
- 导入恢复调度见 `mateclaw-server/src/main/java/vip/mate/semantic/source/ImportJobRecovery.java:24-36`（15秒初始/30秒扫描、每批10），本次未做真实进程中断。源变化恢复用例见 `SemanticSourceChangeIntegrationTest.java:93`，留父任务完整套件。
- 上游模型429映射见 `extraction/MateClawModelAdapter.java:73`；本次不启用模型，未验证供应商429或 API QPS/突发流量。
- 上下文 token 预算见 `query/SemanticContextService.java:112-113,259,279`：512..12000估算 token，单项/整个封套超预算明确拒绝；本次未调用上下文 API。
- 本次无18109停机重启、真实断电/kill、原库恢复、容量压测或 RPO/RTO 承诺。生产文档大小、并发用户数、吞吐及延迟目标尚无用户输入，不能判定容量达标。
