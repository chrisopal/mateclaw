# AC30 证据收口对账

## 结论

**工程建议：原 AC30 类别条款已有足够的真实浏览器证据，可记为 `ENGINEERING_EVIDENCE_SUFFICIENT`；正式签收仍为 `NOT_RUN`，本次不修改正式台账。** 旧证据的源码复用依据已核对，且已在当前 JAR 上补做 enterprise/classic 的最小只读冒烟，原 AC30 不再依赖“旧包等同当前包”的假设。

原条款来自两个入口：

- `CODEX_TASKS.md` 的 AQ-09：非目标功能不改变，字符内容不改，两主题、窄屏、Workspace 切换做视觉与功能回归。
- `ACCEPTANCE.md` / `REVIEW_CHECKLIST.md` 的 AC-30：enterprise/classic、只读、空态、错误态、窄屏正常，并有真实浏览器证据。

`ac30-preparation/coverage.json` 的 77 个控件/区域与四维扩展形成 367 个台账槽位，其中 59 个有限 PASS、308 个 NOT_RUN。308 是扩展回归维度，不是 308 条独立的 AC30 原始需求，不能用其总数直接判 AC30 失败，也不能因日志存在将其自动改为 PASS。

## 原要求与实证

| 原要求 | 已证实的具体行为 | 确切证据与版本 | 工程判断 |
| --- | --- | --- | --- |
| enterprise / classic 两 profile | enterprise 与 classic 均在真实 Chrome 打开售前详情六页签；两者均完成 390×844 几何检查和项目编辑器打开/关闭；classic 另有桌面暗色六页签检查 | 5a tree `017fefc...`：`mobile-layout-stable.log`、`classic-mobile-layout.log`、`classic-dark-tabs.log` | PASS；当前候选的 `mateclaw-ui/src/features/presales` 与 5a 无文件差异，可复用为前端 profile/layout 证据 |
| 窄屏正常 | 两 profile 的六页签在 390×844 下 `document.scrollWidth == 390`，编辑器宽 390，保存按钮在视口内；e6 另验证窄屏项目↔聊天返回后仍保持已取消状态，项目与聊天文档宽均为 390 | 5a：上述 mobile logs；e6 tree `e6f9fe...`：`ac30-runtime-ui-acceptance/evidence/narrow-runtime-ui.log`、`narrow-transcript-readback.log` | PASS；5a 证据覆盖售前 feature，e6 覆盖后续新增聊天/执行记录行为 |
| 只读正常 | viewer 的 25 个可见变更入口全部 disabled，六页签可读，监听到售前写请求为 0；取消执行的聊天 composer 在刷新、离开/返回后仍 disabled，状态接口 `readOnly=true` | 5a：`viewer-readonly-retry.log`；e6：`desktop-runtime-ui.log`、`narrow-transcript-readback.log` | PASS；旧 viewer 结果只复用于未改的售前 feature；聊天只读使用 e6 后的新实现证据 |
| 空态正常 | 空项目六页签逐一选中，材料/需求/澄清/能力/方案/评审成果均显示明确空态且页面可继续导航 | 5a：`empty-detail-tabs.log`、`empty-enterprise-desktop.png` | PASS；这是具体六页签空态，不扩张为每个控件的所有空组合 |
| 错误态正常 | 列表 500 显示错误并在撤除故障后恢复；能力/详情 pending 时隐藏旧数据并以实际 GET 200 恢复；成员失败禁用负责人；选项失败后实际 GET 200 恢复；窄屏生成 403 保留目标且未调用模型；当前候选项目编辑器收到 HTTP 400/403 后保留输入并允许重试，随后实际 PATCH 200、版本 1→2、刷新重开一致 | 5a：`list-error-recovery.log`、`read-errors-recovery.log`、`editor-option-errors.log`、`generation-dialog-recovery.log`；当前 tree `4862bf...` / JAR `0c1d90e3...`：`ac30-unassigned-save/evidence/save-http-errors-recovery.log`、`unassigned-readback-stable.log` | PASS；400/403/500 是浏览器路由注入的真实 HTTP 响应，用于 UI 恢复断言，不冒充后端主动产生；当前候选的成功保存和读回是实际后端请求 |
| Workspace 切换 | 一个实际从后端取得、且包含 Workspace-A 项目的列表响应被延迟；正常切换至 B 后，新请求均携带 B，B 只显示 sentinel；释放 A 响应后未污染 B，且无切换写入 | e6：`ac30-source-workspace-ui/evidence/workspace-unsaved-switch-isolation.log`、`workspace-pending-response-isolated.png` | PASS；直接穿透打开的 modal 不属于要求，正常点击被 overlay 阻挡的补充分支保持 UNRUN |
| 功能回归 | 59 个有限 PASS 覆盖项目编辑与刷新回读、分页/查询、六页签、材料绑定/解绑、需求/基线/方案/评审/候选/批准/发布、下载与 handoff 等代表性链路；后续又补了执行记录只读、候选编辑保存、来源恢复解绑、dirty 丢弃和 Workspace 隔离 | 5a `coverage.json` 中 59 个带 SHA 的 PASS；e6 两批 acceptance；当前候选 unassigned-save | 有限 PASS；不宣称 77 控件逐项四维完成，不把 51 个成功脚本等同 51 条完整业务验收 |
| 当前缺陷修复 | 未分配员工项目的元数据保存不再错误绑定空员工；项目保持“尚未绑定”，版本 1→2，刷新与重开稳定；仅 `UPDATE_PROJECT` 的空员工分支改变 | 当前 base HEAD `e70924e...`，candidate tree `4862bf...`，PID 19617，JAR `0c1d90e3...`；`ac30-unassigned-save` 浏览器证据、63/63 green、861 项回归包（1 个既有平台 skip）、独立审阅 COMMENT 无发现 | PASS，限于该最小分支；独立 COMMENT 不是维护人批准或正式 QA |
| 当前包最小复核 | enterprise/classic 在当前包、390×844 下六页签空态均无文档横向溢出，编辑器取消按钮可达；双 profile 列表 GET 500 显示错误，撤除注入后实际 GET 200 恢复；双 profile viewer 桌面六页签可读、15 个可见变更入口 disabled、售前写入为 0 | current tree `4862bf...` / JAR `0c1d90e3...`；enterprise `50043`、classic `61807`；本目录 `evidence/current-*.log` 与 10 张截图 | PASS；正常登录表单，无认证存储注入、force click、保存、发布、模型调用或权限修改 |

