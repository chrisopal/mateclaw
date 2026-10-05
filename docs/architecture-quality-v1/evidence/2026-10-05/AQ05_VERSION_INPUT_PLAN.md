# AQ05 / AC16–20 版本输入边界修复计划

- 起点：HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `c5h1h7ur` SCAN_PASS、submission_ready=false。保留既有 WIP。
- 全范围核对：AQ05 要求稳定 DTO/运行时校验，AC16/17/20 要求明确版本冲突及回放。创建/命令/生成的 expectedVersion 为 Integer，但默认 Jackson 可发生 coercion；PATCH 直接 ObjectNode.asInt。完整售前 expectedVersion HTTP 入口共四处，本批一起处理，不修改取消接口内部读取的当前版本。
- 先复现：真实定向 Spring HTTP/H2 验证小数与数字字符串在 create/command 被接受、PATCH 超范围数值可能截断为当前版本。归档旧实现 RED 的实际 HTTP 响应；失败会先停在状态断言，不能宣称该路径后续数据库对比已执行。修复后核验 project/revision/operation 均无写入。生成入口使用真实 MVC/Controller 和 mock 执行依赖，禁止真实模型调用。
- 修复：复用一个局部 expectedVersion JSON 整数解析器，绑定 Create/Command/Generate DTO；PATCH 引入明确的 Update 请求元数据 DTO，保留原 ObjectNode payload（含顺序/扩展字段）传递给原 Command，避免改变合法回执 hash。禁止全局 ObjectMapper coercion 配置修改。
- 类型规则：仅 JSON 整数且能精确装入现有 Java int；不以截断/取整/字符串转换接受版本。缺失/null 继续交给原服务的 required/version 政策（PATCH null 将与缺失一致）；范围内整数仍由既有业务策略处理，例如命令/PATCH 负数为版本冲突、创建负数不满足必须为零，不增加另一套版本状态机。
- 明确兼容变化：小数、数字字符串、容器、布尔和溢出版本返回现有 400 INVALID_REQUEST/Malformed request；旧的此类 malformed 请求不再以 coercion 回放。合法整数 JSON wire、操作回执、权限、CAS、来源、事务和扩展字段保持；不重写旧回执，不整合待决的 UTF16 hash 策略。
- 范围：PresalesDtos、PresalesController、PresalesGenerationController、局部解析器及合同测试/证据。无新依赖/bean/SQL/schema。先写回归、看真实 RED，再修复。
- 验证：四入口非法形状与无 project/revision/operation 写入；合法整数、重复操作、异请求冲突、过期版本、角色拒绝和缺失/null；DTO 合法序列化不变；执行全 Presales/权限/架构/来源/投标消费者回归、正式 base Spotless、独立审阅和 dev。
- 限制与回退：不关闭完整并发/迁移/业务 AC；不测真实模型或真实历史数据。本片不解决 int 版本用尽/数据库异常历史值；恢复旧解析会重引入已复现缺陷，不能把代码可回退等同安全运行回退。
