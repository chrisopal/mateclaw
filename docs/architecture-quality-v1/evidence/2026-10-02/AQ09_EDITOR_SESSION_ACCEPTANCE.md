# AQ-05/09 编辑会话职责工程验收

起始HEAD fb4b9ce7e38f4c8debb10fb5b2b3de0e577294be，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；独立工作树开始干净，原项目WIP未触碰。计划见AQ09_EDITOR_SESSION_PLAN.md。

## 完成的边界与兼容

域内usePresalesEditorSession负责一次编辑打开的generation、初始JSON副本/dirty、baseline响应对齐、只读source/statement选项和选择、丢弃/关闭确认及window unload保护。generation私有，captureSession返回绑定原page scope/generation/open且未disposed的predicate。save仍由页面捕获scope和当前kind，复用predicate拒收旧会话响应；不会把有效predicate当作服务器授权。

页面保留canWrite/来源修复命令allowlist、保存busy/conflict、create/update/command、expectedVersion/operation receipt、批准、员工继续与路由/Workspace守卫注册。确认文案和ElementPlus交互由页面注入；只读选项仍走本域presalesApi，复用原page scope，不新增通用框架/事件层/第二套权限或状态仓。

关闭/reopen、同ID离开返回、晚到选项成功/失败/finally、确认后draft或busy改变保持拒收。来源撤权同步清理derived draft与statement选项，保留employee/material修复草稿；页面仍负责证据/生成/预览清理。卸载使捕获session无效并删除window listener，不声称撤回已送mutation。所有默认JSON/历史扩展/字符串ID/来源选择revision/evidence语义沿用纯规则；wire、授权、409、模板/CSS/i18n key/控制面/依赖/迁移未改。

工作台2642→2531行；会话组合函数194行含typed接口、内部状态、生命周期与窄公开返回，不以搬整个页面协调代替职责设计。load仍由页面协调原状态reset。

## 实际验证

生产修改前新增两项真实Vue/ElementPlus刻画：dirty draft阻止beforeunload、unmount移除listener；同route不同project的route-update guard取消保留原路由与草稿。旧实现55/55通过，原53个组件用例字节未改。

迁移后原售前七文件125/125通过。新增17项effectScope合同覆盖关闭重开capture、同ID页面代次、busy/canWrite/受限修复、旧选项finally不能清新版loading、statement精确选择、撤权/项目消失同步清理、丢弃期间draft/busy/scope变化、取消/干净关闭、baseline响应watcher、disposed迟到选项与unload清理。首次新测试16/17：新增预期漏掉纯规则已有reason:''，核对原editorSubmission后补完整新增预期，未改生产行为、原测试、配置或删除断言，FAIL日志保留。

最终全售前八文件142/142通过，零失败/skip。实际vue-tsc/nonfix四文件lint/固定Prettier check均exit0；dev8ak2dhe5、81s2c48p、9jd25c31 SCAN_PASS只证明扫描/门禁自测。命令与源码/日志hash见editor-session-test-results.json；完整精确暂存树、正常commit/push实际报告在PR5记录。

独立workbench_confirmation_audit按spec/源码/测试审阅，APPROVE bounded editor-session extraction，无阻断，确认scope/session/open、draft/busy确认、同步撤权、保存执行边界及watcher顺序未出现冲突写入。诊断无新增错误；plain tsc有旧.vue测试import解析局限，实际Vue typecheck与完整树gate为提交证据。该工程审核不替代正式QA。

## 剩余、风险和回退

编辑字段模板、其他任务/项目展示与用例协调仍有混合职责；PresalesService命令/持久化聚合、V2独立对象/单写迁移、生产同构事务/并发、多方言备份恢复、角色API/真实浏览器两主题/窄屏/Workspace、后台重启/工具/图/cache/历史/导出、真实模型、维护人/业务QA及remote required CI仍未闭合。组件与effectScope替身不能证明服务器授权和实际浏览器退出行为。AC13/16/30/31保持NOT_RUN。

可回退页面接入与会话组合函数，恢复原会话状态/保护；无数据库或生产操作。新增disposed防护只拒绝卸载后结果，不扩大任何权力。本片不宣称AQ09、P0或整体目标完成。