## 版本复用依据

1. 5a tree 到当前 candidate tree：`mateclaw-ui/src/features/presales` 没有文件差异。相关生产变化仅为 `PresalesEmployeeRuntime`、新增 `PresalesTranscriptPolicy`、`PresalesService`，以及聊天界面的 `ChatConsole` / conversation guard。因此 5a 的售前 profile、布局、只读、空态与故障展示可按具体行为复用；旧聊天证据不复用，由 e6 证据替代。
2. e6 tree 到当前 candidate tree：UI 没有差异；相关差异只有 `PresalesService.java` 和两份测试。生产改动只在 `UPDATE_PROJECT` 中让“项目原本未分配且请求员工仍为空”跳过绑定。`SAVE_CONTEXT`、取消/转录、来源解绑、GET 列表及 Workspace scope 不在该分支，因此 e6 的运行时与 Workspace 证据可复用。
3. 当前 live identity 已只读复核：61806 的唯一监听 PID 为 19617，实际 `server.jar` SHA-256 为 `0c1d90e324591260d1c667ac09d06752f0dd56233e83946f363abe6e46ad2b5b`；61807 返回 HTTP 200。当前运行包不与旧 5a/e6 JAR 混称。
4. `ac30-unassigned-save/evidence/runtime-identity.json` 记录 base HEAD `e70924e...`、candidate tree `4862bf...`、53 个售前 UI 文件与当前运行复制一致。当前浏览器修复证据直接绑定此包。
5. 当前包复核直接使用共同 backend 61806/PID 19617：enterprise `50043`、classic `61807`。两个 profile 各访问六个空页签并检查 `document.scrollWidth=390`；各注入两次列表 GET 500 后恢复实际 GET 200；各用正常 viewer 登录检查六页签、15 个可见变更入口和零写入。viewer 的全局设置与活动授权查询返回预期 403，不是售前错误或写入。

