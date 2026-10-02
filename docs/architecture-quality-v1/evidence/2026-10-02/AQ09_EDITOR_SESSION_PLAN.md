# AQ-05/09 编辑会话职责切片计划

起始 HEAD fb4b9ce7e38f4c8debb10fb5b2b3de0e577294be；独立工作树干净，原项目WIP保留。base origin/dev保持实际基线；开始dev记录另存。沿用RULES/REVIEW_CHECKLIST与ADR12/13边界。

## 边界与范围

将编辑会话的打开、草稿初始副本、baseline响应对齐、dirty、来源/statement选项及选择、迟到选项拒收、丢弃确认、关闭与卸载保护移入域内usePresalesEditorSession。封装generation，提供捕获当前会话有效性的predicate给页面save，不让页面操作裸计数。复用editorSubmission纯规则与presalesApi只读选项、现有范围捕获/核验函数；确认UI文案由页面注入，不建立第二套scope或通用事件框架。页面保留编辑标题/模板、route/Workspace注册、load reset、save/create/update/command、版本/receipt/409、批准与员工执行。

来源撤权的编辑状态清理归会话；页面仍处理证据/生成/展示预览清理。保留受限employee/material修复入口与命令allowlist、scope代次、同一ID离开后返回、最新草稿确认和save忙碌检查。不改变来源权限/事务/wire/原历史JSON语义、字符串ID或i18n。无需依赖、控制面或数据库变更。

## 行为保护与验证顺序

先在旧实现补真实beforeunload/route-update丢弃拒绝刻画，运行全部53项既有组件测试；其既有选项迟到成功/失败、草稿更新时确认、route-leave取消、旧save/409/来源撤权、Workspace回调已锁定主行为。实现后同套组件与全售前回归，补effectScope层的captureSession/close/reopen/dispose、选项/确认竞态和baseline对齐合同。每完成职责执行dev；最终nonfix lint/vue-tsc/固定格式、精确树完整门禁、独立源码/测试审阅、正常提交推送与远端PR回读。

## 风险和回退

同一session predicate在异步save结束前必须保持原scope/generation/open约束；选项的finally不能清新版busy，确认不能丢弃新草稿。composable watcher注册顺序不应削弱同步来源清理。onScopeDispose移除window listener并使捕获状态失效，不取消已送出的服务器mutation。可整体回退page接入与composable，无数据迁移；真实角色/浏览器/后端并发/正式QA仍另行验收。
