# AQ05 / AQ06 最新 handoff 冻结读取与去重验收记录

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`，固定 origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。本片在原未提交 WIP 上继续，未操作原始项目工作区、生产数据、已有迁移或 Git 提交。计划见 [AQ05_HANDOFF_FREEZE_PLAN.md](AQ05_HANDOFF_FREEZE_PLAN.md)，源码摘要、命令、91 个归档及回读指纹见 [handoff-freeze-results.json](handoff-freeze-results.json)；最终 gate 报告等另外放在 `handoff-freeze-tests/final-*.gz`，以报告本身的 target_identity 为准。

## 修复与职责边界

旧最新 route 在已发布后仍从当前项目组装 baseline/solution/clarifications，并重新检查当前 releaseGate。真实 CREATE_RELEASE→APPROVE_RELEASE→PUBLISH_RELEASE→SAVE_CLARIFICATION 流程复现：冻结澄清 1 条，最新 handoff 却带 2 条。后来新基线还可使旧发布读取报 BASELINE_STALE，缺少 snapshot 则重组“历史”。这些违反架构规格冻结不变量与 R-05。

仅修改 `PresalesService` 两个 handoff 读取方法并提取 `frozenHandoff`：保留各自 get/来源授权、最新最后 PUBLISHED 的选择和错误顺序、指定版本查找和未发布错误；共享 verifyArtifacts→对象 snapshot→deepCopy→releaseId。删除最新 route 的当前项目重组与读取时的当前 releaseGate，Service 从切片前 1194 行降为 1173 行，净减少 21 行。逐字节对照确认上述区域以外相同；CREATE/APPROVE/PUBLISH 的门禁、writer、hash、receipt、事务、制品读取及来源授权未改动，没有新增类/依赖/表。

新增 `PresalesHandoffReadContractTest` 8 个合同，现有 `PresalesIntegrationTest` 的 g1 流程只增加两条冻结断言，原断言保留。UI `decodeHandoff` 静态复核仍接受 schema1、既有可选 releaseId/materials/sourceRefs/历史标记与扩展，同时检查 project/Workspace；未把静态复核写成 UI 执行通过。

明确兼容影响：没有对象 snapshot 的历史 latest 读取现在与 exact 一样返回 409 HISTORICAL_SNAPSHOT_UNAVAILABLE，不自动回填/重建。semantic mutation flag=false 时历史最新读取与指定版本一致，但新 CREATE_RELEASE 仍 409 SEMANTIC_DISABLED；测试用 finally 恢复共享属性，不证明启动时整个模块关闭的组合。真实旧记录兼容尚待样本验收。

## 实际 RED 与 GREEN

首次合同 7 项/5 失败与实际发布流 1/1 失败。新增夹具误把不属于另一 Workspace 的 viewer 请求期待为 404，真实为 403；仅纠正夹具，不修改产品授权。辅助归档脚本 SyntaxError 发生在修正写入前，随后执行了原样重复 RED；两次日志保留，original-red-repeat XML 属于重复运行而不是首次运行。

纠正后的实际 RED 为 7 项/4 失败、真实发布流 1/1 失败；补充 semantic flag 合同另外 1/1 失败。源码刻画与日志/XML 分别命名，不把 8 项源码冒充此前 7 项执行内容。产品修复后同一合同 8/8、Integration 8/8 通过。

JDK21 Maven 命令见机器结果：全部 Presales、ProjectAuthorityFenceDatabaseTest、Workbench/Semantic ArchUnit，以及 BiddingHandoff/BiddingHandoffReceipt/BiddingMaterials 消费者回归。按本次日志实际 Running 的 46 个唯一类选择 XML，避免残留报告混入：384 tests、0 failure、0 error、1 既有 skip，即 383 项实际通过。唯一 skip 是缺少 PRESALES_PPT_SKILL_ROOT 的既有 PPT 编译用例；没有新增 skip、删除断言或降低门禁。BUILD SUCCESS、exit 0，日志及报告均已归档解压回读。

显式 scoped spotless:apply 仅匹配 3 个文件，其中 2 个测试格式化、Service 已 clean；与行为编辑分开。随后只读 spotless:check BUILD SUCCESS、exit 0，120 文件缓存 clean；未宣称检查重新格式化或重算每一文件。初始 dev 22sodt3d、切片 dev par7g7gj 均 SCAN_PASS / submission_ready=false。

## 当前源码的真实 MySQL 发布与读取

外部 runner 保留当前两份测试原业务断言，仅改类名、添加隔离 DynamicPropertySource，并追加 MySQL/Flyway 身份测试；launcher 只选择原 g1 方法、8 个读取合同与身份断言，共 10 项 found/started/succeeded，无 failure、abort、skip、container failure，exit 0。不是重新运行其他未选 Integration 方法，未改 POM/原迁移/项目测试配置。外部源码、字节码、JUnit 每项结果和日志已归档。

每次隔离 loopback 动态端口、自建 schema 与缓存固定 mysql:8.0 image。本次 MySQL 8.0.46；外部 launcher 在两个 Spring context 启动前一次性断言自己的库为空。Flyway 从 V1 实跑到 V219，独立查询 history 212 行全 success=1、无 BASELINE，尾部 V217 SQL/V218 JDBC/V219 SQL；pending 0、validate 通过、request_hash 宽度 67。这里只证明本次候选和该数据库版本的空库初装，不证明生产旧库升级/备份恢复或 hash writer 已接入。

实际运行 Spring MockMvc、JWT/Workspace/actor、来源授权和发布服务，以及 MySQL 持久化与事务；保留 SemanticHttpFixture 的 WikiKnowledgeBaseService/PAT/I18n mocks。8 项读取合同含人工 SQL 建立的合成发布夹具；原 g1 独立执行真实创建/批准/发布，不把手工夹具当发布证明。未运行 socket 服务器/浏览器、真实历史 Office 或所有后台来源链路。

244 个 source SHA、运行 classpath 上 8219 个 class SHA、5 个外部 class SHA 前后一致；计数不表示这些类全部加载或覆盖。自建容器按确切 ID `docker rm -f -v` exit 0，并独立 inspect 确认不存在；临时凭据 env-file 删除，未操作已有 MateClaw 容器。归档日志/XML/JSON 的 JWT、JSON password、generated-password 形态检查无未脱敏匹配，不输出凭据。

## 审阅、门禁与剩余边界

独立只读 artifact_read_review 已核对来源授权/冻结职责、修复前区域与当前源码、RED、当前 46 类 XML 与归档摘要，结果 COMMENT，0 个实质问题；MySQL 后续独立回核也为 COMMENT / 0 个实质问题：两个外部测试正文仅类名改变，原断言保留；10 项逐项成功、212 行 history、244/8219/5 指纹和 91 个归档全部匹配，隔离/脱敏范围已核对。其没有运行 Maven/gate/数据库，Java LSP NOT_RUN，不构成维护人批准或正式 AC 签收。

最终执行 `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`，实际状态与源码 snapshot 以归档 final-dev-report.json.gz 为准。dev 的 application-toolchains 为 NOT_RUN，不能将分别执行的 Maven/MySQL 验证冒充完整 commit 门禁；submission_ready=false，commit NOT_RUN，本片未提交或推送。

状态 ENGINEERING_HANDOFF_VERIFIED_UNSUBMITTED。46 正式 AC 仍 NOT_RUN；Kingbase、生产升级恢复、真实历史 snapshot/Office、完整浏览器/后台链路、hash 旧回执策略与 writer 接入、完整 V2 对象迁移、维护人批准、远端 required CI 仍未关闭。前一轮成果 HTTP 17 项属于旧 Service snapshot，本记录没有重写那些摘要或将它们当成新修复实测。必要回退应审阅并撤销本片 handoff 区域及新增断言，保留来源授权和成果完整性保护；本片没有数据库迁移或持久化写入改动。
