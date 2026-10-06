# AQ-05/09 方案与评审成果展示拆分计划

起始 HEAD cc9596b91932029145b80b0f433b135f23f58351；工作树干净，base origin/dev 保持既有真实基线。开始 dev w97yyfs7 SCAN_PASS，非提交通过。

## 职责与范围

PresalesWorkbench 仍同时承担方案覆盖/对比展示、方案版本列表、评审及发布表格，以及授权、确认、请求和工作区生命周期。将两个完整展示职责分别放入 PresalesSolutions、PresalesOutputs；不搬整个页面聚合。领域内组件使用 type-only DTO、既有 i18n 和显示标签；发出 typed generate/revise/evidence/download/approve/publish/handoff/editor 意图。页面继续执行既有用例，授权、source restriction、scope/session、CAS/receipt、确认版本及 HTTP 保持。

方案覆盖/对比计算归方案展示，比较选择值以具名 model 仍由页面持有，保留原 load 只清 compareId 的行为。共享原有 section/toolbar/help/text 样式源以 scoped src 复用；方案专有样式搬至方案组件。不改变 DOM 顺序、文本、格式、下载类型或禁用表达式；不引入依赖。主题/窄屏的真实浏览器尚待正式验收，CSS搬移需审阅及渲染证据，不将构建当视觉通过。

## 行为锁定与验证

生产修改前，在真实 Vue/ElementPlus 工作台中补方案空状态/版本倒序/安全文本/覆盖及评审三种发布状态与审批能力组合断言；运行原组件全部49项及新增项。迁移后运行同一测试和全售前回归，必要时补具名 model 交互和语言切换，不修改原断言。dev、nonfix lint、vue-tsc、Prettier check、完整 staged tree commit gate、正常提交/推送和 PR readback；独立评阅 DTO/event、授权及 CSS scope 迁移。不得升级 AC-30/正式 QA。

## 风险及回退

子组件边界可能使父 scoped CSS 不再穿透多根组件；明确共享 scoped 样式及专有样式归属，保留全局主题 token 和原媒体条件。比较选择不随子组件挂载重置。生成/发布子组件意图不赋予权限，页面仍拒绝无效 scope 和授权。回退两组件接入及样式拆分即可，无数据库或生产操作。
