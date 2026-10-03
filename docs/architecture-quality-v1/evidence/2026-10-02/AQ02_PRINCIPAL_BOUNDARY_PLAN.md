# AQ-02 公共主体与 Workspace 基础授权实施计划

起始 HEAD `929dd467882bed172a436fcc6774afa1e4881f70`，base origin/dev；独立工作树干净，执行 dev 检查。范围：auth 公共主体解析、workspace 无缓存基础读取/成员等级、PresalesAccess/BiddingAccess/SemanticPrincipalResolver 适配及对应回归。具体合同经只读架构审核后收敛。

先新增现有实现的刻画测试并运行：web 认证与后台 actor、enabled/deleted、显式 ID、Workspace 不存在、viewer/member/admin/owner/system admin、Workspace owner 差异、owner 输入与批准政策、失败顺序/错误状态代码消息。复跑既有真实 HTTP/JWT 与权威锁/事务回归。之后删除复制基础逻辑，复用 AuthService 和 Mapper；不使用 WorkspaceService 60秒成员缓存，不增加另一套 ACL/账户/认证上下文。

宿主只解析可信主体、读取当前 Workspace/active membership 与等级；售前仍不因 ownerId 自动授权，投标 owner 例外与项目 owner 批准留在业务层。上下文 actorID 只可作为已由宿主绑定身份的重新查验参数；不允许从工具 JSON 或浏览器自报获得身份。公共层不依赖业务或 semantic web；领域入口适配原异常类型/状态/代码/消息，保留现有 ID解析与检查次序。任何确需收紧的差异应明确记录，不能无声放宽。

不改控制面、依赖、迁移、生产数据、批准规则、事务/fence/cache清除。每片 dev + 定向回归，独立安全审核；最终完整暂存精确tree门禁、正常提交和推送、远端SHA/PR读回。正式 QA、多方言和真实浏览器矩阵仍按实际证据报告。

架构复核补充：SemanticAccessService 可同步复用相同无缓存 Workspace/actor 原语，保留 semantic feature/解析/错误次序及不允许 ownerId 绕过的政策；不增加 actor 正数校验。公共输入身份拒绝 anonymousUser 哨兵，对投标是明确收紧；批准第二次复核同时检查 owner actor 活动与 Workspace 存在，不允许 stale owner shortcut。增加正反例并独立复核，不改变可批准的正常角色。
