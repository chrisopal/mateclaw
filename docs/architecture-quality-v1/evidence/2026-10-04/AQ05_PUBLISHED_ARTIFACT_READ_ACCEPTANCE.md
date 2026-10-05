# AQ05 / AQ02 已发布成果与冻结 fit 来源读取验收

本职责切片工程回归通过，未提交，不关闭正式 AC。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`。最终工作区扫描身份及 task ID 见下述归档；HEAD tree 不是 WIP 测试 tree。原 checkout 未操作，此前 WIP 保留。

## 修改边界

- PresalesService 仅 artifact/preview 两处：完整已发布冻结来源走历史读取，不完整、遗留及候选仍执行原 releaseGate；与本片前源码相比净加 2 行，1173→1175。新建、审批、发布、回执、hash 和事务不变。
- PresalesSourceAuthorization 复用公共 get 的真实 actor、Workspace、当前及冻结材料权限；严格认定冻结 schema、身份、manifest、来源集合、fitRefs 和支持的 status，追加 fit-only 证据的持久化复核及当前员工、来源权限。缺失或坏来源失败关闭，不回填冻结数据。
- SourceGovernanceReadService/Repository 复用现有无条件只读端口，返回 Optional 来源 ID：核验 Workspace、graph、冻结 KB、active KB、evidence 与 snapshot 同 graph、撤回/exclusion、严格 WIKI_RAW、raw 同 KB 未删除，缺失或歧义拒绝。端口提供事实，caller 负责授权；不依赖 semantic mutation flag，不要求 graph enabled 或当前 ontology。无新 bean、表、迁移、依赖；临时 Query provider 和三处构造适配已撤销。
- ArtifactPersistence 追加六项合同；实际 g1 新增真实独立 fit 资料→导入→证据→SAVE_FIT_GAP→创建/批准/发布，再测试撤回、删除、exclusion 各自对下载、预览、指定 handoff 返回 404，项目 body 及原字节不改，finally 恢复测试库。新增缺失证据合同及五项只读事实合同；原断言保留。

## 验证证据

JDK21 执行：

```sh
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
```

均 exit 0。从实际 Running 及对应 47 份 XML 汇总：400 项，399 通过，0 失败/错误，1 项既有 PresalesPresentationCompilerTest skip。其中 Artifact15、SourceAuth8、Integration8、Governance9 全通过；包括真实 ArchUnit 与投标交接消费者。编译不等于 Java LSP，后者 NOT_RUN。显式 scoped apply 与只读 check 分开；额外不带 base 的全 reactor check 命中未改 PluginContext 存量格式失败，日志保留，不冒充通过或改写存量。

MySQL 8.0.46 独立空库：24/24 实际 JUnit 成功，0 失败/abort/skip/container 失败，包含真实 JWT、Workspace、来源服务、实际 g1、15 项成果合同与八项既有来源/角色/identity 回归；Wiki/PAT/I18n 既有 mocks 保留。V1→V219 实际 212 条 Flyway history 全部成功、无 BASELINE、pending0、validate 通过。外部夹具适配限于类名/连接、MySQL DROP CHECK、真实 HY000/3819 异常；body/version/revision 回滚断言保留。

248 份源码与五份外部 class 执行前后指纹匹配。原 runner 的 12079 宽 class 库存含空 classpath entry 引入的 7990 主 checkout 相对文件，不能称 runtime 证据；单独校正为实际绝对 classpath 目录 4089 class，均匹配，仍不是全部类已加载的 coverage。原结果不重写，见 `fit-mysql-classpath-inventory.json.gz`。自己的容器按精确 ID 清理 exit0，独立 inspect 确认不存在，凭证文件已删除。归档 gzip 回读双摘要一致，无 JWT 形态残留。

独立原生审阅 `/root/artifact_read_review`：COMMENT，最终源码未发现新增实质缺陷；实际回读 47 XML、MySQL24、归档双摘要及当前源/外部 class，指出并校正库存范围。此意见不是维护人批准。

## 失败与限制

先有真实 RED（历史实时 gate 三项失败及 g1 失败），中间 390/MySQL24 未覆盖 fit-only 来源，独立 P1 后真实撤回期望 404 却 200。最初 fit 夹具缺 reason/productVersion 各 400，修正后取得真实产品 RED；Query provider 尝试因 runtime flag 关闭返回 404，已替换为既有持久化端口。首次 port400 仅旧 malformed 期望被脚本误改而失败，对照 HEAD 恢复原 403，最终完整回归通过。首次 MySQL 探测命中初始化临时 socket 实例，空库创建失败、测试未运行，容器清理后 TCP 正式实例探测重试成功。所有失败日志/XML 保留，不能用中间 GREEN 或夹具错误作最终证明。

文档写入首次长 inline Python 因编码 SyntaxError 在任何写入前失败，随后归档输入缺失；改用显式 patch，未改产品或历史结果。该次 dev 不能当最终文档树证据，已在全部文本写入后重跑。

完整 commit 门禁 NOT_RUN / submission_ready=false。Kingbase、历史真实数据升级/恢复、完整启动禁用模块 HTTP 组合、浏览器/客户/live model/真实 Office、Java LSP 和 46 正式 AC 均 NOT_RUN；维护人批准及远端 required CI NOT_VERIFIED。standalone semantic=false 提供只读 bean 的测试不替代完整启动组合。旧冻结结构不完整的 artifact/preview 继续实时 gate，不能承诺全部历史样本可读。

按单片 diff 回退，无 schema、旧迁移或已发布字节变化；旧路径将恢复历史读取阻断及 fit-only 来源缺口，不能静默安全回退。hash 旧回执政策尚待选择，不在本片整合。

证据：[实施计划](AQ05_PUBLISHED_ARTIFACT_READ_PLAN.md)、[机器结果](published-artifact-read-results.json)、[最终门禁及工作区 identity](published-artifact-read-tests/final-gate.report.json.gz)、[架构静态扫描](published-artifact-read-tests/final-architecture.report.json.gz)、[归档清单](published-artifact-read-tests/final-manifest.json.gz)。
