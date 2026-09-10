# E1 UI 回归验收报告

日期：2026-09-10。判定：**FAIL**。按 `ui-regression-acceptance` 技能执行，未修改产品代码。

## 环境与范围

- 工作树：`/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`；分支 `codex/enterprise-semantic-core`，包含本轮未提交的 E1 实现。
- 真实已登录 Chromium，企业 UI `http://127.0.0.1:5189`，管理员，Default 工作区。
- 两页分别执行：草稿 `2097694137454493697/edit`，已发布版本 `2097611586143510530/versions`。
- 视口：1500×1000、390×844；本轮为浅色。使用既有合成验收数据。
- 范围：E1 图形浏览、检查器、来源聚焦、页面高级入口、临时状态恢复。原有新增定义、保存/发布、来源绑定/复核写表单不属于此次浏览回归范围。

## 数量

| 页面 | 检查 | PASS | FAIL | BLOCKED / NOT_RUN / UNCONFIRMED / N/A |
|---|---:|---:|---:|---:|
| 草稿 | 16 | 15 | 1 | 0 |
| 已发布版本 | 16 | 15 | 1 | 0 |
| 合计 | 32 | 30 | 2 | 0 |

声明范围执行覆盖率 100%，通过率 93.75%。2 个失败实例属于同一产品缺陷；不能据此声称 E1 全部 UI 验收通过。

## 确认的缺陷

**P2：窄屏详情抽屉关闭后没有恢复键盘焦点。** 草稿页与已发布版本页均复现。

复现：缩至 390px → 选中定义 → 关闭详情 → 点击“查看定义详情” → 点击“关闭详情” → 按 Tab。

预期：关闭后焦点返回“查看定义详情”入口，用户可以继续键盘操作。

实际：`document.activeElement` 仍为隐藏检查器内的“关闭详情”按钮，检查器的 `display` 已为 `none`；发布版本页下一次 Tab 跳到“高级 OWL 文档”，跳过重开入口。鼠标关闭/重开可用，页面宽度为 390px，没有页面横向溢出。

代码定位：`mateclaw-ui/src/features/semantic/ontology/components/OntologyModelWorkbench.vue` 的移动详情打开/关闭按钮仅切换 `inspectorOpen`，没有焦点恢复逻辑。建议保存入口 ref，关闭后在 nextTick 恢复焦点；应同时复测节点自动打开和来源跳转关闭场景。

证据：[焦点测量](focus-reproduction.json)、[稳定窄屏截图](published-mobile-stable.png)、[逐项运行输出](results.json)。截图只能展示关闭状态，焦点判断以实际 DOM 只读测量为准。

## 通过项与脚本纠正

两页分别完成搜索/无匹配/清空、仅匹配切换、三类下拉的非首项选择与失焦/重开、概念/个体切换、目录键盘选择与 IRI 折叠、一跳、放大/缩小、布局/适应/定位、真实鼠标节点和连线命中、拖动、键盘平移、滚轮、精确公理来源聚焦及清除、高级入口、刷新恢复。监测到 semantic 写请求 **0**。

首轮失败记录保留于 [attempt-1.json](attempt-1.json) 和 [attempt-1.js](attempt-1.js)。Element Plus 的原生 input 为隐藏控件，修正脚本为操作可见标签或键盘；缩放先建立非边界前置状态。滚轮在 Cytoscape `scrollingPage` 期间被忽略，最终脚本等待真实滚动状态结束后操作，复测通过。上述为测试脚本/前置状态问题，未归为产品缺陷。第二轮记录为 [attempt-2.json](attempt-2.json)。

## 复跑

前提：现有 Vite 与后端正常、浏览器已有授权登录状态、Default 工作区和两个 fixture 保持可访问。脚本不会注入凭据、修改 DOM 状态或调用写 API。

使用现有 Playwright MCP 执行：

```json
{"filename":"/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-e1/run.js"}
```

对应工具 `mcp__playwright__browser_run_code_unsafe`。脚本返回逐项结果，并保存失败截图。控件台账见 [coverage.json](coverage.json)。完整性审计命令：

```sh
python3 /Users/guojiexie/.codex/skills/ui-regression-acceptance/scripts/check_coverage.py /Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-e1/coverage.json
```

本轮审计无记录错误，因 2 项真实失败返回 FAIL/退出码 1。

## 限制

本轮没有重跑构建/类型/API 测试，也不以此前 59 项单测替代 UI 验收。本轮没有执行其他角色、深色主题、网络故障注入、触屏手势、全部版本切换和原有业务写流程；此前记录不得自动计入此次通过数量。此次仅新增测试脚本、台账和证据；产品缺陷尚未修复。
