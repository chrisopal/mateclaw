# AQ05 发布候选渲染短事务实施计划

状态：IMPLEMENTING / Proposed，未通过正式架构或业务验收。起点e5ccc86c1c591104123ee9ee3a2809abf92f969d，tree a86c7a4ccbb0275bbbb88eb4bf0b830a9b219af0；工作树初始干净。dev base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，r80mhd9a SCAN_PASS/submission_ready=false。

## 目标与现状
ARCHITECTURE_SPEC第7节要求先提交任务再执行，文件转换不得长时间占用数据库事务。当前PresalesService.command→applyCommand持有项目锁，createRelease→PresalesArtifacts.materializeCandidate在同一事务内渲染并写文件；releaseGate还持有graph锁。本片要真正使渲染脱离事务，并保证最终回执/项目/修订/文件原子接纳，不以只搬方法代替整改。

默认保持同步成功响应和现有命令API，内部持久化任务；已询问页面交互偏好，若明确改为后台则按用户答复更新设计。本计划不是发布/生产数据/分支保护授权。

## 既有行为保护
同一HEAD刚完成两轮完整门禁：Java6713、UI1227、Node5及适用检查通过；上一片115次原实现刻画、982项售前/投标/架构回归保留。开始生产修改前增加真实Spring/H2事务边界反例，证明当前渲染占用事务及项目锁；新增公共授权围栏以缺失/撤权/锁后重读和事务要求测试保护，不删除既有断言。

## 实施次序与所有权
1. 售前命令与制品执行：捕获不可变渲染输入/确切PPT、持久化operation/attempt身份、提交任务、事务外转换、短事务接纳；同键重放与跨项目/跨命令占用一致。重构边界复用现有hash、CAS、renderer、revision/receipt和错误合同。
2. 公共授权围栏：新增人工命令入口支持真实actor、可选员工、完整graph/KB/raw依赖，不伪造model/rawId；保持既有AI结果入口严格契约、确定锁序及MyBatis清缓存，独立技术审核。
3. 新内部任务表仅新增三方言迁移；不修改任何既有Flyway/冻结算法或重写摘要。任务不是业务revision，不提前增加项目版本。失败不创建半成品release或最终receipt。
4. 真实独立连接/latch回归：renderer无事务、并行修改不阻塞、版本/权限/来源/事实/PPT变化拒收、跨命令幂等、结果晚到/进程恢复、每个写入点失败全回滚、响应丢失精确回放。保留原模板/字段/错误/冻结历史字节合同。
5. 协调编译与回归期间停止其他源码编辑；dev及适用回归通过后独立审阅权限/迁移/并发方案，再执行正常精确树commit/push门禁。

## 依赖和风险
保持runtime→公共接口→业务适配器；Controller不增加DAL。旧AI任务强制employee/model且增加project version，不能直接作为渲染任务。新任务与最终operation共享幂等命名空间，跨命令竞争不能只靠进程map。项目在转换期间可继续编辑，接纳返回VERSION_CONFLICT，不自动换最新输入。HTTP断开不是业务取消。

转换结果优先内存保留，接纳事务才持久化，失败/过期不留文件；重启/接管使用持久化attempt围栏拒绝晚到结果。最终接纳事务使用READ_COMMITTED并锁后重新授权。长请求等待和重试遵循已有运行契约，任何新产品行为需在实现和验收中明确。

## 验收边界与回退
本片未完成，不预写PASS；缺少真实数据库/浏览器/Office/业务签收仍NOT_RUN。新增任务数据形成后不得无条件回退到忽略operation占用的旧writer；迁移/回退设计与实际验证一并交付。无需新增外部依赖，不调整门禁阈值或删测试。

## 实施中确认的边界

实际采用单一 render_task 表；旧 operation receipt 继续是唯一最终响应。所有命令通过既有主体/工作区行锁协调同键请求，避免第二套回执。十分钟租约、失败/到期可同键重试、原输入不可替换、旧 attempt CAS 失效；不会自动后台继续发布。

独立审阅发现外层 REPEATABLE_READ 普通查询可继续读取旧权限/回执，因此新增宿主 current-read 入口并保留 REQUIRED 加入语义；历史 fit 复用真实 evidenceId 来源解析，历史标量来源按冻结材料 graph 展开。来源锁定既含当前也含历史材料。新用例需覆盖这些反例；此记录不是降低原门禁或授权部署。
