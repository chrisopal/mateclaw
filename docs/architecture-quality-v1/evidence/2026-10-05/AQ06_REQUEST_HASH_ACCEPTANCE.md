# AQ-06 请求摘要碰撞修复工程验收

本片于2026-10-05开始，2026-10-06完成工程证据收尾；目录按开始日期保留。范围是操作回执摘要及客户端冲突处理，正式AC、业务签收和远端required CI均未关闭。

HEAD：`6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`；base origin/dev：`ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；分支`codex/aq01b-execution`。工作目录`/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`，本片开始100项dirty记录，保留全部累计WIP。主检出未修改。本片未提交、推送、部署或操作生产数据。

## 问题、实现及边界

旧Service将JSON envelope转为String再UTF-8编码，孤立高/低代理单元会替换为ASCII问号，导致同operationId的不同请求误回放旧成功。按[实施计划](AQ06_REQUEST_HASH_IMPLEMENTATION_PLAN.md)先刻画旧行为并取得真实HTTP失败，再接入已有V2算法。

- Service只序列化一次原envelope，新回执统一写`v2:`摘要。Create仍用固定record；Command保留projectId、expectedVersion、payload扩展、null/省略及字段顺序。这不是原始HTTP字节哈希。
- 回放按已存格式严格分派。legacy摘要不同仍返回`OPERATION_CONFLICT`；摘要相同但请求含ASCII问号或孤立代理单元，以及未知/畸形摘要格式，返回409 `OPERATION_REPLAY_UNVERIFIABLE`。合法代理对、字面转义、U+FFFD和全角问号不误拒。
- 旧回执不能证明原始输入，不从响应/当前项目猜测原请求，不自动升级或重写。安全legacy原响应保持，Command仍经现有权限裁剪。授权→来源检查→回放及事务顺序保持。
- 前端复用现有conflict处理，支持nested/top-level错误码；创建/更新时保留草稿并禁用盲重提，不自动换operationId。员工不可用等错误分类保持。
- Generation的task.requestHash使用Jackson byte-writer，保持原算法。历史含问号生成任务仍回放且零重复enqueue/模型交互；内部操作回执使用Service新V2。

## 修改文件与简化

| 文件（相对项目根） | 本片职责 |
|---|---|
| mateclaw-server/src/main/java/vip/mate/presales/PresalesService.java | 原位接入双读/新写；重复序列化收敛为一次，898→911行 |
| mateclaw-server/src/main/java/vip/mate/presales/PresalesRequestHashV2.java | 复用既有算法，仅加纯UTF-16歧义检测；58行 |
| mateclaw-server/src/test/java/vip/mate/presales/PresalesRequestHashReplayTest.java | 16项真实HTTP/H2合同 |
| mateclaw-server/src/test/java/vip/mate/presales/PresalesVersionInputContractTest.java | 新写V2断言、历史生成byte-writer回放及含问号内部写入 |
| mateclaw-server/src/test/java/vip/mate/presales/PresalesCommandKindContractTest.java | 新写V2断言 |
| mateclaw-server/src/test/java/vip/mate/presales/PresalesSqlListingTest.java | 保留旧算法刻画，Service拒绝异请求；使用既有V219 |
| mateclaw-ui/src/features/presales/shared/state.ts | 错误码并入既有conflict分类 |
| mateclaw-ui/src/features/presales/__tests__/state.test.ts | 两种错误形状 |
| mateclaw-ui/src/features/presales/__tests__/presalesWorkbench.test.ts | 创建/更新真实页面草稿与Save防重提 |

无新依赖/bean/表；本片没有修改Flyway、冻结清单、共享legacy算法、Generation生产类或门禁阈值。V219与V2基础算法来自前片。测试更改已单独技术审核，保留独立手工legacy seed和旧碰撞事实，没有删断言让检查变绿。

## 回归结果

| 检查 | 实际结果 |
|---|---|
| Java baseline | 5类108/108 |
| 最终UTF16真实HTTP RED | 原实现12项中9失败，预期409实际200 |
| Java最终定向 | 8类143/143 |
| Java扩展回归 | 54类713项：712通过、1既有条件skip |
| 三独立JVM真实重启 | 1/1 |
| UI baseline → RED → green | 79/79 → 新增4项失败（79未选中）→83/83 |
| 全UI / Node | 1151/1151 / 5/5，无失败或skip |
| 类型/ESLint/Prettier/精度 | 通过，非修复检查 |
| enterprise/classic构建 | 均exit 0，既有大chunk警告保留 |
| 正式base Spotless | exit 0 |
| 隔离真实MySQL | 17/17，无失败/中止/skip |

最初UTF8属性名孤立代理测试会被Jackson parser提前400，不能证明业务拒绝；保留该失败日志。最终UTF-16BE JSON加ASCII转义仍走真实HTTP converter/Controller/Service，以预期409实际200取得RED，未改宽断言。现有UTF8合同继续保留。

Java唯一skip为`PresalesPresentationCompilerTest`缺`PRESALES_PPT_SKILL_ROOT`。首次省略`quality.base`的Spotless碰到无关plugin-api存量格式失败，日志保留；指定正式base后通过，未改无关代码。

后端命令在项目根运行，均使用JDK21：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl mateclaw-server -am test -Dtest=PresalesRequestHashReplayTest,PresalesRequestHashV2Test,PresalesRequestHashMigrationTest,PresalesCommandKindContractTest,PresalesVersionInputContractTest,PresalesSqlListingTest,PresalesGenerationControllerTest,PresalesGenerationCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl mateclaw-server -am test '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl mateclaw-server -am test '-Dtest=ModuleStartupMatrixTest#presalesRecoveryAcrossProductionProcessRestarts' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl mateclaw-server -am -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

前端命令在mateclaw-ui运行：

```sh
pnpm exec vitest run src/features/presales/__tests__/state.test.ts src/features/presales/__tests__/presalesWorkbench.test.ts --reporter=json --outputFile=/tmp/request-hash-ui-green.json
pnpm exec vitest run --reporter=json --outputFile=/tmp/request-hash-ui-full.json
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/presales/shared/state.ts src/features/presales/__tests__/state.test.ts src/features/presales/__tests__/presalesWorkbench.test.ts --max-warnings=0
pnpm exec prettier --config ../.quality/prettier.json --check src/features/presales/shared/state.ts src/features/presales/__tests__/state.test.ts src/features/presales/__tests__/presalesWorkbench.test.ts
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
```

构建含精度检查与vue-tsc。MySQL runner、适配源码及launcher已归档，可检查实际参数与JUnit逐项结果。

## 数据库与重启证据范围

隔离MySQL 8.0.46，已有image `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。独立随机容器/loopback端口、新空schema、utf8mb4_bin；真实Flyway至V219、validate通过、pending=0、列宽67。适配仅改测试类名、动态数据源及空库身份校验，再加1项迁移断言；原16项行为断言完整保留。真实JWT/Workspace/来源服务与MySQL，继承Wiki/PAT/I18n mocks仍存在。这不是完整生产HTTP服务器、真实历史样本或多实例验证。

