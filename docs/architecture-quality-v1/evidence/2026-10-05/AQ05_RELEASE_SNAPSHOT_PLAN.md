# AQ05 发布候选快照职责拆分计划

- 起点：HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`；base `origin/dev` = `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。保留既有 WIP；初始 dev `pj3_3odf` 为 SCAN_PASS，submission_ready=false。
- 问题：PresalesService 同时承担命令事务、实时发布检查、成果持久化和候选 handoff JSON 的选择、深拷贝、风险投影。此次只分离最后一项，避免持久化流程掩盖冻结内容规则。
- 范围：PresalesService、包内 PresalesReleaseSnapshot、对应回归及本批证据。沿用现有 ObjectMapper、PresalesProjectItems.find 和 Rejected 错误映射，不新增 bean、依赖、表或公开接口。
- 行为保护：先运行现有发布集成、冻结 handoff、投标消费回归；补充服务创建候选边界的快照字段、选择顺序和双向深拷贝刻画，在原生产实现上先通过，再保持断言不变进行提取。
- 职责边界：Service 继续授权、实时 gate、生成 ID、渲染/存储原字节、保存 release、管理事务；快照构建器只读取已校验项目及候选记录，返回独立树。发布时刷新澄清与审批/发布状态的既有逻辑保持原位。
- 兼容：schemaVersion=1、字符串 ID、字段/数组顺序、PENDING 状态、manifest、缺省节点语义、风险筛选及 legacy 404 映射不变；不重建历史快照。不接入尚未决定的 hash replay 策略。
- 验证：原实现刻画回归 → 提取 → 同一回归与 Presales/架构/投标消费测试 → prescribed spotless 检查 → 独立审阅 → dev。保存实际运行类与 Surefire 结果，禁止用旧 XML 汇总替代本次执行。
- 回退：仅回退本批 Service 差异和新构建器；无数据库迁移。测试夹具不代表真实历史数据、完整应用/浏览器或业务验收；正式 AC、完整 commit 门禁、维护人批准和远端 required CI 不在本批关闭。
