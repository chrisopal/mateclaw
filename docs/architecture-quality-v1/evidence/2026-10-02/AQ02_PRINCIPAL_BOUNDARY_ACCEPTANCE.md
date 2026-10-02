# AQ-02 公共主体与 Workspace 基础授权工程验收

起始 HEAD `929dd467882bed172a436fcc6774afa1e4881f70`、base origin/dev、独立工作树干净。计划见 [AQ02_PRINCIPAL_BOUNDARY_PLAN.md](AQ02_PRINCIPAL_BOUNDARY_PLAN.md)。这不是整个 AQ-02 或业务 QA 签收。

## 职责与兼容

ActorResolver 复用 AuthService 解析可信 web 身份或重新查验已由宿主绑定的 actor；不从工具 JSON/requester 文本推导身份。WorkspaceAccessService 复用原 Mapper 无缓存读取当前 Workspace 和 deleted=0 成员，集中原精确大小写角色等级。售前、投标、语义共用这两个入口，删除借用 SemanticPrincipalResolver 及重复成员查询/等级算法。宿主不依赖各业务模块或 HTTP异常；边界适配保留各域异常类型、状态、代码与消息。

保留售前先解析 actor及workspace（任一非法映射401）与投标 actor→feature→workspace 的不同次序；semantic bound actor仍仅解析long，不新增正数限制。保留user/workspace null-deleted活动兼容、membership严格deleted=0、system admin大小写兼容、成员等级精确大小写与null旧行为。售前不因Workspace ownerId绕过成员；投标Workspace owner、member以上的项目owner批准仍为业务政策；不同owner赋值校验与allowed仅吞403也保留。基础事实读取不是许可凭据，不提供缓存/另一套 ACL。

明确安全收紧（不是纯搬移）：投标 current identity 也拒绝 anonymousUser 哨兵，即使存在同名活动DB账号；批准第二次检查会重新查验actor（包括Workspace owner）及当前Workspace，失效分别返回401/404，不能通过owner提前返回。这不增加可批准角色、不允许普通项目owner绕过成员等级。刻画及新增拒绝断言分别覆盖。

## 证据

搬移前37项刻画/HTTP/数据库锁回归通过；接入后67项通过；公共服务/工具身份加入后80项通过；固定Spotless后显式加入真实SemanticAuthorizationTest的12类85项全部通过（零失败/错误/跳过）。命令：`JAVA_HOME=<Temurin21> mvn -pl mateclaw-server -am -Dtest=ActorResolverTest,WorkspaceAccessServiceTest,SemanticPrincipalResolverTest,PresalesAccessActorTest,PresalesAccessPolicyTest,BiddingAccessPolicyTest,PresalesIntegrationTest,ProjectAuthorityFenceDatabaseTest,BiddingProjectTest,PresalesRuntimeTransactionIntegrationTest,SemanticAuthorizationTest,SemanticFeatureFlagTest -Dsurefire.failIfNoSpecifiedTests=false test`。

真实H2/MyBatis覆盖成员撤销、deleted/null排除、工作区/用户隔离、角色不升级、Workspace删除及null兼容；JWT/HTTP/真实mapper和service覆盖售前/投标/语义与已有权威锁事务。host bean在三个业务模块禁用配置下可独立创建；不是完整模块启动矩阵。工具身份覆盖requester文本拒绝、workspace先于account、活动账号重查和string ID。外部模型使用受控替身。

初始刻画失败来自测试Scope actor/project实参顺序；首轮适配失败来自投标HTTP夹具缺宿主Bean，均保留失败hash/摘录，不称为生产缺陷红例。早期80项命令中的SemanticSecurityTest不存在，未计为已测；85项重跑使用实际SemanticAuthorizationTest。dev SCAN_PASS 0q6n7950，应用工具链NOT_RUN；最终提交/推送门禁按真实tree报告。

测试控制面增量只有两套共享HTTP夹具新增host Bean Import及固定formatter排版，不改运行时测试策略、属性、超时、skip、断言或数据库隔离。原构造器适配不改变既有权限/并发断言。源码/日志SHA256和逐类计数见[机器证据](principal-boundary-test-results.json)。独立只读principal_boundary_review未发现阻断，增量风险低；逐项核对身份/角色/查询与批准政策，两套夹具除新增host Bean后可执行token与HEAD一致，16文件scoped secret scan无候选。审核人未自行运行Maven，已读取85项真实终态日志；无依赖增量，审计NOT_APPLICABLE，未执行全量漏洞审计。本结论不替代正式维护人审批或QA。

## 未测、风险与回退

未重跑生产MySQL/PostgreSQL、真实角色浏览器、异步重启、全图/cache/导出和真实模型或正式QA。無UI、迁移、依赖或门禁规则修改；两套夹具新增Bean必须独立审核。角色null旧行为仍为错误而非授予权限，未来调整需独立契约。读后并发变化保证仍依赖既有事务/fence，不把单次fresh查询说成无竞态。

revert本片即可回退，不改数据库/已发布字节。回退也恢复投标哨兵及批准第二次复核的旧弱点。售前命令/持久化职责、工作台轮询/预览/编辑拆分与正式验收继续待完成。