结束后自有容器及卷删除exit 0，精确ID inspect确认不存在，临时凭据文件删除；密码未输出，JWT等日志脱敏。251份源码与8289个classpath class摘要核对稳定；这是输入身份清单，不是已加载类或测试覆盖率。

重启使用三个真实生产入口JVM和同一独立H2文件：seed PID50594提交RUNNING/version2后halt，模型收到1次请求；recover PID50974为FAILED/INTERRUPTED_BY_RESTART/version3；repeat PID51074保持version3、revisions2、receipts2且无二次写入。后两次真实HTTP回读且context关闭。这里version2是项目版本，不能混淆为摘要V2。loopback模型不证明真实供应商计费/多实例恢复；耗尽或坏历史版本恢复仍开放。

## 归档、审核及门禁

[独立代码/测试审核](AQ06_REQUEST_HASH_REVIEW.md)为COMMENT，无可操作缺陷；不是维护人批准或QA签收。机器摘要见`request-hash-integration-tests/results.json.gz`，包含9文件SHA和实际阶段计数。`request-hash-tests`的435份、`request-hash-ui-tests`的22份及集成归档有对应`archives.json.gz`，压缩/解压SHA均回读核验；保留旧基线、RED、失败格式尝试和最终源码。

初始dev任务`_8z8koko`、后端收尾`bg2hn93j`均SCAN_PASS、submission_ready=false。dev的application-toolchains为NOT_RUN；上列手工执行记录独立列出。本文及归档结束后执行最终dev，并在仓库外保存报告及`gate.snapshot`同一工作树身份比对，避免回填本文改变已检tree。精确暂存树commit门禁NOT_RUN，没有用dev代替提交权限。46个正式AC全部保持NOT_RUN；本片只追加AC-20、AC-21、AC-26的局部证据。

## 兼容风险、回退及后续

部分真实同请求legacy重试只要含问号/孤立代理也会409：只有旧摘要时无法证明原输入，不能恢复旧成功。这是明确兼容收紧。若业务必须恢复这些回执，需要可信原始请求档案逐条核验；现有项目和响应不足为证。

正式发布须先让所有reader支持双读，再启用V2写入；本片没有完成生产分阶段发布开关/编排或多实例回退演练，部署前必须解决。V2已写入后不能直接回退旧binary，不可截断成64字符，也不可缩回列宽。开发环境回退只应用本片反向patch并保留之前WIP；生产应回到仍支持双读的修复版本。

Kingbase真实运行、真实历史请求、浏览器/角色/两主题、QA/维护人、正式AC、提交门禁和远端强制仍未完成。完整Presales V2/Delivery业务schema迁移不在本片，仍缺最终规格；售前页面command/save/download拆分、Service其余用例/DTO以及耗尽RUNNING记录的受控恢复继续开放。
