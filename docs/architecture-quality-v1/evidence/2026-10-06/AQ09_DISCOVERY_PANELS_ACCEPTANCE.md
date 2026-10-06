# AQ-09 材料、需求与能力面板工程验证

按[计划](AQ09_DISCOVERY_PANELS_PLAN.md)拆分售前工作台三个内联业务面板。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，分支`codex/aq01b-execution`，仅`/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`，初始110项dirty保留。

## 改动边界

PresalesWorkbench保留页签、当前Workspace/项目、权限派生、编辑/执行/请求会话以及clarificationFilter的生命周期。材料、需求澄清、能力匹配分别由PresalesMaterials/PresalesRequirements/PresalesFitGap展示并发出具体事件。子组件不调用API/store/router、不缓存权限或复制项目；材料撤回ID保持string，需求/澄清/材料/能力证据事件使用既有领域DTO。

澄清列表过滤派生计算移入需求组件，filter本身通过必需受控model回传父级。因此项目切换与页签显示仍保持原选择，UNKNOWN/历史未知状态仍计入未答复。材料WITHDRAWN禁用撤回；只读、归档、来源限制及确认/执行权限仍消费原canWrite/canGenerate/canApprove，不新增领域授权政策。

三个组件复用现有workbenchSections.css和宿主i18n，与Overview/Solutions/Outputs边界一致。页面style块逐字节不变，没有改语言包、主题token、后端、迁移、依赖或门禁配置。页面1461→1226行；3组件62/189/67行，生产源码总计增加83行，来自显式props/emits和组件边界，不能以页面变短宣称系统代码减少。旧内联模板与过滤计算已删除。

## 测试与工具链

拆分前原81项页面合同81/81通过；补4项刻画后只选新增4/4（81未选中，非全量完成）；拆分后页面85/85。四项锁定：未知澄清计入OPEN及跨项目保留filter、撤回材料禁用与精确字符串ID提交、安全能力文本与原记录证据抽屉、只读用户三面板写操作禁用。旧81项断言未修改。

首次全UI1195/1195，随后仅将新emits收窄具体领域DTO并显式格式化；最终再次全UI1195/1195（0失败/跳过），确认最终源。vue-tsc、5文件ESLint零警告、项目Prettier非修复检查、Node5/5、enterprise/classic构建均exit0。两构建内含精度检查；既有大chunk告警保留，没有提高阈值。源码摘要、每条实际命令/cwd/耗时/exit在`workbench-sections-tests/final-checks.json.gz`。

主要命令在mateclaw-ui目录：

```sh
pnpm exec vitest run src/features/presales/__tests__/presalesWorkbench.test.ts --reporter=json --outputFile=/tmp/workbench-sections-extracted.json
pnpm exec vitest run --reporter=json --outputFile=/tmp/workbench-sections-final-full-ui.json
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/presales/pages/PresalesWorkbench.vue src/features/presales/components/PresalesMaterials.vue src/features/presales/components/PresalesRequirements.vue src/features/presales/components/PresalesFitGap.vue src/features/presales/__tests__/presalesWorkbench.test.ts --max-warnings=0
pnpm exec prettier --config ../.quality/prettier.json --check src/features/presales/pages/PresalesWorkbench.vue src/features/presales/components/PresalesMaterials.vue src/features/presales/components/PresalesRequirements.vue src/features/presales/components/PresalesFitGap.vue src/features/presales/__tests__/presalesWorkbench.test.ts
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
```

初始dev f3evxu4a、片后f0f9a3x8为SCAN_PASS/submission_ready=false。归档/文档完成后再次执行`python3 -B scripts/quality/verify.py --mode dev --base origin/dev`并在仓库外核对gate.snapshot；不回写文档使被检tree失效。dev application-toolchains标为NOT_RUN；以上命令为单独实跑，不冒充commit精确暂存树检查。

## 独立复核和浏览器范围

独立代码复核见[AQ09_DISCOVERY_PANELS_REVIEW.md](AQ09_DISCOVERY_PANELS_REVIEW.md)。浏览器首次夹具结果未采纳为双主题/像素一致通过：before漏澄清列，classic为自定义配色且未设置真实html data-ui-profile；额外全局workbenchSections.css/safe-content会掩盖scoped样式问题。随后首次自动提取正则跨越pane，错误取到Overview，也已拒绝。

主任务要求并回读修正：按name精确截取完整原始pane；before使用scoped共享CSS，after只使用真实组件样式；移除自创主题和全局补丁，以项目applyUiProfile(document.documentElement, profile)激活真实enterprise/classic。最初无效verdict、主题夹具及错误提取脚本单独归档，不能充当最终证据。修正后第一次截图仍被fixture的sticky控制栏遮住材料标题/绑定按钮；尽管PNG字节相同，主任务实际看图判为revise，移除仅fixture的sticky后才重拍。

最终真实enterprise/classic × 1200×800桌面/390×844窄屏四组before/after共8张裁剪面板截图，主任务PIL逐像素比较四组均无差异且每组PNG SHA相同；主任务view_image确认材料标题/绑定按钮无遮挡。desktop截取1146×1223；enterprise窄屏336×1377，classic窄屏336×1400。JSON记录html profile、面板尺寸、页面无水平溢出及真实主题背景色（enterprise rgb245/247/250、classic rgb246/241/234），safe-content为pre-wrap。窄屏表格仍按原设计内部横向滚动，不宣称所有列同时可见。

主任务最终visual-verdict为98/pass，仅评价本片“有数据面板的前后渲染保持”；8张PNG、8份观察JSON、完整临时harness源码、独立像素比较、无效阶段及最终verdict均已归档到workbench-sections-tests。原临时output/playwright/discovery-panels已在累计提交收口时逐字节备份移出工作树，恢复位置和SHA见cumulative-submission/cleanup-manifest.json；正式归档未删除。空态全矩阵、完整应用壳、真实项目/权限后端/模型/客户业务签收未由此浏览器夹具验证，正式AC-30状态不变。

## 风险、剩余范围与回退

页面仍负责项目头部、登记列表、对话框及各会话装配；本片完成三个业务面板边界一致化，不代表完整AQ-09或所有Service/DTO整改。正式46项AC状态不变，完整V2/Delivery规范迁移、坏/耗尽RUNNING恢复、实际业务/维护人/QA及远端required CI继续开放。

回退恢复归档before页面/页面测试并移除3个新增组件；保留已完成的查询/编辑/执行/提交/下载会话拆分及后端发布授权修复。无数据库动作。本片未提交/推送/部署；精确暂存树commit门禁NOT_RUN。
