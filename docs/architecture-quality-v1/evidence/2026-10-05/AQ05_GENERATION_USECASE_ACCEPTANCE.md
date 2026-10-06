# AQ05 生成与取消应用用例拆分：工程验证

## 范围与结果

Controller 从实际保存的 201 行变为 42 行，仅负责 HTTP 输入及 R 包装。新 PresalesGenerationService 165 行统一员工查询、生成、取消用例；六个原依赖及执行顺序移入该服务。删除无消费者的 Skill 名称映射值，保留 S1–S8 精确允许集。Generate/Cancel 移到 PresalesDtos，版本反序列化、字段次序、wire/hash 保持。本片不是净行数缩减，也不是全部售前结构整改完成。

公共依赖单向：Controller → GenerationService → 既有应用/授权/执行能力；新服务不引用 Controller、HTTP R 或持久化端口。与 Controller 使用相同 presales.enabled 条件。不添加外层事务：Service.command 自有短事务返回后才 enqueue，后台执行保持原实现。取消仍先预留，持久失败清理预留并重抛原异常。没有新依赖/表/迁移/配置、权限扩大或 hash 算法变更。

## 行为保护与验证

- 原生产实现先跑 2 类 61/61，包括新增 11 项：取消四种 operationId 输入、持久失败清理、三类终态拒绝、超长操作号、未授权拒绝、真实 HTTP/H2 生成提交/排队/回放/冲突。
- HTTP 生成在 enqueue 回调中验证无活动事务，并通过独立 JDBC 连接看到 version=2、RUNNING、固定 contextSnapshot version=2；回执已存在。原请求重试不触发第二次模型准备或排队，异目标请求409且三表事实不变。
- 迁移后 51 类 555 项：554 通过、0 失败/错误、1 既有 PresalesPresentationCompilerTest 跳过。原有断言未删除。新 DTO 保持原 JSON 串 golden；原严格版本和容量边界同时复跑。
- JDK21 编译与实际 WorkbenchArchitectureTest/SemanticCoreArchitectureTest 通过；Spotless 使用正式 quality.base=origin/dev 通过；git diff --check 通过。没有新增跳过或修改控制面来放行。
- 初始 dev sxhgn_i9、切片 dev fvyzbcmh 为 SCAN_PASS、submission_ready=false。最终身份见 final-report.gz / final-manifest.gz，不把 dev 当作提交检查。
- 独立 native reviewer：COMMENT、零阻断发现；比对的是 /tmp 中本片前源码而不是整个 HEAD 差异。Java LSP 不支持此项目，ast-grep 不可用，均不计 PASS；使用 Java21 编译、实际 ArchUnit 与定向源码审阅。COMMENT 不替代独立维护人批准。

## 可复核命令与文件

所有命令在 `/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw` 执行，Maven 使用本机 JDK21。

```sh
mvn -B -pl mateclaw-server -am -Dtest=PresalesGenerationControllerTest,PresalesVersionInputContractTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

变化文件：PresalesGenerationController、PresalesGenerationService、PresalesDtos、PresalesGenerationControllerTest、PresalesVersionInputContractTest；计划/本文/results/README 与压缩证据同步。精确源码路径、SHA256、实际执行类和计数见 generation-usecase-results.json。每次 Maven 的实际 Running 类 XML 在下一次 Maven 前归档，包含原实现源码和最终5份源码；日志移除JWT及测试密码后同时保存压缩与解压 SHA。格式化为单独显式动作，检查命令不修文件。

## 限制、后续与回退

本片使用真实 H2/Flyway、Service、授权与 HTTP 夹具，模型/context/coordinator mocked；排队断言不证明真实后台调度/模型调用。未重新执行 MySQL/Kingbase、完整应用开关组合、普通聊天、浏览器或 Office；不能借前批方言证据覆盖本批。46正式 AC 均 NOT_RUN，远端required CI未验证，完整commit门禁未跑，未提交/推送。

项目/任务响应仍是 ObjectNode、员工查询仍是既有 Map 列表；稳定 DTO、其他应用用例、V2对象/依赖/attempt与single-writer迁移、历史回执hash策略及真实历史恢复仍需继续。已有版本耗尽RUNNING限制未扩大也未解决。新bean的全应用装配及外部Java调用方的嵌套DTO迁移需后续整体验证。

本片回退恢复原 Controller 编排及嵌套请求记录，HTTP/数据无需迁移；不得回滚或删除前批版本、来源、冻结成果安全修复。设计/控制面仍需维护人审核，不能用本工程记录关闭业务验收。
