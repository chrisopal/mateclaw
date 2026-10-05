# AQ-05 对象状态与错误投影工程证据

起点 HEAD 3cb32e3cec3e42d575e27041a2f6da9035846bfc，tree b301d93d26c120d844a1779b39f14d48d90b9796；初始工作树干净，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。实施前 [计划](AQ05_DOMAIN_STATE_PLAN.md) 与 dev y80ysfll 已完成。

## 修复与职责

Clarification 的 RUNNING 原先被全局翻译词表标为已知并归入 OPEN；Task 的 ANSWERED 同样被标为已知。现有 status.ts 增加七组明确的对象契约：clarification、task、release、fitGap、reviewIssue、reviewSeverity、response，返回 known/unknown/missing 判别联合。全局翻译词表保留给普通标签，不能再作为这七组状态的合法集合。显示、澄清筛选、任务轮询/取消和发布按钮消费对应投影，不改 raw DTO、API 请求、编辑草稿、权限或存储字节。

澄清 OPEN 只包含明确 OPEN；ALL 仍显示未知/缺失。冻结 V1 的 openClarificationCount 原本统计非 ANSWERED，因此汇总和详情指标继续保留该口径，文案改为“未答复”，说明包含未知/缺失；不能用改冻结迁移来迎合新筛选。既有导航、计数和人工修订仍可用，未知原文不被自动升级。

presalesError 原先直接断言 unknown 的嵌套 code/message，非字符串 code 会抑制 409 fallback，非字符串 message 会泄漏到文案返回。现有函数内检查对象及非空字符串；保留 nested code→旧 top-level code、msg→message→Error.message 优先级，坏类型落到下一有效字段。合法未知业务码以及 EMPLOYEE_UNAVAILABLE 不被一概标为冲突，403 与回执冲突语义不变。

无新依赖、框架、数据库或后端修改。这里只建立消费者边界，AQ-05 不要求先将所有存储 ObjectNode 改成聚合 record；对象更新粒度与单写迁移仍属于 AQ-06。solution/material/project 等未纳入本片有限状态集合的旧显示规则，不以本片宣称全部状态契约完成。

## 实际验证

- 旧页面 RED：91 项中 2 失败、89 通过，准确显示跨对象状态错误。修复第一轮 501 通过/1 失败，唯一失败是旧合同要求未知归 OPEN；按计划改为严格 OPEN，并强化 ALL/raw/跨项目导航断言。
- 七组对象值与原 47 个双语标签作交叉验证，含未知大小写/空白/原型名、missing/empty及编译类型收窄；中间售前 510/510。
- 错误解码 RED：44 项中 9 失败/35 通过；覆盖数字/布尔/对象/数组 code/message、空 envelope、有效 fallback、合法未知码。编辑选项请求实际拒绝时验证草稿/弹窗保留和错误为字符串。
- 独立审阅发现汇总与 OPEN 口径差异后，新增跨首页/台账/详情测试，修改前 1 失败（-t 排除其余 91 项），修改后通过。旧 Dashboard 文案两条期望随明确语义变化更新，其他断言不变；中间真实失败记录保留。
- 最终 `pnpm exec vitest run src/features/presales`：20 文件，528/528，0 failure/skip；`pnpm exec vue-tsc --noEmit` exit 0。从 UI 目录使用项目指定 pnpm；最初从 repo 根带 --dir 调用触发版本不匹配，未绕过版本检查。
- dev kczkc6fg、6oq801j1 为 SCAN_PASS/submission_ready=false。后续精确 tree 的完整格式/lint/类型/UI/双构建以正常 commit/push 报告及 PR 为准；dev 不是提交许可。

## 浏览器与独立复审

Playwright CLI + Chromium，真实 Dashboard/Ledger/Requirements/Overview/Outputs 组件及项目 CSS/i18n，合成单项目：汇总未答复 3、OPEN 1、ALL 4。enterprise/classic × 1440/390 宽度全部通过，分别覆盖英/中文；未知 task/release 显示原始未知值、取消入口不出现、批准/发布禁用，修订事件仍携带 RUNNING 原文，页面无水平溢出。窄屏表格沿用内部滚动。截图已查看；这不是像素一致性或真实 API/授权/客户验收。

首轮浏览器脚本误用简写按钮名，另一次夹具将布尔 error 传空字符串而被 Vue 当 true；按实际组件契约修正测试夹具，没有修改生产组件以迎合失败。失败和最终日志保留。

独立 /root/dto_boundary_audit：初审 1 P2（汇总口径），修复后 COMMENT/0 待处理发现；末次核对 Dashboard 两处文案期望，未删除断言。AST 不可用；LSP 返回的泛 tsc 诊断不足以证明逐文件检查，不计整体 PASS，实际 vue-tsc 为类型证据。技术审核不替代维护人签收。

[50 项证据归档](domain-state/domain-state-regression.tar.gz) SHA-256 `8f08eb6e738beb6005b3a307216b87bc653604d03497b62c0318a0cbd350b6e8`，0 处凭据脱敏。包含 RED/GREEN/中间失败、源码摘要、浏览器四图/结果/可复现夹具及审核记录；manifest 已逐项读回核验。

## 剩余与回退

撤销本片源码会恢复旧 unknown 显示/筛选和错误类型缺陷；无数据库操作。原 raw 内容、来源/Workspace/权限、事务/CAS/回执及冻结投影不变。正式46 AC、真实历史样本与 QA、完整状态契约、V2 对象迁移和远端强制策略继续开放。

恢复只读复核确认：合法耗尽项目需要全链修订表示扩容及新上限/wire/混跑/回退设计；malformed/mismatched 缺少可验证的修复权威，不能取较大版本或重置。保持现有 fail closed，不把本片消费者修复当作恢复完成。V2 缺失阻断新对象 schema，但不阻止后续独立修订容量设计。
