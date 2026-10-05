# AQ-01/AC-21：真实进程退出后的售前任务恢复

新增三个独立 JVM 的生产启动链路验证，复用已有模块启动 harness，未修改本批生产代码。真实登录、Workspace、HTTP 项目创建和生成经过生产应用服务、公共执行入口、异步事务及仓储；本地合成模型收到流式请求后保持未完成，确认 RUNNING 已提交，再 checkpoint 并 Runtime.halt(23)，不执行 Spring 关闭回调。下一进程由生产 ApplicationReadyEvent 恢复任务，第三进程检查幂等。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base/origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。改动文件为 `mateclaw-server/src/test/java/vip/mate/acceptance/ModuleStartupMatrixTest.java`、`ModuleStartupProbe.java`、新增 `PresalesRestartProbe.java`；复用生产 HTTP helper 和进程隔离逻辑，无新依赖、表、迁移或生产接口。参见 [计划](AQ01_PROCESS_RESTART_PLAN.md) 和 [独立审阅](AQ01_PROCESS_RESTART_REVIEW.md)。

## 已核验的边界

- 第一进程真实生成请求已到达 loopback 模型，恰一次请求，数据库任务 RUNNING、版本2，没有结果；回执和修订各2条。halt 退出码23，context_closed=false。
- 下一次真 main 启动后，认证 HTTP 回读与数据库内容相同：FAILED、INTERRUPTED_BY_RESTART、合法 finishedAt，版本仅增至3。其他项目/任务字段全部保持，回执和修订数不变。
- 第三次独立进程启动后，数据库原始 body 字节完全相同，版本保持3，回执与修订各2。三进程 PID 不同且使用同一个独立文件库。
- 启动时验证禁用的外部工作、数据库引擎/路径、Flyway validate及无待迁移；成功后删除整个临时沙箱。随机密码和项目私有状态不进入证据包。

首次测试因夹具 SQL 使用不存在的 id 列失败，改为真实 provider_id；第二次因恢复进程仍套用空库 setup_initialized=false 断言失败。生产初始化依据用户是否存在，因此现在 ordinary/seed 明确断言 false、recover/repeat 明确断言 true，既有空库断言保留。这两次是测试夹具问题，未算作生产缺陷。失败日志/XML/对应源码均在重跑前归档。

## 复现与检查

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=ModuleStartupMatrixTest#presalesRecoveryAcrossProductionProcessRestarts' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am '-Dtest=ModuleStartupMatrixTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

定向三进程测试1/1通过；完整矩阵9/9（8种模块组合+1个三进程用例），0失败/错误/跳过，11份当前进程报告全部回读且成功沙箱均已删除。检查结果以同目录 `process-restart-tests/results.json.gz`、XML和日志为准。归档含两轮失败、定向成功、最终矩阵逐进程输出、源码SHA-256及清单。Spotless及git diff --check通过。独立审阅 COMMENT，无剩余可操作发现；Java LSP/AST工具不可用，不代表其检查通过。

## 限制与回退

模型为 loopback 合成服务；只验证 H2 文件库、单实例先退出后重启、售前开启且语义/投标关闭组合。同步 checkpoint 缩小到已提交数据恢复，不模拟宿主断电或数据库写入丢失。halt 前仅禁用该合成 provider 以免后续启动访问消失的端口；不证明开启真实 provider 的启动探测或供应商计费语义。其他八组合验证普通聊天及工具/fallback路径，不扩大为每种组合的重启验证。晚到结果、多实例、坏版本/耗尽记录、真实厂商和 MySQL/Kingbase 重启、完整角色浏览器与正式 QA 未在本批验证。

AC-21及46项正式AC仍 NOT_RUN。本批初始dev ng6t1ipp及实现后dev q6anlwjv为SCAN_PASS/submission_ready=false；后者worktree identity为a276210f6730c47b9e584d045038e053875540f1f0cb35a5e6496bab51b0172a（证据归档前），报告已归档，全部证据落盘后再次运行最终dev。本批 dev SCAN_PASS 不等于 application-toolchains完整检查、精确暂存树commit门禁或远端 required CI；未提交或推送。本批回退仅撤销三个测试文件中的新增重启阶段及本批文档/台账条目，保留既有八组合和其他WIP。
