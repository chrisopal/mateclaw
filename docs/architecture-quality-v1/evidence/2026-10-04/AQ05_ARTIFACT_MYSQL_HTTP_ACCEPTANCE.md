# AQ05 / AQ02 成果读取的 MySQL HTTP 与来源授权验证

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，固定 origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。本轮保留全部未提交 WIP，没有修改产品源码、原 H2 测试、迁移、权限政策或 hash writer。计划见 [AQ05_ARTIFACT_MYSQL_HTTP_PLAN.md](AQ05_ARTIFACT_MYSQL_HTTP_PLAN.md)，机器结果和 63 个可回读归档见 [artifact-mysql-http-results.json](artifact-mysql-http-results.json)。

## 结果与实际范围

原 `PresalesArtifactPersistenceContractTest` 在 JDK21 Maven 定向回归中实际执行 9 项，全部通过，无失败/错误/跳过。再由其当前精确源码生成外部 MySQL 方言夹具，保留原业务断言并追加 8 个测试；JUnit 使用已缓存的匹配版本 launcher，没有修改 POM 或新增项目依赖。执行 `python3 /tmp/mateclaw-artifact-mysql-http-runner.py`，最终 exit 0，17 项发现、开始并成功，失败、abort、skip、container failure 均为 0。

| 范围 | 数量 | 断言 |
|---|---:|---|
| 原下载、预览、PPT、完整性缺口与事务回滚 | 9 | 原字节、权限、404/409、未声明与重复清单、空摘要、原数据库正文/成果/回执事实 |
| 实际数据库与迁移身份 | 1 | MySQL、自己的 schema、V219、pending 0、validate、request_hash 列宽 67 |
| 五类角色读取与预览 | 1 | viewer/member/admin/owner/system admin 均可按现有政策下载；前两类不可预览，后三类可预览；冻结数据不改写 |
| 缺少凭据与账号停用 | 2 | 401；已签 JWT 不绕过当前账号状态；冻结数据不改写 |
| 历史来源撤回、员工 wiki_disabled、KB binding 禁用、来源跨 Workspace | 4 | 先有真实成功下载，再修改 live 来源/员工事实；403 SOURCE_UNAVAILABLE，项目正文、版本和成果行保持 |

HTTP 由真实 Spring MockMvc、JWT filter、ActorResolver、Workspace 授权、PresalesService、来源读取/治理服务和真实 MySQL 执行。现有 SemanticHttpFixture 的 WikiKnowledgeBaseService、PAT、I18n mocks 保留；本轮不证明真实 Wiki 服务全部行为、PAT 认证、socket 服务器、浏览器或所有后台工具/缓存/任务来源链路。

## 失败、诊断与方言适配

首次 MySQL 实跑和诊断实跑分别为 17 项、16 成功、1 失败，exit 1。失败来自原 H2 故障注入对 DataIntegrityViolationException 的类型期待；实际 MySQL 返回 UncategorizedSQLException，诊断仅打印 SQLState HY000 和错误码 3819。原类型断言失败后，后续回滚断言没有执行，故这两次记录不能称为回滚通过。

外部方言夹具只做有记录的适配：类名、MySQL `DROP CHECK` 清理语法和精确驱动错误类型/SQLState/3819。继续要求原注入约束名，保留正文、artifact 和 receipt 断言，并新增物理 body/version 和 revision 数量不变断言。没有改产品异常政策、原 H2 期待或删除业务断言。最终该用例 SUCCESSFUL，证明所有后续回滚断言实际执行；首次和诊断失败分别归档，不改写为 PASS。

独立审阅发现首份失败日志留下夹具临时密码字段和 Spring generated-security-password；已在归档前脱敏并补强 runner。JWT、JSON password 和 generated password 形态经只统计数量的回读复核无未脱敏匹配，不输出凭据值。

## 数据库、版本与清理证据

最终服务版本 MySQL 8.0.46，固定已缓存 image `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。每次使用独立自行命名的 loopback 容器、动态端口与 `mateclaw_aq_acceptance_*` 空 schema，JUnit BeforeAll 验证 MySQL 产品、catalog 和空库；连接覆盖使用 DynamicPropertySource，实际加载 MySQL driver 和迁移路径。没有复用已有数据库。

当前 MySQL 迁移路径实际从空库初始化：Flyway history 独立回读 212 行，每行 success=1，起始 V1，末尾 V217 SQL / V218 JDBC / V219 SQL，无 BASELINE 行；当前版本 219、pending 0、validate 通过。此证据支持该候选在本次数据库版本和测试 profile 的空库初装，不是生产完整启动、旧库升级、备份恢复、Kingbase 或完整业务 V2 迁移验收；列扩宽没有接入新的请求 hash writer。

243 个源码指纹、运行 classpath 上 8217 个 class 指纹、3 个外部测试 class 指纹前后相同；数量不表示所有类均被加载或覆盖。原六个成果修复源码与上一轮实际回归指纹一致，旧 SQL/Java 迁移和冻结清单没有修改。外部当前源码、测试变体、日志、类和摘要已压缩归档并解压回读。

三个运行的凭据 env-file 都已删除，自建容器按确切 ID `docker rm -f -v`，各 exit 0，清理匿名卷；最终容器不存在已独立查询。未操作原 MateClaw 容器、用户 WIP、生产数据库或正式历史交付物。

## 独立审阅与最终门禁

`artifact_read_review` 对原断言对照、方言适配、真实 FAIL/GREEN、摘要、迁移 history、隔离与脱敏最终给出 COMMENT，0 个剩余实质问题。其未运行 Docker/Maven/gate，没有 Java LSP 诊断，不构成维护人批准或正式业务签收。

初始与最终 dev 使用 `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`；最终实际报告归档于 `artifact-mysql-http-tests/final-dev-report.json.gz`，以其中 source snapshot target_identity 为准。dev 只证明 SCAN_PASS，application toolchains 在该命令中仍 NOT_RUN；本轮明确的 9 项 Maven 与 17 项外部 MySQL 结果是分别执行的证据，不能冒充完整 commit 门禁。

当前状态 ENGINEERING_HTTP_FIXTURE_VERIFIED_UNSUBMITTED，submission_ready=false。正式 46 AC 未关闭，Kingbase、真实历史 Office、生产升级恢复、完整浏览器/后台来源链路、hash 旧回执策略、维护人批准与远端 required CI 仍待完成；完整 commit 门禁 NOT_RUN，本轮未提交或推送。历史冻结 manifest 缺少或不可验证时失败关闭的兼容风险仍保留；不得补造摘要或重新渲染来掩盖它。

后续当前源码的 latest handoff 冻结修复、真实发布流与 10 项 MySQL 实测见 [AQ05_HANDOFF_FREEZE_ACCEPTANCE.md](AQ05_HANDOFF_FREEZE_ACCEPTANCE.md)。本页 17 项及源码指纹保留为此前切片的历史证据，没有改写为新 Service 的结果。
