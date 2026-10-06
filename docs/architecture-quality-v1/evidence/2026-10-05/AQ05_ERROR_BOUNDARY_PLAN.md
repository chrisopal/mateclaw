# AQ05 售前错误边界实施计划

- 起点：HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `ntgln6lz` SCAN_PASS、submission_ready=false。保留既有 WIP。
- 依据：IMPLEMENTATION_DESIGN 的 AQ05 类型化顺序为 Ref/ID/status/error；ADR-AQ-020 明确保留 Service 的 SemanticApiException 兼容出口。不能为了消除 import 破坏运行时/协调器及旧 Java 捕获契约。
- 问题：成果读取、命令校验和方案规则共同借用 PresalesProjectItems.Rejected，错误契约被嵌套在条目修订工具；HTTP adapter 使用 Map 临时拼装错误数据，缺少稳定类型。
- 设计：把同一 status/code/message 拒绝类移为包内 PresalesRejected，纯模块与 Service 适配器直接使用；删除原嵌套类，不保留无消费者别名。Service 仍映射原 SemanticApiException，外部公开服务返回/捕获契约保持。PresalesDtos.ErrorData 明确唯一 code 字段；HTTP handler 使用 R<ErrorData>，不改变 wire、状态码、消息或已有异常映射。
- 范围：上述类型及直接使用者、三份纯规则测试的异常类型名、增补错误适配合同、本批文档。类型迁移涉及测试编译符号，允许机械更名但不得删除或放宽 status/code/message/副作用断言；保留更名前源码及等价对比。
- 先锁行为：新增 standalone MockMvc/序列化合同覆盖业务状态、未知 code、原 semantic fieldErrors/rejected payload 不透出、DuplicateKey、坏 JSON、缺 header、空 message 与 null code 原拒绝语义；已有 Service/HTTP 回归保护公开捕获边界。原生产实现上先通过，再迁移。
- 不变项：鉴权、Workspace、来源、事务、回执、schema、已发布字节、外部 exception handlers、依赖/bean 和旧 hash 策略。不是引入新的通用异常体系，不统一 SourceAuthorization 的权限拒绝类型。
- 验证：基线合同及受影响纯规则测试 → 迁移 → Presales/权限/架构/来源及投标消费全相关回归 → 正式 base Spotless → 独立审阅 → dev。保存实际 Running 类的 XML 与源码摘要。
- 回退：恢复直接类型引用、嵌套类和旧 Map handler；无数据库变更。工程证据不等于正式 AC、完整 commit 门禁、维护人批准或远端 required CI。
