# AC05 真实应用组件扫描启动矩阵计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2 / base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；初始dev ffrz3s_p SCAN_PASS。保留累计WIP，不启动生产或使用现有数据库。

针对正式AC05缺口：此前选定Spring组件不能证明MateClawApplication完整扫描/装配。新增测试专用child JVM探针与JUnit驱动，以真实SpringApplication(MateClawApplication.class)运行；排除所有target/test-classes，只复制探针自身编译类，避免测试@Configuration污染组件发现。不能列举业务Bean替代真实扫描，不引入mock或依赖。

独立JVM cwd/user.home/java.io.tmpdir全部临时，清空继承环境，显式classpath application.yml和ac05-isolated profile，全新H2内存库、loopback随机HTTP端口、明确临时Skill/sandbox/upload/wiki/payload目录。关闭非目标plugin、语义抽取/bidding调度；不声称Spring调度总禁用。使用已有await-language-selection=true避免data-zh种子启用cron/渠道，读取真实库核对没有enabled cron/channel/MCP/provider。模型配置只用测试占位key，不调用模型。无法仅凭静态分析保证零网络；若发现初始化器需要外部数据，先记录，不用bean排除掩盖问题。

先000启动、归档，再八组合000/111/100/010/001/110/101/011完整运行，每个独立进程/库。断言ApplicationReadyEvent、真实DataSource/H2身份、Flyway validate/pending/current、真实HTTP setup状态、模块配置和条件Controller/Service、公共Agent和dispatcher、context关闭以及进程退出。不得把banner当启动成功。子进程超时强制终止自身，不影响其他服务。

本阶段只证明“待初始化setup模式的完整组件上下文”，不关闭正式AC05；正常初始化路径、认证用户/Workspace/业务unavailable及普通聊天执行后续另补。真实MySQL/Kingbase/模型/浏览器/P0签收/远端CI仍开放。测试产物和子进程日志逐次归档，脱敏后回读，独立审核隔离/覆盖边界；无生产代码改动，发现装配缺陷则按实际原因修复且重跑。

独立审阅后的补强：首轮八组合8/8已归档；投标Bean无条件注册，单纯flag绑定不足以保护禁用语义。现通过真实AuthService创建普通测试用户、WorkspaceService创建其私有owner工作区，再走HTTP登录取得仅内存使用的token。八组合均调用投标capabilities：启用200且enabled=true，禁用404/BIDDING_DISABLED。售前启用时HTTP新建/回读项目，statements在语义关闭时409/SEMANTIC_DISABLED，开启时空列表200；投标开启而售前关闭时handoff-options返回409/PRESALES_UNAVAILABLE，两者开启时空发布列表200。fixture不是默认seed初始化、普通聊天或客户业务验收。成功运行复制结果后删除仅自身临时根，失败保留定位；强杀等待必须确认退出，并处理已知后代进程。
