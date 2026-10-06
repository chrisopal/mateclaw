# AQ-05 任务结果接收职责拆分计划

仅 architecture-quality 工作树；HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`、初始115项dirty，保留累计WIP。初始dev ejgjufme SCAN_PASS，submission_ready=false。

本片集中 Service 的 SAVE_AI_TASK 接收规则：默认状态和不可信标记、原运行任务完整身份、成功结果格式、权限围栏、运行时重验和模型校验。新增包内 PresalesTaskAcceptance.prepare；复用 ProjectItems、公开 ProjectAuthorityFence 和现有业务 runtime/ModelAdapter。删除 Service 中搬出的围栏与身份比较逻辑和围栏字段，不引入框架、回调、bean、依赖或表。

Service 仍拥有角色/来源授权、receipt回放、版本检查、项目行锁、READ_COMMITTED/REQUIRES_NEW事务、任务保存与成功结果投影、项目CAS、revision/receipt写入。prepare仅变更候选的status/authority并验证，在Service事务内通过原围栏取得锁；不启动新事务或写项目。PresalesRejected仅在原Service边界转换成既有SemanticApiException，模型错误保持原样。严格保留provider访问和短路顺序，失败候选仅忽略原有五个输出字段，成功候选仅忽略原有三个输出字段；未知扩展字段继续参与身份比较。普通命令路径继续由现有payload验证限制，不扩大终态写入权限。

实施前运行现有AtomicResultAcceptance、RuntimeTransactionIntegration、CommandPayloadContract、ProjectAuthorityFenceDatabase测试锁定行为，尤其撤权等待、三表回滚、独立事务提交、失败诊断与终态不可覆盖。现有完整Service合同足以保护搬移；提取后新增直接接收边界测试，覆盖缺失runtime、错误优先级、围栏来源/actor参数、锁失败映射、取消/身份变化、成功诊断注入及失败候选无需执行权限。然后定向回归、扩展presales/runtime/ArchUnit、Spotless非修复检查、dev及独立审核。新增测试属于测试控制面，要求独立审阅；不改旧断言或放宽门禁。

仅此责任切片，不扩展为通用任务引擎，不改取消、投影、旧迁移、前端、API或历史存储。完整DTO/V2迁移、历史坏/耗尽版本RUNNING恢复、正式AC与远端强制检查继续开放。开发回退恢复before Service、删除本片新类/测试，保留此前所有修复。
