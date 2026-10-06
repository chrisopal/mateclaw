# AQ05 售前内部拒绝类型与 HTTP 错误 DTO 工程验证

本批错误边界整改完成，未提交。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；最终 WIP 身份见 final-gate.report.json.gz。旧 WIP 保留。

## 修改文件与边界

- 新增包内 `PresalesRejected`，从 `PresalesProjectItems` 中移出原 status/code/message 异常；删除原嵌套类。
- `PresalesArtifactReader`、`PresalesCommandPayload`、`PresalesSolutionPolicy`、`PresalesProjectItems`、`PresalesService` 使用独立类型，成果读取和校验不再为共享错误类型依赖条目修订工具。
- `PresalesDtos` 新增仅含 code 的 ErrorData；`PresalesExceptionHandler` 使用 `R<ErrorData>` 替换 Map，明确 HTTP 错误数据形状。
- 增加 `PresalesErrorContractTest` 五项；`PresalesCommandPayloadTest`、`PresalesSolutionPolicyTest`、`PresalesProjectItemsTest` 仅机械更名异常类型并格式化，保留原断言。

遵循 ADR-AQ-020，公开 Service 仍将内部拒绝转换为原 SemanticApiException，协调器/运行时原捕获契约不变。没有把新类型推广为全项目通用异常，也没有合并 SourceAuthorization 的独立 Denied。新领域类构造/null/status/code/message 语义与旧嵌套类相同；ErrorData 仅在 HTTP adapter 约束 code 非 null，保留原 Map.of 的拒绝行为。

Handler 的作用范围、Order、异常匹配、HTTP status、业务 code、msg 及唯一 data.code 字段不变；semantic fieldErrors、模型 rejected payload 和数据库异常详情仍不对外输出。Handler 的 Java 泛型更明确，仓库内未发现依赖旧 `R<Object>` 显式赋值的调用方；公开业务服务的异常类型没有改动。没有新 bean、库、SQL、schema、权限、事务、回执或已发布成果变化。

## 实际验证

所有 Maven 使用 JDK21。

```sh
# 原实现先通过：7 个实际运行类，89/89
mvn -B -pl mateclaw-server -am '-Dtest=PresalesErrorContractTest,PresalesCommandPayloadTest,PresalesSolutionPolicyTest,PresalesProjectItemsTest,PresalesArtifactReadContractTest,PresalesCommandPayloadContractTest,PresalesSolutionPolicyContractTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
# 迁移后：50 个实际运行类，423 项，422 通过、1 既有 PPT skip
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

Maven 编译/测试和正式 base Spotless 均成功。新增合同在原生产实现通过后逐字节保持；三份旧纯模块测试经保留字符串内容的 token 对比，除异常类型名外无断言变化。所有生产/售前测试源码已无旧嵌套引用。实际编译目录中旧 `PresalesProjectItems$Rejected.class` 不存在，新异常和 ErrorData class 存在，没有旧类残留冒充完整迁移。

新合同使用实际 advice 的 standalone MockMvc，验证各业务状态、未知 code、原始消息/空消息、敏感内容不输出、DuplicateKey 冲突、坏 JSON、缺 header、null code 原拒绝语义。它不含 Workspace/授权基础设施；既有真实 Service/定向 Spring HTTP/H2/事务/来源/启动缺 bean 回归本轮另外执行，不能用 standalone 测试替代它们。仅按当次 Running 类归档 XML，未混入旧报告。

独立原生审阅 `/root/artifact_read_review` 对计划、实际机械迁移、HTTP 泛型边界和最终日志/XML/编译产物给出 COMMENT，无实质发现。Java LSP NOT_RUN；该意见不是维护人批准。dev 的 SCAN_PASS 仅用于本地增量扫描与门禁自测，完整 commit 门禁未运行。

## 剩余与回退

本批未重跑 MySQL/Kingbase，不将此前方言证据改称本次运行。完整应用/浏览器/客户验收、真实历史数据迁移与恢复、历史 Office 样本仍 NOT_RUN；46 正式 AC 未关闭。commit 门禁 NOT_RUN、submission_ready=false；维护人批准和远端 required CI NOT_VERIFIED。没有提交、推送、部署或管理操作。

回退恢复旧嵌套类/直接使用者和 Map handler，无数据库回退。剩余完整项目/命令 DTO、其余用例职责、旧 hash replay 政策和业务验收继续开放。保留语义异常适配出口是既有兼容选择，不代表全部跨域类型依赖已消除。

证据：[计划](AQ05_ERROR_BOUNDARY_PLAN.md)、[机器结果与文件摘要](error-boundary-results.json)、[最终门禁](error-boundary-tests/final-gate.report.json.gz)、[归档清单](error-boundary-tests/final-manifest.json.gz)。
