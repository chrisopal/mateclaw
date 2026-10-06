# AQ-09 售前工作台查询生命周期拆分

查询状态、能力/列表/详情加载、成员目录、portfolio分页和所属请求取消移入 `usePresalesWorkbenchQuery`。`PresalesWorkbench.vue` 从1874行降到1696行；新模块284行。修复清空Workspace时旧请求被取消、旧finally跳过，导致loading一直为true的问题。模板和style与本批基线逐字节相同，不把行数减少解释为完整结构整改已完成。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。初始94条WIP保留。计划见[AQ09_WORKBENCH_QUERY_PLAN.md](AQ09_WORKBENCH_QUERY_PLAN.md)，源码范围为上述两个生产文件及 `presalesWorkbench.test.ts`、`workbenchQuery.test.ts`；精确摘要见 `workbench-query-tests/source-manifest.json.gz`。未增加依赖、改变后端或数据库。

## 职责与兼容

查询模块拥有查询refs和两个AbortController，页面继续拥有命令、生成/取消、回执、scopeGeneration、编辑与未保存guard、轮询及预览。四个类型化回调仅连接范围失效、轮询复位、界面复位和项目接纳；不传入万能页面context。回调在watch首次load前所需变量均已初始化。

保持dirty先阻止load、固定Workspace/project、旧请求取消、source撤权先清空敏感project后取repair、受限repair元数据/空集合/opaque binding校验。repair只有当前范围内的403且canWrite才读取，不将其视为新的授权；原错误仍透传。保留列表筛选/分页、portfolio每页100与最多50页及去重/数量一致性校验，复用既有loadPortfolio而不复制算法。页面命令scopeGeneration、409草稿保护、轮询与异步accept守卫均保留。

本批行为改变：空Workspace分支返回前清除loading；模块dispose同时取消全部所属请求并清除三种loading。没有修改服务端权限、版本/事务、幂等或发布字节合同。原API decoder与页面严格repair校验并非完全等价，本次搬移保留后者，不借去重放宽绑定角色/空ID限制。

## 已执行验证

- 原售前全部394/394通过后才改代码。新增真实RouterView回归在旧实现上执行2项均失败：capabilities挂起后清空Workspace，旧请求晚到成功/失败均未关闭遮罩。RED定向筛选报告67项中2失败、65未选中，不当作全量运行或新增skip。
- 搬移/修复后396/396；补20项模块合同后416/416，0失败/skip。覆盖Workspace ABA、dirty、停用/缺Workspace/404、200条portfolio分页、成员失败/晚到、dispose以及repair敏感信息清除/拒绝。
- 最终全UI Vitest：138个文件，1115/1115，0失败/skip。legacy Node：2个文件、5/5，0失败/skip。
- vue-tsc、四文件非修复ESLint（零警告）、项目配置Prettier check和Snowflake精度检查通过。enterprise与classic构建均exit 0；构建日志保留大chunk告警，不据此改阈值。
- 主任务独立回读源码SHA及baseline压缩文件：template/style均字节相同。检查命令没有自动格式化或更新snapshot。

UI目录实际命令（完整日志与JSON在workbench-query-tests）：

```sh
pnpm exec vitest run src/features/presales --reporter=json --outputFile=/tmp/workbench-query-final.json
pnpm run typecheck
pnpm exec eslint --max-warnings=0 src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesWorkbenchQuery.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/workbenchQuery.test.ts
pnpm exec prettier --check --config ../.quality/prettier.json src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesWorkbenchQuery.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/workbenchQuery.test.ts
pnpm exec vitest run --reporter=json --outputFile=/tmp/workbench-query-full-ui.json
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
```

Node命令由Python枚举test目录下两个实际test/spec文件传入，未使用shell通配扩展。根目录 `python3 -B scripts/quality/verify.py --mode dev --base origin/dev` 初始yl84bzyn和实现后egmybmql均SCAN_PASS/submission_ready=false。后者受检identity `13c3c1718507bd27dcc6f9ecebd84fc53906abd2d29a287a5409a48fe557344c`，为最终证据/本文之前；architecture-ratchet与guard-self-tests通过，application-toolchains为NOT_RUN。本文收尾后再次执行dev并回读精确工作树；上述手工应用工具链不能将dev变成commit门禁。

[独立审核](AQ09_WORKBENCH_QUERY_REVIEW.md)结论COMMENT，四文件无可操作发现；普通tsc不能完整诊断Vue、AST工具未安装的局限保留，实际vue-tsc已通过。

## 未完成范围与回退

本批真实浏览器、两主题/窄屏、真实后端角色矩阵、并发HTTP、外部模型、正式QA与远端required CI均未执行。Vue组件/HappyDOM测试与构建不能代替它们。46项正式AC仍NOT_RUN。页面命令编排、完整领域DTO/服务结构、版本化hash回放兼容、V2迁移及耗尽版本历史记录恢复仍需后续切片；本次未修改它们。

回退按本批baseline-page快照恢复页面查询逻辑、移除新查询模块和本批新增回归，或以独立补丁只逆转搬移并保留空Workspace bugfix。不要重置其他累计WIP。无生产或DB操作，本批未提交/推送；精确暂存tree的commit门禁未运行，submission_ready=false。
