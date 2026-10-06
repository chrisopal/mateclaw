# AQ-09 工作台提交与下载职责工程验收

状态：本片实现及适用应用检查完成；最终文档树dev在证据收尾后执行。任务按[拆分计划](AQ09_WORKBENCH_MUTATIONS_PLAN.md)执行，范围为售前页面的提交与下载用例，非完整AQ-09或正式业务验收。

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，分支`codex/aq01b-execution`。工作目录`/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw`，开始103项dirty记录，保留此前WIP；不修改原Development检出。

## 职责边界与保持行为

提交会话承接command/save/approve/archive，下载模块承接files/preview/draft/handoff。页面负责路由、Workspace、模板布局及公共状态装配。继续消费本域presalesApi/prepareEditorSubmission/presalesError，不新建请求框架、store或第二份项目数据。

唯一saving锁、页面scope代次、原receipt缓存、acceptMutation版本接纳和编辑会话生命周期通过显式依赖共享。写入保持精确expectedVersion与字符串ID；来源限制下只允许原材料绑定/解绑及仅agentId的窄修复。批准/归档确认后再次检查目标、版本与权限。create/update成功时先解锁再重载/跳转；命令成功只关闭捕获的编辑会话。旧响应不能污染新工作区或释放替换请求的锁。409含OPERATION_REPLAY_UNVERIFIABLE仍保留草稿并禁止盲重提。

下载只在当前scope/Workspace/project与同一项目对象仍有效时创建URL；来源限制拒绝下载。JSON交接包保持原格式和版本文件名，二进制保持原Blob；preview/draft保留UNAPPROVED前缀，成功点击后1000ms释放URL。下载只是读取既有成果，不更改后端发布/审批权威。

本片无后端、迁移、依赖、门禁配置、语言包或主题改动。4255份相关输入已保存摘要用于收尾越界核对。模板/style已逐字节核验；构建和DOM测试不替代真实浏览器双主题/窄屏验收。

## 证据与已知验证过程

初始dev `s_qhirgn`：SCAN_PASS，submission_ready=false，application-toolchains NOT_RUN。旧页面补命令失败同receipt重试和批准原理由后77/77；下载成功刻画最初误把组件preview fallback当已发布文件，预期filename与原实现UNAPPROVED前缀不符。修正测试入口/预期后定向6项通过，其余75项未选中。该失败是夹具假设修正，不作为产品bug RED；原始失败日志保留。

提取后旧页面+提交模块86/86；下载模块首次26/27，发现新模块释放后保留回调仍发请求，加入口disposed拦截后27/27。主任务类型探针发现两项下载测试类型错误（URL接受Blob|MediaSource、this需显式类型），严格修正后通过。子任务还修复新增handoff类型导入、移除无用API导入和this-alias lint问题。

独立审核发现最后一次lint修正引入createElement递归mock：首次全UI1189项中1185通过/4失败。改为typed anchor.click观察数组，保持下载字节/名称/释放断言；另补create/update两个deferred保存顺序合同。修正后3文件115/115（页面81、提交7、下载27），全部UI1191/1191（0失败/skip）、Node5/5、vue-tsc、6文件非修复ESLint零警告、项目Prettier及enterprise/classic构建全exit 0。两构建均内含精度检查；保留既有大chunk告警，未提高阈值。

最终6文件：页面1638→1461行，新增usePresalesMutationSession.ts 213行、usePresalesDownloads.ts 86行；页面测试新增6项，新增mutationSession.test.ts 7项与downloads.test.ts 27项。系统生产源码总计增加122行，换取明确依赖和独立生命周期，未以缩短页面掩盖新增成本。原六个内联方法已移除，重复Blob/anchor/URL创建收敛到一个私有函数。页面仍有展示/编辑模板、统计及组装职责；完整前端结构整改并未宣称结束。

[独立审核](AQ09_WORKBENCH_MUTATIONS_REVIEW.md)最终COMMENT，无剩余可操作缺陷；早期HIGH测试递归与MEDIUM验证缺口已修复。普通tsc的Vue解析局限、最终LSP传输不可用及AST未安装不记PASS。实际vue-tsc另有完整通过证据。

准确命令/cwd/exit/耗时/6文件SHA见`workbench-mutations-tests/final-checks.json.gz`，主要命令为UI目录下：

```sh
pnpm exec vitest run src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/mutationSession.test.ts src/features/presales/__tests__/downloads.test.ts --reporter=json --outputFile=/tmp/workbench-mutations-parent-repair-targeted.json
pnpm exec vitest run --reporter=json --outputFile=/tmp/workbench-mutations-parent-repaired-full-ui.json
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesMutationSession.ts src/features/presales/composables/usePresalesDownloads.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/mutationSession.test.ts src/features/presales/__tests__/downloads.test.ts --max-warnings=0
pnpm exec prettier --config ../.quality/prettier.json --check src/features/presales/pages/PresalesWorkbench.vue src/features/presales/composables/usePresalesMutationSession.ts src/features/presales/composables/usePresalesDownloads.ts src/features/presales/__tests__/presalesWorkbench.test.ts src/features/presales/__tests__/mutationSession.test.ts src/features/presales/__tests__/downloads.test.ts
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
```

初次从根目录用pnpm --dir启动类型探针触发Corepack版本12.6.0与UI声明12.4.2不匹配，保留原错误；直接在UI目录使用现有工具链成功，没有改版本约束。子任务一条串联格式/lint日志末尾exit=0未代表前置格式成功，保留该告警并由主任务逐条检查重新验证，不采纳其聚合状态。

片后dev `j02uwns7`为SCAN_PASS/submission_ready=false。本文/台账/归档后再次运行同一`python3 -B scripts/quality/verify.py --mode dev --base origin/dev`，报告与gate.snapshot工作树身份在仓库外核对，避免回填本文改变已检tree。dev的application-toolchains是NOT_RUN；上述应用检查系独立实际执行，不替代精确暂存树commit门禁。

`results.json.gz`汇总实际结果；before/characterization/final源码和所有中间失败日志一起归档。`archives.json.gz`登记压缩/解压SHA，主任务逐项回读；初始4255份输入包含被Git忽略的前端构建输出；两主题构建更换其中100份static文件。逐项git check-ignore确认，仅排除这些既有生成目录的忽略产物后，真正受保护源码/配置摘要全部稳定（精确数量见results.json）。这是证据清单分类修正，不修改门禁规则或豁免源码变更。最终模板/style原字节一致。

## 剩余范围与回退

本片没有提交、推送或部署。正式46个AC状态不升级；真实浏览器、角色/模型、维护人/QA、精确暂存树commit门禁与远端required CI仍开放。完整V2业务迁移、坏/耗尽历史RUNNING恢复、Service其余用例及领域DTO不是本片完成项。

开发回退以本片before页面/测试为准，仅移除本片两模块与新增独立测试，保留查询、编辑、执行会话拆分和请求摘要修复。无数据库回滚。
