# AC09 五角色跨层政策证据

范围：在源码 `8d2c8b2a68c3d63280f79e8ea21623fbe2223b7b` 上回读既有实现和断言；无新增权限政策。选择同一条业务链：发起员工生成 → 后台执行 → `SAVE_AI_TASK` 接纳候选。下表“允许”仅表示角色门槛通过；当前账号、Workspace、员工、来源、版本、活动 attempt、取消和归档检查仍独立生效。

| 角色 | UI canWrite/生成入口 | HTTP generate（member） | 后台执行/候选接纳（member） | 人工批准（admin） |
|---|---|---|---|---|
| viewer | 拒绝 | 拒绝 | 拒绝 | 拒绝 |
| member | 允许 | 允许 | 允许，重新校验 | 拒绝 |
| admin | 允许 | 允许 | 允许，重新校验 | 允许 |
| owner（Workspace角色） | 允许 | 允许 | 允许，重新校验 | 允许 |
| system admin | 允许 | 允许 | 允许，仍校验活动账号和Workspace | 允许 |

## 同一政策的实际消费者

- `PresalesQueryDtoContractTest.capabilitiesKeepRolesAndExactBooleans` 对五角色真实 HTTP capability 断言：canWrite 除 viewer 外为 true；canApprove 仅 admin/owner/global 为 true。SemanticHttpFixture 的 global 是系统管理员账号。
- `PresalesWorkbench.vue` 消费服务端 capability；`usePresalesExecutionSession.ts` 的 openGeneration/generate 都检查 canGenerate。`executionSession.test.ts` 的 readonly 分支断言 employees/generate 均未调用，正向分支断言确切请求和结果接纳；`presalesWorkbench.test.ts` 保留页面实际按钮消费断言。
- `PresalesGenerationController` 调用 `PresalesGenerationService`，其 generate 开始处 `access.require(scope,"member")`；随后保存 `SAVE_AI_TASK` 再排队。
- `PresalesExecutionRevalidationProvider.requireActive` 在读取项目之前 `access.requireActor(...,"member")`，并重验任务、员工 pin 与来源。`changedActorFailsBeforeProjectRead` 证明失败时不读项目。
- `PresalesService.saveEmployeeTask` 只允许 `SAVE_AI_TASK`；prepareCommand 对其要求 member，并在授权锁后调用 requireLockedActor。`PresalesTaskAcceptance` 仍复验运行身份与来源，候选为 UNTRUSTED_DRAFT。
- `PresalesAccess` 当前主体、绑定主体与加锁主体均检查活动账号/Workspace；普通账号共用 `WorkspaceAccessService.roleLevel` 的 viewer/member/admin/owner=1/2/3/4。系统管理员仅绕过成员角色等级，不绕过账号/Workspace有效性。

## 回归与拒绝证据

- `PresalesAccessPolicyTest.currentAndBoundActorsShareExactRoleMatrixWithoutOwnerBypass`：在 admin 门槛枚举普通角色、未知/大小写别名，并在 owner 门槛验证 system admin 的当前主体/绑定主体；member 门槛矩阵由相同 roleLevel 与真实入口调用关系组合证明，不称该单测直接枚举 member 门槛。
- `PresalesRuntimeTransactionIntegrationTest.invalidExecutionIsRejectedBeforeExternalCalls`：VIEWER 拒绝且无外部模型调用。
- `resultProducedBeforeAuthorityOrTaskChangedCannotOverwriteDurableState`：角色撤回后结果拒收，项目/回执/修订无覆盖。
- `realRuntimeRejectsRevocationCommittedWhileResultWaitedForAuthority`：等待授权锁期间降权，结果仍拒收。
- `resultCommitsInIndependentReadCommittedTransactionDespiteCallerRollback`：member 的候选接纳正例。
- 人工批准由 `APPROVE_BASELINE/APPROVE_RELEASE/PUBLISH_RELEASE` 的 admin 门槛控制；`PresalesIntegrationTest.g1RequiresAcceptedFactsAndReleasePublishesExactBytes` 有 member 403 与 owner 200。后台只接纳候选，不存在后台自批 API，不能把后台批准写为允许。
- 投标项目 owner 的业务例外保留在 `BiddingAccess` 与 `BiddingAccessPolicyTest.projectOwnerApprovalRemainsSeparateFromWorkspaceRole`，不将售前与投标批准政策强行统一。

## 已执行证据与边界

精确提交门禁 `yei03eyr` 的 checked tree 为 `7e5243c40d7cb49113e6f137b40b6530ea7f5642`，PASS 且 submission_ready=true；其后 HEAD8d2c8b2a 仅改文档。该门禁实际运行 Java6780/UI1262。命名Java套件为 QueryDto8、AccessPolicy5、ExecutionRevalidationProvider5、RuntimeTransaction43、TaskAcceptance17、PresalesIntegration8、BiddingAccessPolicy7，均零失败/错误/跳过。报告及原始日志摘要已重新读回；归档见 `delegation-sse-cancellation-commit`。

本项通过“服务端五角色真实 HTTP capability + 共用角色策略与各入口调用关系 + UI正反例 + 真实运行/接纳撤权回归”组合证明，没有声称新增执行了15条端到端用例。未用本次文档整理替代正式维护人/QA签收。AC09工程分类可转 ENGINEERING_EVIDENCE_COMPLETE_PENDING_SIGNOFF，正式台账保持 NOT_RUN。

独立只读技术复核：`ac07_cached_source_boundary` 核对原AC09、11个源码摘要、实际门禁身份及命名套件日志，确认上述组合证据相称，无需为矩阵格式新增功能；该技术复核不替代正式签收。
