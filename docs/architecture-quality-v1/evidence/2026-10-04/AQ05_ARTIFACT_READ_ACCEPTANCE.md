# AQ05 成果读取与冻结摘要工程修复

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`，固定 origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。当前候选为未提交工作树，不能用 HEAD tree 冒充候选。计划见 AQ05_ARTIFACT_READ_PLAN.md，63 个源码/测试/日志归档及摘要见 artifact-read-results.json；最终源快照检查报告另存 artifact-read-tests/final-dev-report.json.gz。

## 真实问题与结果

已发布下载原来只比较 artifact 行的 content_base64 和该行 digest；同时更换内容与 digest 可返回替换字节。相同 release 下未在冻结 manifest 声明的行也能被下载，空 manifest 摘要没有被校验。真实 MockMvc/H2 回归 6 项中新增 3 项失败，三种缺口均返回 200。预览先校验声明清单，再通过 raw-content 查询读取请求文件，仍可读未声明文件；追加反例的 22 项组合中有 1 项失败，实际返回 200。

修复后的下载和预览只读取唯一 manifest 条目，按 project/release/filename 精确查询唯一存储行，解码后同时比较行 digest 和冻结 sha256。替换内容与行 digest、空冻结摘要返回 409 ARTIFACT_DIGEST_MISMATCH；未声明文件返回 404 NOT_FOUND；重复条目无法证明唯一性，返回 409 ARTIFACT_MISSING。读取不改 manifest、不修复存储字节、不重新渲染。新增 HTTP 测试也回读了替换的存储内容，证明失败读取没有将其自动改回。

这修复 R-05 / 架构规格不变量 4 的工程缺口，但不等于对真实历史发布集合、Office 或正式 AC-23 的完整验收。缺少/歧义/不可验证 manifest 的历史文件现在会失败关闭，不能通过补造摘要或重建文件消除该风险。

## 职责与简化

- 新增 package-private `PresalesArtifactReader`，负责已授权上下文中的清单校验、发布/预览原字节及存储 PPT 读取；依赖既有仓储和领域 Rejected，复用 Renderer.digest，不调用 render，不新增依赖或 Spring bean。
- PresalesService 保留身份/Workspace/来源、发布条件、预览 admin、事务、回执及 legacy status/code/message 映射。对切片前源码逐段比较，get、releaseGate、replay、receipt、hash 完全相同；构造器参数保持。1228 行降为 1194 行，本片净减少 34 行。
- 删除唯一生产调用被替换后的 `findContents` 重复 SQL。仓储六处原值断言改由 `find` 返回的 StoredArtifact.contentBase64 验证，保留原字节、三键与事务断言，没有删除测试期待。
- 候选/PPT 的基数、双摘要、空 expectedDigest、严格 Base64、错误消息由 8 个提取前实际服务刻画测试保护；授权由真实 HTTP/H2 集成测试分别保护，不将 Mockito 读取验证称作授权证明。

## 实际证据

| 阶段 | 命令 / 结果 |
|---|---|
| 初始 dev yp_letsh | `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`；exit0 / SCAN_PASS，submission_ready=false |
| 发布缺口 RED | ArtifactPersistenceContractTest：6/3 failure/0 error/0 skip，exit1 |
| 提取前刻画 | ArtifactReadContractTest + RepositoryTest：13/0/0/0，exit0 |
| 预览缺口 RED | ReadContract + PersistenceContract + Repository：22/1 failure/0 error/0 skip，exit1 |
| 合并 GREEN | Presales* + ProjectAuthorityFenceDatabaseTest + WorkbenchArchitectureTest + SemanticCoreArchitectureTest：370 总数，369 通过，0 failure/error，1 既有 PPT 环境跳过，exit0 |
| 编译架构检查 | 2872 生产类，runtime 876 / Controller 3 / pure semantic 122；17 测试无失败或跳过 |
| 显式开发格式化 | scoped Spotless apply，6 个本片文件；与只读 check 分开 |
| 项目格式检查 | `mvn -B -pl mateclaw-server -Dquality.base=origin/dev spotless:check`；119 文件，0 需修改，exit0 |
| 切片 dev fo5j16zc | exit0 / SCAN_PASS；工具链 NOT_RUN，submission_ready=false |

Java 命令使用安装的 JDK21。合并回归：`mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test`。未跳过断言；定向回归不等同完整 commit 门禁。日志中 26 个 Bearer 等凭据形态已脱敏，压缩档/解压字节摘要及来源摘要均保存并回读。

独立 artifact_read_review 对六个 Java 文件给出 COMMENT，0 个可操作缺陷；其独立回读 RED 与刻画 XML，未跑 Maven/gate/formatter。Java LSP 返回不适用的 tsc/no tsconfig 信息，故 Java 诊断 NOT_RUN；AST 工具缺失亦 NOT_RUN，不能假称诊断为零错误。此意见不构成维护人批准或正式业务验收。

## 剩余、版本与回退

无数据库模式变更，旧 SQL/Java Flyway、冻结清单、gate 和更严格语义架构测试保持 HEAD 原字节。此前 AQ05/AQ07 证据对应各自当时快照；本片是后续候选，不能将旧源码摘要冒充当前源摘要。当前成果修复尚未在 MySQL/Kingbase 或真实历史发布集合运行；上一批 MySQL 29 项是列表/V218 fixture，不能用于本片完整性修复。46 正式 AC、角色/生产并发/浏览器/模型/Office、维护人批准及远端 required CI 仍未关闭。

AQ20 请求 hash 与旧回执兼容策略尚未接入，未因本片改变。当前完整 commit 门禁 NOT_RUN，submission_ready=false；没有提交/推送。回退须使用正常、可审阅的 Git revert 并重跑门禁；回退会重新暴露冻结摘要与未声明文件读取缺口，应明确标记为风险，不重新写历史内容规避问题。

后续验证记录：[AQ05_ARTIFACT_MYSQL_ACCEPTANCE.md](AQ05_ARTIFACT_MYSQL_ACCEPTANCE.md) 对相同修复源码完成真实 MySQL 24 项组件验证，合成 Office 字节回读与有限结构可读。该记录不改写本文阶段快照；完整 MySQL HTTP、Kingbase、真实历史与人工 Office 验收仍待完成。
