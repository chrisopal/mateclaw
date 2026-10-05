# AQ-09 项目台账展示职责工程证据

起点 HEAD 08acb836ee6ad3ec2713410d90b4e23c52ea219c，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；初始工作树 clean。本片覆盖台账展示边界，不宣称整个售前重构或正式 AC-30 已完成。

## 职责和兼容

PresalesProjectLedger 承担筛选表单、项目摘要列表、阶段/日期显示与分页展示。query/ownerFilter/statusFilter/page 使用 required model 连接父页原状态；search/reload/open 事件交给原查询会话和路由。没有 API、权限、Workspace、缓存、store 或异步生命周期迁入组件。复用已有成员/状态展示函数和同域 CSS，无新增依赖。

保持 fragment，不新增布局容器；专属台账样式随组件移动，navigation-link 规则进入既有共享样式，当前消费者仍只有父页和台账。分页先更新 page 再 reload；搜索重置 page 1；阶段与 Dashboard 双向联动不变；字符串 ID、未知状态、缺失成员/日期与错误/空态保持。

页面 1226 → 1062 行，新增组件 207 行、共享 CSS 增加 4 行，总生产行数增加 47。收益是减少页面展示职责并保持查询生命周期单一来源，不以页面变短冒称总代码减少。回退恢复原台账及样式、删除新组件；无后端/API/数据库/权限变更。

## 行为刻画与工具链

- 在旧页面新增 4 项实际交互刻画后，`pnpm exec vitest run src/features/presales/__tests__/presalesWorkbench.test.ts`：89/89。首跑夹具对台账 pageSize20 和汇总 pageSize100 返回同一数据，导致错误的末次请求观察；按真实请求分别返回后通过，原失败日志保留。
- 提取后及最终格式收敛后，`pnpm exec vitest run src/features/presales`：20 文件、500/500，0 failures/skips。原测试正文逐字节保留，仅新增 125 行；没有删除或修改旧断言。
- 新夹具可选 params.page 的类型错误已按 API 默认值补 `?? 1`；最终 `pnpm exec vue-tsc --noEmit`、适用文件 `eslint --max-warnings=0` 与 `prettier --check --config ../.quality/prettier.json` 均 exit 0。
- 第一次显式 formatter 漏带项目配置造成格式噪声；已恢复旧测试正文和无关模板原字节，最终 diff 不包含这次噪声。没有改 formatter 配置或规则。
- dev e2adjpdo、_72ylxyk、ibr3ktvt 均 SCAN_PASS/submission_ready=false；diff --check 通过。精确 tree 的 commit/push 检查仍以真实门禁及 PR 后续记录为准。
- 首次 commit 门禁 8_e5pnb8 因未跟踪 `.omx/state/project-ledger/ralph-progress.json` 返回 BLOCKED/submission_ready=false，未生成提交。该文件是本轮视觉 skill 状态，已连同 verdict 归档到工作树外，保留失败报告，不修改 ignore、门禁或其他用户文件。

## 浏览器工程验证

使用 Playwright CLI + Chromium，合成夹具分别渲染起点源码的旧内联模板/样式和真实新组件。导入项目原 main.css 与 enterprise 样式，通过实际 applyUiProfile 设置 classic/enterprise；视口 1440×900 与 390×900。

四组前后对照的几何、文案、间距、字体尺寸/字重均一致。少量表体文字颜色出现 1–2/255 通道差异，变化 17–2363 像素；对照的两次旧页面也重现 790 像素差异，且无运行中动画，因此不宣称截图逐像素相同，也没有为消差改生产样式。视觉 verdict 99/pass，PNG、布局 JSON、旧旧对照与 verdict 已归档。

四组真实浏览器交互均核对 search 的 Unicode 文本/精确 owner ID/REQUIREMENTS、page:2、两个字符串 ID 的链接/双击事件，以及无页面水平溢出；另核对空态和加载失败文案不同。选择框输入被占位层遮盖时改为点击真实可见控件；夹具外部调试事件输出改为换行，未放宽原溢出断言。失败记录保留。

这是组件呈现与交互工程证据：合成项目、事件接收器，不是真实 API/授权/业务数据，也不替代正式 AC-30 或客户/维护人签收。

## 审核、归档与剩余项

独立 /root/project_ledger_review：COMMENT，无可操作发现；核对旧测试逐字节相同、page 更新先于 current-change、父级权限/Workspace/session 不漂移。AST 不可用；未重复运行 tsc-based LSP，不计为静态诊断通过。技术审核不是维护人批准。

[42 项归档](project-ledger/project-ledger-regression.tar.gz) SHA-256 `c25e64ae987ec6e962e2672d4b728ee20fc4f2f68294a3585bda7a8ad27696bd`；包含旧/新测试、失败和修正日志、源码哈希、浏览器截图/控制对照、可复现夹具和审核记录，0 处凭据脱敏。manifest 保存原始/归档摘要并已逐项读回验证。

前一提交 run37376295073 已完成 success，验证 job 和 Required 汇总均通过；下载报告确认 source HEAD08acb836、merge7d2ed26f、tree da79a9e3，详细证据见 [远端CI记录](AQ08_REMOTE_CI_ACCEPTANCE.md)。这是前一提交结果，不能替代本片新 tree 的 CI。等待旧 run 完成后才推送，避免并发策略取消有效验证。完整 DTO、V2 单写迁移、坏/耗尽任务恢复、正式 46 AC 和远端 required 强制生效继续开放。
