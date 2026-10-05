# AQ-01 / AC-05 完整应用启动与依赖不可用工程验证

## 范围与结果

新增 `ModuleStartupMatrixTest` 与 `ModuleStartupProbe`，真实 `SpringApplication(MateClawApplication.class)` 扫描全部生产组件，八种 semantic/presales/bidding 开关组合全部通过。没有修改生产源码、运行权限、API、迁移、依赖或既有检查阈值；只扩大测试覆盖。两个测试文件不复制业务实现，复用 AuthService、WorkspaceService、真实登录/授权/Controller/持久化路径。原主 checkout 与累计 WIP 保留。

首轮全关闭1/1，第一轮完整矩阵8/8（48.619秒）；独立审阅要求补齐投标实际禁用语义后，最终完整矩阵8/8（50.639秒，0失败/错误/跳过）。前两轮日志与XML先归档再运行下一轮，不能与最终结果混算成17个独立场景。

| 语义/售前/投标 | 投标 capabilities | 售前项目新建/回读 | statements | handoff-options |
|---|---|---|---|---|
| 000 | 404 BIDDING_DISABLED | 模块关闭 | 不适用 | 投标关闭 |
| 001 | 200 enabled | 模块关闭 | 不适用 | 409 PRESALES_UNAVAILABLE |
| 010 | 404 BIDDING_DISABLED | 200/200 | 409 SEMANTIC_DISABLED | 投标关闭 |
| 011 | 200 enabled | 200/200 | 409 SEMANTIC_DISABLED | 200 空发布列表 |
| 100 | 404 BIDDING_DISABLED | 模块关闭 | 不适用 | 投标关闭 |
| 101 | 200 enabled | 模块关闭 | 不适用 | 409 PRESALES_UNAVAILABLE |
| 110 | 404 BIDDING_DISABLED | 200/200 | 200 空声明列表 | 投标关闭 |
| 111 | 200 enabled | 200/200 | 200 空声明列表 | 200 空发布列表 |

## 身份、隔离与行为边界

工作目录 `/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`；HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。实际待检文本输入身份、最终dev任务及日志见 `module-startup-tests/final-manifest.json.gz` 和 `final-report.json.gz`。源码SHA与各组合真实结果见 `module-startup-results.json`。

每组合独立JVM、清空继承环境、独立cwd/user.home/tmp/Skill/上传/工作区/payload路径、随机loopback HTTP、全新H2数据库。classpath排除全部test-classes目录，仅复制探针自身class，不让其他测试@Configuration参与扫描。断言ApplicationReadyEvent、活动context、真实数据库URL/H2身份、Flyway validate/current219/无pending、条件Bean及公共Agent/dispatcher可装配。setup/status实际200并在夹具创建前initialized=false。随后仅在该私有H2通过真实服务创建普通用户及owner工作区，HTTP登录token仅在进程内使用，HTTP请求经过真实安全链；不mock权限/数据访问。

`await-language-selection=true` 是正式现有入口，避免默认seed启用cron/渠道。探针还关闭非目标插件与语义抽取/投标调度；启动后检查cron/channel/MCP/provider的enabled行均0。这不能保证全部网络出口均被隔离，也不代表正常seed初始化路径已验收。HTTP夹具创建后不再声称setup仍为未初始化。

成功时context.close返回并写结果后显式System.exit(0)，不声称所有调度线程自然终止。父进程最多等待120秒，超时终止已知后代及子进程并确认退出。结果复制后删除本次临时根（不跟随目录软链接）；失败保留定位。最终八个成功目录均核验不存在。启动前删除旧result.json，避免本次失败借用旧成功结果。

## 审核、检查与证据

- 独立审阅指出投标flag绑定不足、成功目录泄漏与旧结果混用：已分别补真实HTTP合同、成功自动清理、启动前移除旧结果，并增强强杀后退出检查。生产规则不为测试改变。
- 所有进入证据目录的日志/XML均经脱敏后gzip并校验解压SHA；生成密码值是 `[REDACTED]`，保留提示文字便于辨认日志来源。原始target日志为本地忽略输出。独立审阅文本与更正见 `independent-review.txt.gz`。
- Java21编译及实际矩阵执行、正式base Spotless、适用ArchUnit、git diff --check、最终dev报告分别归档；dev仅SCAN_PASS，不是提交许可。精确命令及计数以归档实际报告为准。
- 默认Maven测试发现包含 `*Test`，本测试自动进入既有Java检查，无新开关或绕过入口。

## 剩余项与回退

正式AC-05仍NOT_RUN，增加工程证据链接但不替代领域开发/QA签收。默认完整seed初始化、普通聊天/真实模型执行、多角色/历史项目、MySQL/Kingbase、浏览器和真实交付均未在本片验证。其余45项正式AC状态不变；完整commit门禁、维护人批准、远端requiredCI仍未完成。本轮未提交/推送。

回退仅删除新增测试与本片文档/索引记录，无生产数据或迁移回退。默认完整Java检查增加八次独立启动，当前测试约51秒，跨平台/CI资源成本尚未实测；失败保留的沙箱需按日志路径人工定位清理。
