# AQ-09 员工执行会话职责拆分工程验证

员工目录加载、生成弹窗状态、生成/取消请求从PresalesWorkbench.vue移入usePresalesExecutionSession。页面1696→1638行，新模块158行；模板28349字节及style4676字节与本片基线完全相同。本批保留原行为并增加模块dispose后的结果拒收，不声称发现旧实现缺陷；新增刻画在原实现上已经通过。

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，初始98条累计WIP保留。计划[AQ09_EXECUTION_SESSION_PLAN.md](AQ09_EXECUTION_SESSION_PLAN.md)。四个源码文件及SHA在execution-session-tests/final-evidence.json.gz：页面、新composable、presalesWorkbench.test.ts及新executionSession.test.ts。未修改后端、DB、依赖、模板布局或项目质量规则。

## 边界和行为保持

复用既有泛型Scope接口模式与同一个页面scopeGeneration；没有新增通用请求框架或独立授权规则。新模块拥有employees/employeeError/employeesLoading、员工请求序号、generation/generationOpen及其加载/执行方法；页面继续拥有编辑command/save、router/Workspace guards、版本比较acceptMutation、轮询和查询。通过类型化getter/ref及明确回调共享saving、receipt和结果接纳，不能由模块自行扩大权限。

Workspace/project在发送时固定；旧请求在工作区A→B→A后成功或失败均不能覆盖当前项目、弹窗、错误或解锁新请求。生成继续使用原expectedVersion与同输入receipt，无modelId覆盖；取消保留字符串taskId及相同请求回执。生成409保留输入和回执，更换目标产生新回执；取消409不替换project。sourceRestricted、dirty、saving及权限守卫顺序保持。员工请求序号使同范围晚到结果不覆盖后发请求，dispose拒收晚到结果。

初始化使用延迟loadEmployees getter，构造EditorSession时不会访问后声明执行会话。第一次vue-tsc发现editor→loadEmployees→execution.isDirty→editor类型推断环（TS7022/7023），已用明确Promise<void>/boolean返回类型切断；失败日志initial-types-failure.log.gz和最终成功日志同时保留，不删除断言或添加any。页面末尾immediate watch仍在依赖初始化后运行。

## 已执行证据

- 原售前416/416通过并归档，然后新增6项真实RouterView刻画；原实现页面73/73，迁移后页面73/73。
- 新模块26/26；最终售前448/448，0失败/skip。6项页面增量与26项模块增量不重复计入总数。
- 全UI Vitest139文件1147/1147；legacy Node两个文件5/5，均0失败/skip。
- 四文件非修复ESLint零警告、项目配置Prettier、vue-tsc --noEmit、Snowflake精度检查均exit 0。enterprise与classic构建均exit 0，保留大chunk告警，不改阈值掩盖。
- 主任务回读四源码SHA并独立比较模板/style字节；git diff --check通过。

准确命令、cwd、exit、耗时见final-checks.json.gz；主要命令如下（UI目录，precision/dev除外）：

```sh
pnpm exec vitest run src/features/presales --reporter=json --outputFile=/tmp/execution-session-final-presales.json
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesExecutionSession.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/executionSession.test.ts --max-warnings=0
pnpm exec prettier --config ../.quality/prettier.json --check src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesExecutionSession.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/executionSession.test.ts
pnpm exec vitest run --reporter=json --outputFile=/tmp/execution-session-full-ui.json
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
bash scripts/check-snowflake-precision.sh
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

初始dev rdw4jh8a、执行片最终dev 1v2_sz08均SCAN_PASS/submission_ready=false，后者target_identity为1c88718d388b23042d5ce02756416e04f95dbf27dca1d1e63eb87765b00a07ba（归档/本文前）。architecture-ratchet与guard-self-tests通过，application-toolchains为NOT_RUN。应用检查系手工实际执行；证据文档收尾后再跑最终dev并核对工作树，不能把dev当commit门禁。

[独立审核](AQ09_EXECUTION_SESSION_REVIEW.md)为COMMENT、无可操作发现。Vue普通tsc诊断局限和AST未安装不记通过；实际vue-tsc另有成功证据。

## 剩余范围与回退

真实浏览器/两主题/窄屏、后端角色矩阵、真实模型、正式QA、精确暂存tree commit门禁和远端required CI均未执行。全部46正式AC保持NOT_RUN。本批没有提交、推送或部署。

页面command/save、下载逻辑和完整领域结构仍可继续拆分；旧操作回执摘要碰撞仍待接入修复，下一片设计和独立审核见[AQ06_REQUEST_HASH_COMPATIBILITY_DESIGN.md](AQ06_REQUEST_HASH_COMPATIBILITY_DESIGN.md)，其中JDK/Jackson探针不是业务修复。完整V2迁移和坏历史版本恢复继续开放。

回退只恢复本片baseline页面员工执行方法及对应测试，移除新composable；保留上一片查询拆分和空Workspace遮罩修复，不重置其他WIP。无DB/生产数据操作。

后续状态（2026-10-06）：上文所列Service摘要碰撞已在下一片完成工程修复与回归，见[AQ06_REQUEST_HASH_ACCEPTANCE.md](AQ06_REQUEST_HASH_ACCEPTANCE.md)；保留本片完成当时的历史状态，不据此关闭正式AC。
