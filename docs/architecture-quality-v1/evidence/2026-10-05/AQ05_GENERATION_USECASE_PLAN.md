# AQ05 生成与取消应用用例拆分计划

起点 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `sxhgn_i9` SCAN_PASS、submission_ready=false，输入工作树 `80ce22dbfa118a622793e7b2957de66f16217275a54e16fbff546d3312e44a34`。保留既有 WIP。

1. 先刻画取消预留/成功/失败清理、授权拒绝、非 RUNNING 状态、操作号兼容；加强真实 HTTP/H2 的任务先提交后排队、旧请求回放、异请求冲突。原实现先跑通过，再搬移代码。
2. 将 Controller 的生成/取消/员工查询用例整体移入 PresalesGenerationService；Controller 只做 HTTP 映射和 R 包装。Generate/Cancel 请求记录移入 PresalesDtos，保持字段顺序、严格版本反序列化及 hash 字节。
3. 新应用服务复用现有 PresalesService/Access/ContextProvider/EmployeeRuntime/Coordinator，单向依赖且不反向依赖 Controller。沿用模块条件。应用用例不加外层事务，command 的短事务先返回，再 enqueue；取消仍先预留，持久失败清理预留。
4. 不变更权限、来源、回执/hash 算法、Schema、Flyway、线程调度、API 路径或错误码。项目响应仍是 ObjectNode；完整 DTO/对象修订迁移属于后续工作。仅删除无人读取的 skill 名称映射值，保留 S1-S8 精确允许集。
5. 跑相关 Java 回归、实际 ArchUnit、Spotless 与 dev；独立审阅测试和边界。记录实际 checked tree 与未测范围。不得将本地工程结果当作维护人批准、业务签收或远端强制。

涉及：GenerationController、新 GenerationService、Dtos、GenerationControllerTest、VersionInputContractTest，以及本片证据。无新依赖。回退本片可恢复原 Controller 编排及 DTO 位置，不需要数据回退。进程运行时不修改仓库。完整提交门禁完成前不提交。
