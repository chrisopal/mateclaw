# AQ-05 项目列表投影职责实施计划

起始HEADb7a40deb8a99e06765a85a381fbf93c97260412e，独立工作树干净。原项目WIP未触碰。先运行dev，报告实际任务ID。

PresalesService混合列表授权/原始读取和筛选/分页/摘要/阶段投影。本片把后者交给领域PresalesProjectListing，内部typed Criteria保留原query/status/owner/stage/page/size；Page已有wire结构不改，ObjectNode历史扩展保持，不宣称完整稳定项目DTO或V2完成。

服务仍先viewer授权、再pagination校验、再repository Workspace条件读取和原decode；列表不擅自改成详情source授权，仍剔除原十二类来源集合。阶段判定同一实现复用于命令/repair fallback，保留ARCHIVED→RELEASE→SOLUTION→BASELINED→REQUIREMENTS→DISCOVERY优先级和原withArray行为；摘要深副本、未知历史顶层扩展、openClarificationCount及最后方案version不变。服务原repair集合inventory留原位；投影接收同一不可变inventory，不复制第二份名单或扩大白名单。

先旧实现真实HTTP刻画：query name/customer Locale.ROOT、status/owner/stage精确过滤、原name/id顺序、筛选后total/分页与空页、旧大string IDs、摘要去来源/未知扩展/计数/最后version、非法分页、Workspace权限/隔离，原project存储body逐字节不写。再迁移纯投影并补阶段优先级/副本/空缺数组行为合同。无新增DI框架、依赖、schema、控制面或批准政策；existing assertions不删改。

每个职责片dev和原领域回归、Spotless只读check；独立审核公共wire/权限/兼容。完整精确树和正常commit/push绑定最后候选，证据日志先脱敏JWT/JSON凭据/Spring自动开发密码。真实SQL分页、V2迁移、浏览器/多方言/正式QA仍NOT_RUN。

实施修正：不用eager toList，Stream保留前行stage错误先于后行decode错误；新增纯测试锁定该顺序。初始dev9cfmcrxc/最终jvrkz12x，旧15/最终58项通过，独立限定审核通过。
