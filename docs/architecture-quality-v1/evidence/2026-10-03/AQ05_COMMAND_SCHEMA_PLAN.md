# AQ-05：公开命令载荷的运行时形状边界

- 开始 HEAD：fc87d8eb413535fabac37650e1e82ca76368e460；base：ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93（origin/dev）；工作树干净。
- 初始 dev：exit 0 / SCAN_PASS；原始日志 `/tmp/mateclaw-query-next-initial.log`，不是提交通过。
- 需求：RULES R-07、ARCHITECTURE_SPEC 类型边界、AQ-05。当前公开16命令已有原始写 DTO，后端仍把数字/布尔等转换为文本且保留原节点，导致新写入响应无法通过客户端已声明字段解码。

## 修改范围与顺序

1. 先补真实 HTTP/H2 负例，验证数字文本/ID、非字符串引用、嵌套字段和错误顺序，并确认原实现失败；运行既有 command/policy 合同。
2. 新增本域无状态 PresalesCommandPayload 校验：按 CommandKind 验证已声明写字段和嵌套 issues/sections/requirementResponses，缺失字段仍交原业务规则。不引依赖、不重建通用 schema 框架。
3. Service 在角色→operation→expectedVersion→来源→幂等重放→CAS→归档后、命令变更前调用。复用 ProjectItems.Rejected→原 SemanticApiException 映射，400 INVALID_REQUEST；消息仅字段路径，无用户值。
4. 新写入的错误类型拒绝；文本、未知扩展、字段次序不改；枚举的 missing/null 默认仍由原 enumValue 决定。statementRevision 保留 string 或非负安全整数。人工 SAVE_AI_TASK 的 result/contextSnapshot 保持 opaque 内部，仅检查存在时对象形状；正式员工结果仍走原 pinned model/fence 验证，不用手工命令 schema 取代。
5. 补纯形状正反例及 H2 持久化/回执不写、同键同请求旧回放、异请求冲突、source/role/CAS/archive 优先、修复白名单合同。明确新准入限制，不改历史/冻结数据。
6. dev、格式/编译、适用合同、完整精确树 commit 与 normal hook；独立限定审核，再提交、checked push、PR readback与证据。

## 锁定行为与边界

- 权限/来源/修复形状/事务/lock/CAS/receipt/hash、已发布字节、取消/员工结果接收保持。未知动作仍原 Unsupported command。
- 新 validator 只负责结构，不查库、不批准/规范化状态，不把 extension 升级为可信字段。纯形状接受不等于业务接受。
- 这是明确的新公开写入类型准入；旧 receipt 在验证之前重放，旧 body 只读不回写。缺失必填、枚举值、成员/引用存在性仍由现有领域逻辑检查；branch 内多处非法输入的首个错误可能变为字段形状错误。
- SQL 全量 body 读取另有真实缺口：name/customer/status/owner/stage 的来源不同、ROOT Unicode搜索/历史扩展及坏行失败顺序已有合同，不能用现有 name/status 列盲目替代；完整 SQL 投影/回填、V2单写迁移与缺失规格映射继续待实施。
- 不改 gate/rules/config/Flyway；新增测试仍需维护者控制面签收。原46AC保持 NOT_RUN。

## 回退与未测

回退仅恢复本批 Service 调用、新形状校验和测试源码，无数据库迁移。真实浏览器、生产历史抽样、完整领域状态/响应server DTO/model schema、三方言/备份恢复/V2迁移、维护者/业务QA、远端requiredCI未由本片证明。
