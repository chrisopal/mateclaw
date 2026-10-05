# AQ05 / AQ01-AC05 启动禁用语义的来源读取工程验证

新增启动组合回归通过，未提交；完整 AC05 尚未完成。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`。实际最终 WIP 身份及 task ID 见末尾门禁归档，不用 HEAD tree 代替。原 checkout 未操作，此前 WIP 保留。

## 已补齐的证据

新增 `PresalesDisabledStartupReadContractTest.java` 八项测试，沿用现有 SemanticHttpFixture 的定向 Spring、Flyway、JWT、Workspace 及真实来源端口，启动时设置 semantic=false、presales=true。Wiki/PAT/I18n 既有 mocks 保留。历史数据以实际迁移后的表约束种入合成冻结项目、KB/raw、ontology/revision/graph/snapshot/evidence；这是历史读取夹具，不是真实旧数据或发布流程验收。

实质断言包括：启动时 query/graph/statement beans 缺失、只读来源 bean 存在及状态 API；原字节下载/预览和 latest/指定 handoff 一致；fit-only 来源撤回、删除、exclusion、小写 kind 各自四读拒绝、恢复后四读成功；当前员工绑定/停用/wiki_disabled、Workspace/角色约束；新候选 SEMANTIC_DISABLED 409；坏字节与 digest 同时替换仍拒绝；不完整冻结历史沿用原实时 gate。每种读取均核对 project body/version、artifact、revision 及本 Workspace operation receipts 不变。

本片未改生产代码、既有测试、迁移、门禁或依赖；32 份相关生产源码与前批真实 MySQL 指纹逐份一致。新增测试自动由既有 `Presales*` 测试映射发现，不修改 CI 路径或跳过规则。

## 实际运行

```sh
mvn -B -pl mateclaw-server -am '-Dtest=PresalesDisabledStartupReadContractTest,SemanticDisabledTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

JDK21 定向 Maven exit0：实际 Running 两类，新增8项和既有禁用状态1项，共9/9成功，无失败、错误、跳过。正式 base Spotless exit0，显式 scoped apply 与只读 check 分开。没有重复执行未变的全量回归，也不把前批400项改称本批运行。

MySQL 8.0.46 两个独立空库各9/9成功：原八项合同完整保留，仅外部夹具类名、DynamicPropertySource 及额外 migration identity/validate 案例不同。每库实际212条 V1→V219 Flyway history 全部成功、无BASELINE、pending0、validate通过、request_hash宽67。首库创建配置为utf8mb4_bin，不能由此推断所有列排序规则。此前“不区分大小写比较”的表述超出首库证据，已明确纠正后补跑第二库。

第二库实际 identity 断言 `mate_semantic_source_snapshot.source_kind` 列为 `utf8mb4_0900_ai_ci`，同一小写 kind 行在 SQL 同时匹配 `wiki_raw` 和 `WIKI_RAW` 且 count=1；原负例仍四读404，恢复后200。因此额外运行才证明 SQL CI 命中后 Java 严格 WIKI_RAW 校验仍拒绝。不会用源码推理代替这一运行证据。

每次 MySQL 都记录247份源码、4091个绝对classpath目录class、4个外部class执行前后摘要；过滤空/相对classpath库存，库存仍非全部类已加载coverage。自己的容器按精确ID清理exit0，独立inspect确认不存在，凭证文件删除；归档日志JWT/未脱敏password形态为0。

独立原生审阅 `/root/artifact_read_review` 给出 COMMENT：回读源码、实际XML/两库case/CI列与SQL断言、档案双摘要及当前指纹，未发现新增实质问题。此前指出首库CI证据不足，已通过追加实测解决。该意见不是维护人批准；Java LSP NOT_RUN。

## 失败与剩余范围

前两轮夹具初始化失败分别是 raw.title 缺失（8项7 errors）和员工KB绑定id缺失（8项1 error），均按真实已迁移schema补齐；未改生产逻辑或降低断言，日志/XML/当时源码保留，不当产品RED或通过证明。早期脚本在格式化后的文本定位不匹配，未写入；改用确切上下文补字段。

这是一个定向 Spring 启动组合，完整 MateClawApplication、普通聊天及全部模块组合、完整 AC01–46、浏览器/客户、真实历史发布/升级/备份恢复/Office、Kingbase 均 NOT_RUN。完整 commit 门禁 NOT_RUN、submission_ready=false；维护人批准和远端required CI NOT_VERIFIED。完整目标仍进行中，不按本片重定义为完成。

回退只需删除本片新增测试及文档，生产、schema、旧迁移、已发布字节不变。下一出口继续售前用例职责拆分及其余模块/权限组合；hash旧回执政策尚待选择，不在本片整合。

证据：[计划](AQ05_DISABLED_STARTUP_PLAN.md)、[机器结果](disabled-startup-results.json)、[最终门禁与WIP身份](disabled-startup-tests/final-gate.report.json.gz)、[架构静态扫描](disabled-startup-tests/final-architecture.report.json.gz)、[归档清单](disabled-startup-tests/final-manifest.json.gz)。
