# AQ05 当前成果读取的隔离 MySQL 验证计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；保留全部当前 WIP。成果读取修复的上一批 369 个实际回归通过，当前仍需 MySQL 组件实测；不能使用旧列表回归充当新读取修复证据。

本片不修改产品源码或数据库迁移。在临时目录编译当前 Reader/Repository/ProjectItems/Renderer 精确源码，使用现有测试 classpath 和 JDK21。探针调用实际类和 JdbcTemplate，使用独立 SHA-256 oracle 生成期待值，不复制 Reader 算法以冒充验证。归档探针、runner、源码摘要、结果与清理身份。

仅创建自行命名的 loopback MySQL 容器、动态端口和空 schema。固定已缓存 mysql:8.0 image ID，不拉取新镜像；凭据经临时 env-file/进程环境传入，不打印并及时删除。URL 必须匹配 127.0.0.1 和 mateclaw_aq_acceptance_*，校验产品 MySQL。使用发布 V211 MySQL 脚本建立最小存储 fixture；这不是完整系统 Flyway 启动、V219 或生产升级证明。结束删除自建容器及匿名卷，不操作既有用户容器。

覆盖：精确三键隔离、有效原字节与存储事实、替换内容和存储 digest、存储/manifest/空摘要错误、未声明与重复 manifest、缺失记录、候选校验、PPT 空 expectedDigest 仍校验存储、严格 Base64、物理 PK 唯一性、调用方事务回滚与失败读取不写数据。另生成一次合成 DOCX/PPTX，保存后回读原字节并用 POI 打开；不称作真实历史或人工 Office 验收。

MySQL 此处证明实际组件与数据库读取路径；HTTP/角色/来源仍使用上一批实际 H2/MockMvc 证据，本片不冒充完整 MySQL HTTP 测试。Kingbase、真实历史文件、旧回执策略、正式 AC 和远端 required CI 仍待完成。可独立审阅 runner 的环境隔离与证明范围；不改 gate/断言/基线让结果变绿。
