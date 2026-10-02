# AQ-02 来源受限工作台工程回归

Base/HEAD aa0be8523ccd4a11b18afdd921118f2e74ed04ec，独立工作树起始干净；初始dev SCAN_PASS，mateclaw-quality-ry6i2opq。受限摘要此前没有明确提示，员工执行入口仍可用。组件负例先复现（/tmp/mateclaw-aq-restricted-ui-red.log：1失败、10通过），不是后端或客户验收。

## 实现与边界

PresalesProject增加可选boolean sourceAccessRestricted，原完整响应兼容。复用宿主i18n新增中英文提示；来源不可用不再仅凭空数组解释为无业务数据。canGenerate统一控制按钮和handler；canWrite保持材料和员工配置修复。受限或项目清除时清空来源详情、预览、来源编辑框、statement options；项目/材料修复保留。下载/精确handoff/预览/证据的异步响应同时核对捕获的project对象、Workspace与路由，不能在受限响应之后重开旧内容。实际任务轮询返回HTTP403时清除先前project及来源表面并显示真实错误，不填空成功。state错误分类新增accessDenied，409冲突政策保留。未调整后端授权、审批、依赖、数据库、门禁或测试配置。

独立/root/restricted_ui_review曾指出来源编辑框和迟到下载两个HIGH问题；均修复并增加回归。最终限定审阅无阻断；独立component+state 22项、Vue类型检查通过。一般tsc/LSP不能正确处理.vue模块，未把该工具不适配报为类型通过或产品缺陷。正式维护人/QA/合并批准不由工程审阅提供。

## 检查与浏览器证据

售前3个test files共28项通过，0失败；包含受限提示、材料修复恢复、迟到证据、来源编辑框清理、迟到file/handoff下载、真实HTTP403轮询清除旧内容与403/409区分。命令在mateclaw-ui运行：

```
node node_modules/vitest/vitest.mjs run src/features/presales/__tests__
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```

日志 /tmp/mateclaw-aq-final-ui-tests.log（本轮目录另保存component-tests.log）、/tmp/mateclaw-aq-final-ui-types.log。直接调用已安装项目工具；没有通过pnpm版本不匹配提示建议的ignore/warn放宽配置。全量门禁仍按正常工具链运行。

restricted-ui-browser保留实际脚本、隔离router/Pinia入口、源码哈希、截图、结果及原脚本定位失败。HeadlessChrome154、1440×1000、light主题；实际Vue组件与HTTP客户端，但API由Playwright route模拟，标SIMULATED_HTTP_ACTUAL_COMPONENT；没有真实登录、角色策略或服务端持久化。检查受限提示可见/不越过视口、生成禁用、员工配置可用；点击材料tab/可见选框/实际option/保存后提示消失、生成恢复。第一轮点选框内部input被占位层挡住，是定位错误；改点可见wrapper后原业务断言通过，保留previousFailures。Vite首次依赖优化重载并非产品缺陷。

coverage.json为声明范围3控件12检查：8 PASS、4 NOT_RUN，无记录格式错误，审计结论INCOMPLETE。未测项包括键盘/生成交互与完整布局组合；台账和截图不自动证明业务验收。sources.json为实际受测源码指纹，不能证明生产部署。复跑：恢复两个harness文本至mateclaw-ui对应路径，启动本地vite5197，playwright-cli open about:blank后以run-code执行flow.js.txt；保存输出/截图后清理本轮临时入口。

## 未完成项与回退

真实服务端/身份/完整角色、MySQL/PostgreSQL、窄屏/暗色/中文布局、deferred预览与object URL清理回归、实际员工重绑定保存、源类型/并发导出和正式QA仍待验证。刷新后GET403无法自动提供绑定修复界面，现保持真实错误与不泄漏；需要独立设计可授权元数据/修复读取契约，不能绕过后端GET拒绝。默认文件库测试隔离仍有存量。AC-07/08/09只追加工程证据，正式状态保持NOT_RUN。

本片可单独revert，无数据迁移或发布字节重渲染；回退会恢复受限状态提示/旧来源UI与迟到响应问题。实际dev/commit/push报告、checked tree在PR交付记录登记，SCAN_PASS或本报告不替代提交门禁。

## Final format and type review

Initial normal commit gate 2db85qdh failed ui-format; no commit was created. Fixed Prettier now formats the complete workbench. Explicit DTO/form fields replace any; opaque snapshots stay unknown. All 290 literal bilingual calls now use domain-local messages inheriting host locale; host locale files have no HEAD diff. Wire behavior is preserved; no runtime API validation added. Independent expanded review found no blocker, component/state23 and Vue typecheck passed; full presales28 passed including global locale switch. Browser simulation rerun on final sources passed; fingerprints/screens refreshed. Real server/roles and formal QA remain NOT_RUN.
