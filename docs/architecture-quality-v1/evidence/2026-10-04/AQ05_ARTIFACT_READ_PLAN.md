# AQ05 成果读取职责与冻结摘要修复计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2；固定 origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；保留 AQ05/AQ20/AQ07 全部 WIP。初始 dev yp_letsh exit0/SCAN_PASS，提交资格 false。

检查发现真实缺口：发布下载仅比较解码内容与 artifact 行自身 digest。若行的 content_base64 和 digest 一起变化，下载不会比较已冻结 release.files.sha256，可返回另一份内容；未在发布 manifest 声明的同 release 行也可被下载。预览和 handoff 已有 manifest 校验，发布下载存在不同规则。

R-05、架构规格不变量 4 和 AC-23 要求保留发布原字节及摘要；这里修复读取验证，不能通过重新渲染、重写发布 manifest、补造历史内容来让校验通过。先增加真实 MockMvc/H2 行篡改与未声明文件反例并执行 RED，再保留现有 HTTP 身份/Workspace/来源/发布条件检查并修复发布读取。无法证明属于该发布 manifest 的文件失败关闭；未知文件仍 404 NOT_FOUND，不增加文件输出。

职责切片：PresalesService 保持 get/授权/发布条件/事务及 legacy 异常映射；package-private PresalesArtifactReader 负责已授权上下文中的候选清单、发布文件、存储 PPT 的原字节读取与完整性检查。只依赖既有 PresalesArtifactRepository 和领域 Rejected，复用现有 Renderer.digest（不调用 render）。不新增 Spring bean、依赖、Controller SQL、框架注册或回调层。数据库仓储继续仅返回原存储事实。

发布读取以精确 project/release/filename 查询，必须属于唯一 manifest 条目且字节摘要同时等于存储 digest 与冻结 manifest sha256。缺失行仍 404；重复 manifest/存储行无法证明唯一性，使用既有 ARTIFACT_MISSING 409；存储或 manifest 摘要错误为 ARTIFACT_DIGEST_MISMATCH 409。候选校验和 presentation 读取保持既有缺失/重复/摘要/空 expectedDigest/非法 Base64 行为与错误信息。原字节不改写。

先补行为刻画：候选清单基数、空清单、双摘要、严格 Base64、PPT 空 expectedDigest 与错误码；执行现有 ArtifactPersistenceContract/Repository/Integration 回归。保留新增修复反例的真实 RED，不将既有绕过行为锁为正确。再实现一个完整职责切片，显式 scoped formatter 与只读 check 分开，重跑 Presales* + 权限数据库 + 编译架构检查，再执行 dev。

本片对历史无法验证的发布文件可能收紧读取；不自动迁移、改旧发布、改变旧回执策略或生产数据。46 正式 AC、真实旧发布集合/Office/生产恢复仍需独立业务核验。独立审阅确认未绕过授权、未调用 renderer 重造文件、manifest 和存储均参与验证；仅提交工程证据，不宣称正式 AC-23 完成。

进一步追踪发现 admin 预览先校验声明清单，却仍通过另一条 raw-content 查询返回未声明文件；追加真实 HTTP RED（声明文件正常存在），保持全部原预览授权/发布条件/候选校验顺序，再复用 releaseFile 原字节校验。findContents 的唯一生产调用被替换后删除该重复 SQL 方法；仓储测试六处原值断言改为从 find 的 StoredArtifact.contentBase64 取值，保持原字节、精确三键和事务断言，没有删除断言或降低期待。此阶段不修改数据库模式。
