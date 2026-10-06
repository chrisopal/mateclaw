# AQ05 员工列表固定 DTO 清理计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。累计WIP保留，主checkout不动。初始dev日志 /tmp/mateclaw-employee-dto-initial-dev.log。

边界问题：employees在PresalesEmployeeRuntime、GenerationService和GenerationController三层使用List<Map<String,Object>>，前端已有id/name/enabled/available固定契约。只将此查询投影改为PresalesDtos.Employee，移除两处Map导入；不改生成/取消、领域员工实体、公共AgentService、权限或持久化。运行时仍查询同Workspace且包含enabled=true的原列表，保持原eligible筛选与顺序，权限仍在应用服务先执行。

先新增原实现可运行的JSON/HTTP刻画：字段全集与大整数ID字符串、实体变化后已返回结果不变、disabled/deleted/异Workspace/plan_execute/非native/null过滤、缺runtime空列表、上游异常传递及无效名称/ID拒绝、viewer授权和拒绝不触发上游查询。旧实现通过并归档XML后替换生产投影。record拒绝null id/name，保持原Map.of拒绝null的行为；不引入默认值、额外enum、映射框架、依赖、事务或数据库变更。

验证：旧实现定向测试→归档→DTO替换→相关Presales和架构/来源/投标消费者回归→归档→正式base Spotless与dev；独立审阅测试及权限边界。不要在运行命令期间编辑。DTO只承载可公开字段，不包含模型/提示词等完整Agent实体。JSON字段顺序不是客户端契约；不改任何持久化/回执字节。

本计划是既有ADR-AQ-022固定查询DTO方向的后续实施。完整项目/命令DTO、V2数据模型与正式AC继续开放；本片不等于完整售前结构整改。回退仅恢复四份生产源及本批新测试，无数据迁移。
