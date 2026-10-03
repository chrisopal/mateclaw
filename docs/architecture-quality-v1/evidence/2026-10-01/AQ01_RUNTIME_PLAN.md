# AQ-01/02 runtime 与代理事务验收切片

起点 `05bb9f5c646b5cea408c81cc045200c9f16f204e`，工作树干净，dev SCAN_PASS。

范围：新增 Spring 集成测试，复用真实 PresalesService 代理、PresalesEmployeeRuntime、ProjectExecutionRevalidatorDispatcher、PresalesExecutionRevalidationProvider、PresalesAccess、PresalesContextProvider、ProjectSourceAccess、AuthService、模型配置服务及 mapper；使用现有 H2 Flyway 全量 schema；补充本机隔离 MySQL 8.0.46 空库全量迁移后的同一用例。AgentService 的员工读取替身转调真实 mapper，模型流与会话接口仅为外发边界替身，不调用真实模型。

验证：正常运行及结果提交；REQUIRES_NEW 挂起调用者事务并在调用者回滚后保留结果；结果短事务 READ_COMMITTED；执行前停用 actor/员工、模型 pin 变化、成员 viewer、任务取消/项目版本漂移均阻止模型外发；生成后发生相同变化也拒收结果；锁等待期间 Workspace 删除/成员降权后真实 runtime 拒收，版本/任务/receipt/revision 不变。

不修改生产策略、API、依赖或迁移。用例建立后先定向回归，再独立审阅和 dev/commit/push 门禁。正式 UI/历史/导出/真实模型及业务 QA 不自动转 PASS。
