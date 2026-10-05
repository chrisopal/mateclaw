# AQ05 版本输入边界工程验证

本批已修复，未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`、HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`。实际最终 WIP 身份以 final-gate.report.json.gz 为准，既有未提交工作保留。

## 修改与兼容

- 新增包内 `PresalesExpectedVersion`，复用同一局部 JSON 整数解析规则。仅接受能精确装入现有 Java int 的 JSON 整数；缺失/null 留给原业务策略。
- `PresalesDtos.Create/Command`、`PresalesGenerationController.Generate` 绑定该解析器。`PresalesDtos.Update` 明确 PATCH 元数据，保留同一个完整 ObjectNode 作为原命令 payload；`PresalesController` 删除 asInt 转换，只读取 DTO。
- 新增 `PresalesVersionInputContractTest` 38 项；`PresalesDisabledStartupReadContractTest` 一处版本 fixture 从 asLong 改用原数字 JsonNode，所有既有断言保留。

小数（包括 1.0）、数字字符串、布尔、容器、超范围整数现在返回原有 400 INVALID_REQUEST/Malformed request。PATCH null 从旧版转换成 0 后冲突改为与缺失相同的 expectedVersion required。此为明确的输入兼容收紧，旧 malformed 请求也不再通过 coercion 重放。外部 Java 客户端若用全局 Long→String 配置生成版本字符串，需要改为发送 JSON 整数；未验证外部客户端。

内置 UI 的项目 version/expectedVersion 为 number，响应校验使用 Number.isSafeInteger，create 固定 0，命令/PATCH/generate 直接传当前版本；本批未运行真实浏览器。合法整数 DTO wire、原 operationId.asText 语义、PATCH 全部扩展字段与顺序、旧 Command 哈希、权限/CAS/事务保持。Service 字节未变，无新依赖、bean、SQL、schema 或全局 ObjectMapper 配置；不触碰待决 UTF16 hash replay 策略。

## 验证证据

所有 Maven 使用 JDK21。按每次日志的实际 Running 类读取/归档对应 Surefire XML，不混用陈旧结果。

```sh
# RED：3 类46项，23失败，0错误；新合同38项中的23项失败
mvn -B -pl mateclaw-server -am '-Dtest=PresalesVersionInputContractTest,PresalesCommandKindContractTest,PresalesProjectPersistenceContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 原生产实现下旧 Command/hash 对照：1/1
mvn -B -pl mateclaw-server -am '-Dtest=PresalesVersionInputContractTest#validCommandAndPatchRetainReplayConflictAndOriginalPayloadHash' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 最终回归：51 类461项，460通过、0失败、0错误、1既有PPT skip
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

RED 的实际 HTTP 证据包括小数/字符串请求返回 200、PATCH `4294967297`/`-4294967295` 截断后返回 version=2；生成坏版本到达 mock 员工依赖。状态断言失败后的数据库对比未执行，不将这些 RED 报成旧版数据库差异断言通过。

GREEN 新合同38/38：四入口非法版本拒绝后 project/revision/operation 全部事实不变；生成不触及 model/context/coordinator；合法创建/命令/PATCH 的回放、载荷改变冲突、过期冲突、角色拒绝保持。手工构造旧 Command 字段顺序和完整原 payload 计算旧 SHA256，与实际写入 receipt 的 request_hash 相等。命令/PATCH 缺失/null/负数由 HTTP 验证，Create/Generate 的全部业务版本条件没有新增穷尽 HTTP 验证；合法 DTO int 极值覆盖序列化。

首轮全回归461项中1失败，原因是启动禁用测试用 asLong 放入 Map，被现有 JacksonConfig Long→String 转成非法版本字符串。归档 before-disabled-test、after-first 日志/XML；仅改为原数字 JsonNode，保留 409、SEMANTIC_DISABLED 和无数据库变化三个断言。最终该类8/8。没有放宽生产解析或删除断言。

原生独立审阅 `/root/artifact_read_review` 对本批实现、哈希对照和夹具修正给出 COMMENT，无实质发现；Java LSP NOT_RUN，不替代维护人批准。编译、上述回归与正式 base Spotless 通过。初始 dev `c5h1h7ur`；最终 dev 任务ID、checked tree和结果见归档真实报告。

## 限制、剩余与回退

本片为定向 Spring MockMvc/真实 Service/Flyway/H2 验证，沿用 Wiki/PAT/I18n fixture mocks，生成执行依赖为 mock；无真实模型调用。本批 MySQL/Kingbase、完整应用/浏览器/历史数据/外部客户端 NOT_RUN。46 正式 AC 未关闭，完整 DTO/用例拆分、迁移恢复、真实业务验收仍待推进；commit 门禁 NOT_RUN、远端 required CI/维护人批准 NOT_VERIFIED，dev SCAN_PASS 不代表可提交。

没有提交、推送、部署或修改仓库管理设置。代码回退恢复四入口原声明/解析及删除局部解析器，无数据库回退；恢复旧解析会重引入已复现缺陷。int 版本耗尽、数据库异常历史值不在本片范围。

证据：[计划](AQ05_VERSION_INPUT_PLAN.md)、[机器结果与源码摘要](version-input-results.json)、[最终门禁](version-input-tests/final-gate.report.json.gz)、[归档清单](version-input-tests/final-manifest.json.gz)。
