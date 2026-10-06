# AQ-05/09 编辑字段职责切片计划

起始HEAD1e8ec81fd946bd0d60551dfa480798140729925c；独立工作树干净，原项目WIP保留。base origin/dev实际基线不变，开始dev uvzuqbe5 SCAN_PASS。沿用默认工程门禁与现有职责ADR。

## 范围与依赖

将11类编辑的字段模板按三处职责分组：AssignmentFields(project/employee)、DiscoveryFields(material/requirement/clarification/baseline/fitgap/context)、OutputFields(solution/review/release)。使用required具名form model引用原typed draft，kind用明确union限制；只发出来源/statement选择与管理页导航意图。合并project/employee重复员工选择/错误/空提示，保持原字段顺序及条件。不给组件scope、保存、授权、HTTP或验证职责；页面保留dialog/form submit/footer/error、editorSession、source/statement选择回调、save/CAS/receipt/409、导航与权限。

字段组件复用原i18n、显示label与Workspace读取事实。共享原form-grid/section-editor/全宽select的scoped样式源，保留原媒体条件；不复制通用框架，不增加依赖或改门禁/DTO。required、maxlength、textarea rows、disabled/loading、精确string ID/revision/多选及历史扩展语义不变。

## 行为保护与验证

旧组件55项已有项目/员工/来源/澄清/dirty/route/scope/409行为保护；修改生产前补baseline提交、solution add-section与review add-finding的真实ElementPlus刻画。迁移后同套与全售前回归，必要时补子组件locale/model联动。原断言不改。逐片dev、nonfix lint/vue-tsc/固定格式、CSS合同/六组件scoped编译、独立模板/事件/DTO/样式审阅、完整精确树gate、正常commit/push/PR readback。真实浏览器不由CSS编译或组件替身替代。

## 风险与回退

多根组件不能依赖父scoped样式穿透；共享原样式源并验证selector/declaration/media。具名model的嵌套编辑仍需更新同一session draft，数组push不能变成局部副本。管理页导航仍经原route discard guard，子组件只发意图。可回退page接入与三字段组件/样式，无数据库或生产操作。正式QA/角色/并发/双主题窄屏仍单独验收。
