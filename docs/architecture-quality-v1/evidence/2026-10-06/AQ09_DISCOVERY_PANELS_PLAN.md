# AQ-09 材料、需求与能力面板拆分计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，110项dirty。仅architecture-quality工作树，原Development检出只读，保留累计WIP。初始dev见/tmp/workbench-sections-initial-dev.log。

问题：PresalesWorkbench已有Overview/Solutions/Outputs组件，但其余三个业务页签仍内联约300行表格、筛选与操作按钮。边界不一致，增加页面编辑和权限维护负担。本片统一这三个面板边界，非仅移动任意代码行。

新增PresalesMaterials/PresalesRequirements/PresalesFitGap，与现有面板保持同域组件、typed props/emits、宿主i18n、共享workbenchSections.css。页签容器仍留在页面；子组件无API/store/路由调用、无新权限策略。材料只发bind/unbind/evidence事件，需求发generate/confirm/continue/revise/evidence事件，能力发generate/evidence；父级负责动作与精确ID。已撤回材料继续禁止再撤回。

clarificationFilter保持父级生命周期，通过受控model传递，避免页签/项目切换行为变化；过滤派生列表归需求组件。状态及owner格式化仍复用现有函数。原DOM结构、文案、字符串ID、表格宽度、权限disabled和来源限制保持，复用共享CSS且核对scoped样式影响。无新依赖、迁移、后端、门禁和语言包修改。

先运行原页面合同，补必要交互刻画，再抽离组件；定向/全UI、vue-tsc、非修复ESLint、项目Prettier、两主题构建、dev及独立审阅。尝试隔离真实浏览器渲染对比，明确夹具视觉检查与真实业务验收的区别。格式化显式单独执行。记录失败与修正，不改变旧断言/配置使检查变绿。

回退恢复before页面并删除3个组件/本片新增测试；不改已完成会话拆分或后端。正式46AC签收及整体V2迁移保持开放。
