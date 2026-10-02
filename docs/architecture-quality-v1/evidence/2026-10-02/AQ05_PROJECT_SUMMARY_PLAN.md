# AQ-05 列表摘要与项目详情契约计划

起点617a6c19d857f8f6d0288ec39e8ddbc5e13f5a46，工作树干净；初始dev32f0thgq SCAN_PASS。修改前核实后端SOURCE_COLLECTIONS：列表移除12个业务集合，仍保留元数据及未知历史扩展。

问题：ProjectPage.items声明为完整PresalesProject，工作台列表/portfolio/概览也使用完整详情，掩盖没有加载业务集合的事实。修复建立共享元数据头、独立PresalesProjectSummary和完整PresalesProject；Page/portfolio/dashboard只消费Summary。保持既有可选计数/状态/未知历史扩展及字符串ID，Summary不可当作完整详情。

范围：presalesApi.ts、dashboard.ts、PresalesDashboard.vue、PresalesWorkbench.vue和新summary合同测试，文档证据。先补真实Axios adapter/list→portfolio→dashboard稀疏响应测试在原实现通过，再更改类型并添加编译期不允许summary到detail断言。既有测试/控制面/依赖不变。

不改变HTTP路径/请求/响应、来源授权、事务、发布、Workspace和UI渲染；不填充空明细，不新增runtime schema或假称unknown已验证，不借此完成payload/V2/SQL分页或业务验收。复用既有汇总/分页/Workspace请求。回归新合同+所有售前+vue-tsc、dev、正式commit/push门禁；独立审阅。回退仅恢复四处类型声明、删除新增Summary，无数据库操作。正式AC仍NOT_RUN。

实测调整：原概览压缩模板格式化后新增UI-001词法指纹。遵守现有门禁，不重写baseline；将该组件20条原中英文文案接入既有presalesMessages/useI18n，补本地组件两语言回读/切换/点击合同。增加messages.ts与新dashboard合同测试范围，不改规则。初次pnpm从根目录选择12.6.0与UI pin12.4.2不符，改从UI目录运行正确版本；初次formatter遗漏固定config，随后显式使用.quality/prettier.json，最终重新验证。失败日志保留。

完整暂存门禁_1zpyw3r因no-empty-object-type拒绝空Summary继承声明，其余前端检查通过；修复删除不必要的Metadata中间接口，Summary直接定义共享字段，完整Project继承Summary并声明业务集合。保持行为/字段/断言不变，重新验证与独立复核。
