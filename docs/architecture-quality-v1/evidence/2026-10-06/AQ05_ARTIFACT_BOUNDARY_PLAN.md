# AQ05 制品物化与交付边界拆分计划

状态 Proposed，延续用户授权的业务结构整改；独立技术审阅不替代正式维护人/业务签收。初始 HEAD2fb5577d9d766dbf284c99a9bba612512f4b0f4b、treebc168a16910e7118b7ab05cb173ece910c5eb423，工作树干净；dev ijp2sxnq SCAN_PASS/submission_ready=false，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。上一轮是真实完成事件修复及提交推送，属于进展；CI37394849714初查仍in_progress。

## 问题、方案与取舍

PresalesService仍同时掌握用例授权/事务和制品实现：重复Document/sections构建、presentation与模板选择、文件编码/摘要/manifest/存储、冻结交付。已有包内Reader负责三键查询/双摘要校验。按独立presales_artifact_design审阅，采用深度制品模块，拒绝只抽Document helper（边界太浅），拒绝本片同时新增Spring发布服务并迁移授权/事务（风险扩散和循环依赖）。

将既有PresalesArtifactReader重命名/扩展为包内PresalesArtifacts，复用其读取算法而非叠加转发层：负责候选生成/存储/清单、草稿选择与渲染、preview全清单校验、download指定文件校验、handoff验证和快照独立复制。不新增bean、依赖、公共API、线程、表或迁移。Service保留admin/viewer、全项目materials/history来源复核、live releaseGate、saveItem/发布状态/快照捕获、回放/CAS/事务。制品模块输入只来自完成对应授权的Service路径，不允许Controller绕过调用。

保留原时序 gate→render→presentation读取→insert/manifest→saveItem→临时/最终releaseId重绑定→snapshot；不重新分配或统一ID算法。保持preview先admin再get、handoff先校验文件后检查snapshot、PUBLISHED完整冻结快照才跳过新发布gate。保持isTextual控制是否模板生成slides与非空artifactId控制读取presentation的两个不同旧分支；不顺便修正空字符串/非字符串行为。保持三键、双摘要、重复行/manifest拒绝、presentation空expected摘要兼容、历史原字节和错误优先级。

当前渲染仍在command事务中。本片降低实现耦合，不声称达到文件转换退出长事务的目标；后续需明确输入快照、授权/CAS重验、制品清理协议再移动，不能用纯函数提取伪称性能修复。

## 实施与验证

1. 原实现已有101项artifact/release snapshot/handoff刻画通过，日志/tmp/aq-presales-artifact-boundary-baseline.log。新增有遗漏敏感性的Service合同：候选renderer参数/顺序、presentation只读原字节、坏presentation先于insert失败、空artifactId原分支、草稿PPT/slide/report路由、清单size/digest/顺序与重绑定。先在原实现运行通过，再提取。
2. 修改Service和重命名/扩展Reader；不改原有HTTP、public Service签名及构造器签名。保持既有冻结产物、权限、真实H2事务回滚和消费方测试；删除旧Reader生产/编译残留。
3. Spotless、dev、适用售前/投标消费回归及独立控制面/架构审阅。完整commit/push门禁针对精确树，远端CI单独核验，不把绿色工程测试当真实Office/业务签收。

回退：本片源码/测试可反向恢复，不改历史字节或持久化格式；不回滚累计PR中的新数据迁移。待验：真实客户端/Office、方言生产环境、正式AC、文件转换长事务、整体Service/页面其余职责。
