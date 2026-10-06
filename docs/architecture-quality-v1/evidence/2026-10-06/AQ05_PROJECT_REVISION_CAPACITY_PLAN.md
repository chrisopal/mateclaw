# AQ05 项目修订容量与终态恢复计划

状态：Proposed；技术评审与工程验证不替代部署批准或正式业务验收。
起点 HEAD `20e29f2e0c3f0eca381a0d4a454b96acb91054f5`，tree `ef3c766bfe3ad006e66c9ec5377237a39d876f2a`；隔离工作树干净，base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。初始 dev `wml29bnj` 为 SCAN_PASS，submission_ready=false。

## 问题与要求

现有项目修订由 HTTP Integer、JSON、SQL INTEGER、队列 acceptedVersion 和工具作用域 int 共同表示。达到 2147483647 的合法项目无法递增，因此已有 RUNNING 任务重启后仍无法保存 FAILED。前序防溢出保护正确拒绝写入，但未完成合法耗尽项目恢复。不能回绕、重置、同版本覆盖，也不能从坏 JSON 或不一致的物理列猜测权威。

只扩容项目修订：包括项目版本、项目修订历史表主键、列表投影版本、快照 projectVersion、生成 acceptedVersion 和请求 expectedVersion。需求/方案/基线条目及语义修订继续保持原 int 契约；此片不替代 AQ06 对象独立版本/依赖拆分。冻结 V211/V217/V218/V219、ListingProjectionV1 及历史字节不修改。

## 方案与取舍

推荐 Java long / SQL BIGINT，JSON 继续整数 number，上限 9007199254740991（2^53-1）。前端现有 number 和十进制序列化能精确表示此范围；不新增依赖、配置开关或第二套权威。预计新增迁移 V220，落盘前再次核对版本占用。输入只接受 JSON 整数或 null；历史存储读取继续允许原已支持的整数字符串，要求正数且不超过上限。负请求仍走原冲突/创建校验，绝对值超出安全范围、浮点及字符串请求拒绝；缺省/显式 null 不变。

拒绝仅把恢复 UPDATE 改成同版本写入（破坏 CAS 和消费者失效）；拒绝全 long 范围（JavaScript number 不能精确表达）；拒绝把版本 wire 改字符串（破坏现有消费者与旧请求序列化/hash）；拒绝把所有对象修订一并扩大（无关契约扩散）。新的安全上限仍是有限容量，达到上限继续明确 VERSION_EXHAUSTED，不能宣称永久无限恢复。

保持权限→来源→回执→版本→业务规则→事务/CAS顺序，原合法 int 请求序列化与 hash 必须逐字比较。旧 operation/revision/artifact 不回写。Jackson 小数值 IntNode/LongNode 的 equals 差异不能使原队列 task 身份失配；生成快照写入应保持原低位 JSON 节点表现或通过既有持久化读回权威，而不能删除 task 精确身份比较。

## 职责和实施顺序

1. 先增加旧上限跨越的真实 RED：HTTP 命令与回执重放、恢复/权限丢失兜底、排队后的接受版本。保留旧坏版本/不一致/取消/延迟结果拒绝断言。
2. 独立项目修订边界负责精确解析、比较、递增及安全上限；现有 ProjectItems 只保留条目修订职责。ExpectedVersion/四 DTO、Service record/update、GenerationService/Coordinator、ContextProvider/EmployeeRuntime/ToolScope/RevalidationProvider、BaselineApproval 的项目快照同步使用该边界。
3. Repository 的 ProjectRow、getLong、CAS、listing_project_version 和 insertRevision 全链 long；SQL 新增三方言迁移只扩大三列，不改变 nullability/主键/数据。V218 在旧列范围先执行，V220 之后不得重跑旧 backfill。
4. 新回归覆盖旧边界两侧、安全上限两侧、坏/缺失/浮点/别名版本、任务精确身份、取消、并发重读、回执 byte/hash、列表摘要及历史修订。既有 int 上限的项目测试迁到新安全上限，同时保留旧上限作为成功正例；条目上限仍原样拒绝。
5. H2 从 V219 升级及空库到新版本、Flyway validate、旧 row/body/receipt/artifact 字节及列/约束对账。可用隔离 MySQL 重复升级/备份恢复；Kingbase 未具备实际环境则 NOT_RUN，不以文件存在视作方言通过。
6. dev、适用回归、Spotless、独立技术审核后再完整 commit/push 门禁；门禁/Maven执行期间不编辑。记录失败和重试，不绕过历史测试。

## 运行切换与回退

此变更只准备代码和追加迁移，不执行生产部署。迁移和新 writer 启用前须停止全部旧 writer，排空或明确终止执行，完成备份/隔离恢复和数据清单；不得新旧进程混跑。即使 BIGINT 列允许旧进程写入，旧 int 读取或冻结 backfill 仍可能失败/截断，不以 schema 兼容当 writer 兼容。

未产生超旧范围的新写入时，也必须校验所有三列与项目/快照修订、当前模型任务状态后才考虑回退；存在新范围修订时旧二进制不能安全恢复，停止写入并前向修复或按批准方案恢复整套备份，不能只缩列/回滚代码。备份之后的写入损失须业务批准，本任务不执行此操作。

malformed/mismatched 的项目仍 fail closed；缺少可证明权威的修复输入，不能按较大列值或最高历史记录重建（runtime 写入不总会产生历史行）。这一剩余项独立记录，不被合法旧容量扩容掩盖。
