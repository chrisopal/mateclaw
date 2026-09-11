# UI 控件验收

| 流程 | 控件与检查 | 证据/状态 |
|---|---|---|
| 定义来源 | 检查资料变化、展开待确认、重新建模 | 实库 UI 创建唯一任务，导航携带 taskId |
| 建模任务 | 新旧资料对比、关闭、390px纵向布局 | model-comparison-1440.png / model-comparison-390.png |
| 建模建议 | 刷新、查看依据、确认 | model-proposal-1440.png；确认后草稿显示生产设备 |
| 事实来源 | 扫描、FACT 行修订入口、打开表单 | 实库 UI 新增申请 |
| 事实表单 | 数值输入、原文输入、失焦回显、提交 | fact-revision-1440.png / fact-revision-390.png |
| 事实时间 | UNKNOWN不伪造范围 | 挂载测试及持久化回读 |
| 精确引用 | 重复摘录禁止、补上下文恢复、Unicode范围 | sourceFactRevision.test.ts |
| 权限与不可用 | 无权限不展示入口、来源不可用禁入口 | sourceChangeReview.test.ts，后端删除/撤权回归 |
| 过期结果 | 工作区切换后预览/提交迟到响应不回写 | 挂载测试 |
| 申请审核 | 业务结论、查看依据、填写理由、确认 | 最终实库检查见 readback JSON |
| 历史与幂等 | 旧发布版不变、旧事实保留、重复请求不增加版本 | replay-readback.json / post-restart-readback.json |

未覆盖：真实 LLM 的继续对话生成质量；手机原生浏览器；生产 MySQL 运行；其他模块全量 UI。响应式验收使用桌面浏览器窄视口，不声称移动设备实机验收。
