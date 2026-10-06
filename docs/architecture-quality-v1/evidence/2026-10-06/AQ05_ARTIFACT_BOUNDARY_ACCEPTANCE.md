# AQ05 制品物化与交付边界工程记录

基线 HEAD `2fb5577d9d766dbf284c99a9bba612512f4b0f4b`，tree `bc168a16910e7118b7ab05cb173ece910c5eb423`；初始工作树干净，dev base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。关联 AQ05 的用例/制品职责，提供 AC15/20/23/24 的部分工程证据，不改变正式AC的NOT_RUN状态。

## 边界与变化

既有包内Reader重命名并扩展为PresalesArtifacts，承担候选模板/presentation选择、生成、编码、摘要、存储、清单与release归属重绑定，以及草稿路由、候选预览、发布文件和冻结handoff交付。复用全部原Reader算法；没有增加包装层、Spring bean、公共API、依赖、线程池、表或迁移。

Service保留原admin/viewer、项目加载、全项目materials与历史来源授权、live releaseGate、事务/回放/CAS、saveItem、批准/发布状态与snapshot捕获。Controller和Bidding继续走原公共Service入口，不能绕过授权访问包内模块。preview仍先admin再get，PUBLISHED完整冻结快照才跳过新发布gate；preview全清单验证、download选定文件验证、handoff先验文件再读snapshot的差别保持。

Service从793降至711行；旧Reader89行变为179行Artifacts，合计生产代码增加8行。收益是制品规则集中与重复Document构建消除，不声称总行数下降或全部Service结构整改结束。公共Service构造器/方法签名和注解完全不变；JDK21解析AST核对所有旧Reader方法体不变，以及Service除明确制品方法/构造器外的全部方法不变。删除旧storedPresentationArtifact私有转发方法及旧Reader源码；最终定向编译目录只有PresalesArtifacts.class，没有旧Reader.class。

## 行为保护与真实结果

| 检查 | 结果与证据边界 |
|---|---|
| 原实现已有artifact/snapshot/handoff合同 | 101/101，无失败/跳过 |
| 原实现增加14次刻画后 | 115/115；锁定renderer参数、清单顺序/size/digest、临时→最终release重绑定、存储PPT先于insert、坏presentation拒写、空文本artifactId旧分支、草稿三格式存储路由/渲染/404、五种来源拒绝先于产物访问 |
| 首次提取后宽回归 | 3 failures + 1 error：旧完整性测试反射已删除私有方法，NoSuchMethodException；原断言未修改，失败归档保留 |
| 测试harness修复 | 经公共draftArtifact入口；保留原三键、缺失/重复、双摘要、空expected摘要、Base64严格失败、字节独立性全部断言；未恢复生产兼容壳 |
| 最终售前/投标/架构回归 | 88类982项，981执行通过、1既有环境跳过，0 failures/errors；其中Snapshot77、ArtifactRead8、ArtifactPersistence15、WorkbenchArchitecture12均通过 |
| dev | 初始ijp2sxnq、最终24kiz6n1均SCAN_PASS/submission_ready=false；只证明扫描与门禁自测 |
| 格式/静态差异 | Spotless apply及git diff --check通过；无旧Reader生产/编译残留；公共API、事务注解和旧读算法AST比对通过 |
| 独立技术审核 | presales_artifact_design只读设计/实现/测试审核，指出反射阻断；修复后限定回读确认全部旧断言保留、无新增阻断；不是维护人批准 |

唯一跳过为PresalesPresentationCompilerTest，要求PRESALES_PPT_SKILL_ROOT；未设置该环境。本片没有把它记为真实Office验收。ArtifactPersistence的真实HTTP/H2路径保留候选文件与project/revision/operation一起回滚、来源/角色拒绝和冻结历史读取；单元mock的访问顺序证据与HTTP/H2证据分开。新测试的原实现快照和115项JUnit、最终982项JUnit及失败反射记录可回读。

实际命令：

```sh
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full '-Dtest=PresalesArtifact*Test,PresalesReleaseSnapshotContractTest,PresalesHandoffReadContractTest' -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full '-Dtest=Presales*Test,Bidding*Test,WorkbenchArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -B -Dquality.base=HEAD spotless:apply
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

正常commit/push精确树与远端CI在实际完成后记录于PR和外部交付记录；本文件不预签尚未执行的完整门禁。没有修改门禁、断言阈值或存量baseline。

## 取舍、未测与回退

保留gate→render→读取presentation→insert/manifest→saveItem→reassign→snapshot的事务内顺序，以及isTextual与!isBlank两个历史分支。没有把渲染移出command事务；长事务仍需独立的输入捕获、版本/权限重验和失败制品清理协议。不能用本次职责提取宣称性能问题已修复。

未执行真实浏览器/外部模型、已安装PPT技能、真实历史Office交付、MySQL/Kingbase生产切换及正式业务签收；既有环境证据不自动升级为本片验收。完整单写V2/Delivery契约、其余Service/页面职责及AQ10继续开放。

本片可还原源码和测试harness，无数据格式或迁移回退；不要回滚累计PR已产生新数据的V220等迁移。来源授权、原字节/摘要、发布状态、公共接口与事务不能在后续“简化”中移除。

## 证据包

[归档](presales-artifact-boundary/presales-artifact-boundary.tar.gz) / [摘要清单](presales-artifact-boundary/presales-artifact-boundary-manifest.json)，215文件，SHA-256 `e60c5c958e80c71793efb0a0d7351ac32506084152a42320133cfbbf74544224`。48处测试凭据脱敏；原日志SHA与归档SHA分别记录，逐文件解包回读校验通过。JUnit按对应实际执行日志选择，不把旧的未执行报告混入当前测试数。
