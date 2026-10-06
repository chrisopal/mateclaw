# AQ-09 售前工作台轮询生命周期整改计划（关联 AQ-04/AC-13、AQ-01/AC-21）

起始 HEAD e0d3b26a398fc12e2e8c5e5ec5af283b7939369c，base origin/dev，独立工作树干净；dev SCAN_PASS jbtxebuf。范围仅售前工作台任务轮询职责、新域内组合函数及行为测试/证据，不改UI布局、后端权限、依赖或测试调度。

先运行现有组件/状态回归。补真实组件失败用例：同项目重新加载后已取消请求迟到、命令返回更高版本后旧轮询迟到、多个运行任务不能重复整项目GET。测试使用真实Vue/ElementPlus组件，API替身故意忽略AbortSignal暴露页面接受端的防线。确认失败后才搬移。

把轮询放入域内组合函数：每个工作台实例/当前project一个循环，根据整项目响应跟踪全部运行任务；捕获Workspace、项目和独立AbortController，读取前后都检查当前scope/取消；重载和卸载终止循环及计时器；旧循环finally不能删除替换后的循环。保留1000ms间隔、600次上限、任务终态停止、403清空来源与严格repair-context读取、原错误/i18n。新响应低于当前项目version不得覆盖，不能以取消请求已发送为证据接受迟到内容。

页面保留载入、命令和来源授权读取；组合函数只负责请求生命周期/任务跟踪，复用现有presalesApi/types/state。不引入全局store、业务批准策略、通用框架或演示资料。重构不改变receipt/409草稿保护及下载/预览权限。

每片dev及定向Vitest/typecheck/nonfix lint/format检查。独立审核取消、版本、403/repair和多个task分支。完整staged tree commit门禁、正常提交/推送、远端与PR读取；真实浏览器若可运行补绑定组件工程证据，不将API模拟当正式角色/业务QA。正式数据库/模型/QA仍未验收。
