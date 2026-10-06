# AQ-09 项目台账展示职责计划

起点 HEAD 08acb836ee6ad3ec2713410d90b4e23c52ea219c，工作树 clean，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。dev e2adjpdo SCAN_PASS/submission_ready=false。远端 run37376295073 已获 Runner，真实检查进行中。

清理对象是 PresalesWorkbench 中项目台账的筛选表单、列表、分页、阶段和日期展示及专属样式。提取 PresalesProjectLedger 展示组件，用 required model 传递 query/ownerFilter/statusFilter/page，typed props 提供项目摘要、成员和已有显示函数，search/reload/open 事件交回父页。组件不调用 API、不缓存项目或权限、不拥有 Workspace 生命周期、不导航。页面保留 Dashboard、查询会话、scope/abort、页面状态和路由；不增加 store、请求封装或依赖。

先在旧页面补并运行交互刻画：筛选精确字符串 ID、搜索重置页码、分页请求、Dashboard 阶段联动，列表文本和导航/双击，空态/错误态。既有工作区切换/取消/权限/重放回归继续执行。再原样移动模板和样式，保持 fragment 结构，不增加布局包裹；date fallback、成员不可用和未知阶段显示不改变。导航链接样式复用既有 feature CSS，避免跨 feature 样式依赖。

实现后：售前回归、类型/只读 lint/格式、dev、两主题构建，浏览器 desktop/narrow 两主题前后对比及交互验证。浏览器夹具使用明确合成数据，仅验证呈现/交互，不冒充真实业务、真实后端或正式 AC-30。独立审核后完整暂存树门禁、正常提交/推送，日志与未测项进入交付证据。

回退恢复原页面并移除新组件，保留已完成的查询/执行/提交/下载职责；无 API、数据库、权限、文案或业务政策变化。
