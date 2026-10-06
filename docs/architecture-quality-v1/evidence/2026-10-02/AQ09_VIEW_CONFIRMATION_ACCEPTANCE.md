# AQ-09 来源查看与确认范围工程验收

起始 HEAD `60d73b77f9434bf19ebaccacb4fe7f91881509b1`、base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；独立工作树起始干净，原项目 WIP 保留。计划见 [AQ09_VIEW_CONFIRMATION_PLAN.md](AQ09_VIEW_CONFIRMATION_PLAN.md)。本片支持 AQ-04/AC-13 请求范围、AQ-01/AC-21 晚到结果及 AQ-09 页面职责，不代表完整 P0 或业务 QA 完成。

## 问题与职责

原证据和幻灯片读取只固定 Workspace/项目与项目对象，没有固定最新选择；逆序完成会把第二选择覆盖为第一选择，旧预览错误也污染最新视图。归档与发布确认在 await 后从页面重新取当前项目：A 项目的归档确认可以向切换后的 B 发出归档。命令旧响应即使不写项目仍返回 true，关闭替换编辑器；旧错误及 finally 也会写替换界面的 conflict/error/saving。项目 update 存在相同关闭/错误窗口。仅比较 IDs 无法识别 A→B→A 或同项目重载。

来源查看搬入 148 行域内 `usePresalesSourcePreview`，保留原证据不可用元数据、错误映射与 draft SVG Blob 格式；分别跟踪证据/预览选择，关闭、来源受限、范围变化及 scope disposal 使迟到成功和错误失效，及时释放对象 URL。现有 file/evidence API 未提供 AbortSignal，本片是接受端失效防线，不宣称传输已取消。没有全局 store 或通用请求框架。

页面保留能力、来源/repair-context 白名单、命令和表单策略。同步范围 generation 覆盖 Workspace/route、reload 与 unmount；command/update/create/generate/cancel 成功、错误和忙碌清理都绑定捕获范围。旧命令返回 false，不关闭新编辑器；旧 finally 不解除新操作忙碌状态。较旧 mutation version 不回退项目，并通过原冲突提示保留草稿、阻止盲目重提。合法来源修复命令仍允许恢复读取，不套用轮询的限制恢复规则。

归档/发布确认捕获项目与版本，确认后复核范围、版本与既有能力；不能换目标或隐式升级到新修订。编辑会话及草稿快照固定 discard/options/save，当前 scope 的409与回执重用保持。员工选项取最新请求，生成目标在 await 前设置；保存期间不打开替换编辑/生成界面。下载成功和错误也复核当前范围/项目对象。不改批准角色、API/wire shape、ID、数据库、历史字节或依赖；布局与 i18n 保持。表单布局、其余聚合职责和业务验收还未完成。

## 验证与独立审核

修改前原五十项通过。首轮新增五项的旧代码运行55项：四个真实红例、51通过（证据逆序、预览逆序成功、预览迟到错误、归档切换目标）。新命令两个用例首轮因测试tab选择不准确失败；修正选择器后，在旧生产实现独立运行这两个，均以关闭新编辑器/写入旧409失败，33个其他用例未选择，不算通过或完整门禁。失败原日志保留，不将夹具失败冒充生产红例。

最终 `npm test -- --run src/features/presales` 五文件 **77/77**，零失败。覆盖现有50项、最新选择、关闭/卸载 URL、A→B→A、当前与过期确认、旧 command/update success/409、替换操作忙碌、较旧结果保留新版与草稿、discard期间草稿变化、重开material选项及来源受限。API使用受控替身，真实 Vue/ElementPlus 渲染。测试装配由直接 Workbench 改为 RouterView（原装配不注入 matchedRoute，不能证明 route guards），新增外部 route、reactive Workspace 和真实 before-switch 回调保存；实际 route-leave 取消用例证明保留脏草稿，原断言不删除或改弱，runner/config不改。

最终 `npm run typecheck`（vue-tsc）、四文件 nonfix ESLint `--max-warnings=0`、固定 `.quality/prettier.json` 的 Prettier check 均退出0。首次类型失败来自新演示夹具遗漏必需字段、captured editor kind 的分支收窄，以及 Element Plus 本地声明把 MessageBoxInputData 与 Action 交叉；分别补完整夹具、按捕获 kind 分支、在单一测试 confirmed() seam 使用精确 SDK 返回类型断言。未新增 any、未改依赖声明/生产类型/tsconfig或门禁。源码和 raw 日志 hash 见 [机器证据](view-confirmation-test-results.json)。dev SCAN_PASS 只证明增量扫描和门禁自测，提交/推送按后续真实精确树报告。

独立 `workbench_confirmation_audit` 先定位确认/命令/update/discard/版本窗口，再按 code-reviewer prompt 审阅最终五文件，无阻断，批准本工程切片。审阅核验权限、repair allowlist、回执、expectedVersion、409、URL与夹具控制面增量，并读取77项日志；未自行运行构建/测试。LSP的plain tsc不能解析原有Vue import（TS2307），实际vue-tsc退出0；不改配置掩盖。正式维护人/QA签收仍待完成。

## 未测、风险与回退

真实角色/API/服务端授权变化、浏览器两主题/窄屏/Workspace导航、多方言/生产同构、异步重启、真实模型和正式 QA **NOT_RUN**。组件替身不能证明业务权限或重启结果；AC台账保持原状态。范围失效只阻止页面接纳/后续动作，不撤回已发送服务端 mutation，也不替代服务端幂等/CAS/授权。版本变更后的旧确认需要重新发起；较旧成功回执仍需刷新对比，不能把本地拒收说成服务端操作没发生。

revert本片即可回退（不改数据库），同时恢复已复现的选择覆盖、确认目标漂移及旧命令污染。后续继续表单/用例职责拆分、售前服务与单写迁移，以及真实环境的角色/重启/QA验收。
