# AQ-05 对象状态与错误投影计划

起点 HEAD 3cb32e3cec3e42d575e27041a2f6da9035846bfc，tree b301d93d26c120d844a1779b39f14d48d90b9796；初始工作树干净，dev y80ysfll SCAN_PASS/submission_ready=false。

## 目标与边界

规范第 8 节要求稳定消费者 DTO、unknown 校验和对象未知状态；第 9 节的聚合/对象单写迁移属于 AQ-06。现有具名响应 DTO 及 unknown 解码保留，不为消灭 ObjectNode 再创建无消费价值的 wrapper。独立只读复核发现两个实际缺口：澄清 RUNNING 借全局词表显示为已知且计入 OPEN；错误处理把 unknown 直接断言为字符串 envelope。

先补真实页面与错误函数的 RED 回归，再分别实施：

1. 在现有 status.ts 明确 clarification、task、release、fitGap、reviewIssue、reviewSeverity、response 的有限集合与判别联合。允许集合取自现有后端规则，不从翻译表推导；词表仍用于普通标签。将实际生命周期显示、澄清筛选/计数及任务轮询/取消消费者接入对象投影。未知状态不归 OPEN，不授予取消/批准；所有值保留原大小写/空白，missing 单列，raw API、编辑草稿和历史字节不改写。
2. 在现有 state.ts 对错误 envelope 做 unknown 检查。按原优先级选择有效字符串 code/message；坏类型不能阻断合法旧字段或 409 fallback。保留明确 EMPLOYEE_UNAVAILABLE 等非冲突 409，以及403、原回执语义。无需新依赖/框架。

这是 bug 修复，未知澄清的显示和 OPEN 数量有意改变：ALL 仍可查看未知/缺失，OPEN 只统计明确 OPEN。编辑入口保留原文以便人工纠正，不默默把未知升级为合法状态。授权、来源、Workspace、事务、CAS、发布、后台取消规则仍由既有服务控制。

## 验证和审核

旧实现新增测试先失败；保留失败日志。纯合同覆盖对象间同名值、missing/empty/unknown、类型收窄、全部合法值及两种语言；页面覆盖过滤、未知任务显示/不取消、错误草稿保留。每片后 dev 与适用回归，最终售前/全UI、类型、lint、格式、双主题构建由正常门禁执行；独立审核确认旧断言未削弱。真实浏览器检查补对象状态的显示/筛选，夹具不冒充业务 QA。

源码可逐片 revert，无数据库或依赖变化。正式 46 AC、真实历史/业务验收和远端强制策略仍开放。耗尽版本恢复需要修订表示扩容与混跑/回退设计；坏版本恢复需要可证明的权威输入，继续 fail closed，不以本片声称恢复完成。

## 独立审核补充

冻结 V1 的 openClarificationCount 是非 ANSWERED 数量，不能改成只有 OPEN。原详情指标跟随同一总数，UI 明确改称“未答复”，通过说明交代包含未知/缺失；OPEN 筛选仍只收明确 OPEN。新增同一合成项目从首页/台账进入详情的 RED 回归再修复；不更改冻结投影/迁移/原始 wire。