## 有限成功与失败口径

- `coverage.json`：77 个源控件/区域，367 个扩展槽位，59 个有限 PASS、308 个 NOT_RUN；两页 inventory 均为 incomplete。
- 69 份历史运行记录中 51 个 EXECUTED、17 个 HARNESS_ERROR、1 个预期旧基线拒绝。HARNESS_ERROR 不等于产品 FAIL；EXECUTED 也只证明各日志中的具体断言。
- 三个新增证据包的 manifest 全部逐项 SHA 校验通过：runtime-ui 23 项、source-workspace 21 项、unassigned-save 21 项；preparation 的 59 个 PASS 引用共 98 个文件引用，全部 SHA 校验通过。
- 当前包最小复核四次主用例均 PASS：enterprise/classic admin smoke 各检查 6 个空页签、窄屏编辑器、GET 500→200 恢复和零写入；enterprise/classic viewer smoke 各检查 6 个可读页签、15 个 disabled 入口、零写入。注入 500 产生的浏览器资源错误，以及 viewer 访问全局设置/活动授权的预期 403，均与意外 console error 分开记录。
- 模拟错误只证明 UI 收到相应 HTTP 状态后的展示、输入保留和恢复；实际后端成功保存/读回、实际 Workspace-A 响应、实际 GET 恢复分别单独报告。
- 合成账号、合成项目和 SIMULATED_MODEL 不构成客户确认、真实模型质量或人工业务签收。

## 最值得补的真实缺口

1. **正式 QA / 台账签收仍 NOT_RUN。** 本报告只给工程证据建议，不修改 `acceptance-register.json` 或 `REVIEW_CHECKLIST.md`。
2. **扩展控制清单仍为抽样。** 若 AC30 原条款签收后继续增强回归，优先补已有合法制品条件下的预览/下载失败恢复；不得伪造 presentation metadata，也不要求真模型生成。
3. **窄屏英文编辑器仍是可选增强项。** 现有英文六页签是桌面证据，双 profile 窄屏是中文证据；这一组合缺口不阻断原 AC30 类别条款。

原 AC30 类别条款本身没有剩余的具体浏览器测试缺口；剩余第一项是正式签收，后两项属于扩展回归。

S6 实际演示预览、打开 modal 后强行点击 Workspace、完整 77 控件四维交叉、真实模型质量与客户批准均不是原 AC30 的新增前置条件。

## 仓库归档与交付身份

本轮实现已受检提交并推送为 `5f61abceae13ee2721704fdecd391f69fb9b443d`，tree `2f0efd237fbaef25dce4e16ba446d82b6816d0bf`。提交前门禁及正常提交钩子均执行 Java 6860 / UI 1271；当前提交远端 CI 另验。前一提交 e709 的 CI 37454001983 已成功，不能覆盖后续实现。

本目录中的脚本、截图和日志打包在 [browser-evidence.tar.gz](ac30-reconciliation/browser-evidence.tar.gz)，含 38 个清单成员及 manifest；SHA-256 `471ee315a8ed5d6a53f946844673aa3df9b05f6a23a05628c53388daea83bcde`。包内保留原始目录结构，报告中 `本目录` 指解包目录。历史包的内容及版本对应关系不因归档而改变。
