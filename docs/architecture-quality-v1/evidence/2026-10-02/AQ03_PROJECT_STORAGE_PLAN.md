# AQ-03/05 项目持久化边界计划

起始HEADdd4429619889e38719a8223357fd9e626fea5cdd，独立工作树干净，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；dev l23qp7ze SCAN_PASS。原项目WIP保留。

## 边界与取舍

PresalesService仍直接管理项目、操作回执和历史修订SQL，无法独立核查Workspace过滤、FOR UPDATE和版本条件。提取本域repository.PresalesProjectRepository，只持有JdbcTemplate及SQL：项目list/find/insert/update，回执find/insert，revision insert。接口使用string ID/原始JSON字符串、精确version和不可变row/receipt record；不解析/序列化JSON，不校验授权或批准，不拥有新事务或缓存。

Service保留真实actor/Workspace/来源与修复allowlist、全部public事务传播/隔离、load不存在404、replay hash冲突、编码/原ObjectNode业务规则、CAS返回行数检查和原错误、record/receipt调用顺序与UTC时间。DAO原SQL/参数/过滤/FOR UPDATE/排序保持；零行返回给Service判断，不把DAO事实当授权。既有artifact SQL留到独立冻结字节职责片。本片不改表/schema/Flyway，不宣称V2独立对象、数据库分页或迁移完成；拒绝通用JSON仓储/第二套权限/自动REQUIRES_NEW/把整个聚合移动进DAO。

## 先行行为保护与实施

先新增真实JWT/HTTP+H2刻画：创建/命令持久body、revision、receipt字节相等；重复操作回放不新写/不同输入冲突/跨Workspace不可读；故意令revision约束失败，验证项目更新和回执一起回滚。原实现运行新测试和现有Integration/AtomicResult/AuthorityFence/RuntimeTransaction/SourceScope，再生产编辑。Spring import/三个direct constructor测试装配新增真实repository，不改原断言。

补repository真实H2+TransactionTemplate合同，验证workspace过滤/字符串ID/原JSON字节/版本CAS/缺失Optional/transaction rollback，以及原FOR UPDATE双连接竞争。运行定向Java、dev、固定Spotless、独立事务/安全审阅、精确完整commit门禁和正常push/PR回读；本片无UI源码修改；实际保守影响规则java→ui仍执行type/ID/Vitest/Node/双主题构建，只有无变更的UI formatter/lint目标判NOT_APPLICABLE。不新增依赖或修改测试/门禁配置。

## 风险、剩余和回退

Repository依赖Spring测试显式import要补齐；构造器变更需所有调用点核对。不能改变JSON编码、摘要去来源规则、父事务绑定连接、锁顺序或发布历史字节。Java全reactor、三方言/真实角色/并发重启/正式QA仍独立证据。本片可回退DI/SQL委派无数据迁移；后续Service查询摘要/命令策略/冻结发布、V2精确独立修订与单写迁移继续推进。
