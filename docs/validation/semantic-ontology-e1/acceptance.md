# E1 画布与定义检查增强验收

日期：2026-09-10。实施于 `codex/enterprise-semantic-core`，基线 `d45d4abb64b46f248550e713ff77633f7c362bd6`。本轮交付 E1a/E1b，代码尚未提交；E2/E3/E4 仍为方案。

## 实现

- 新增 `OntologyGraphCanvas.vue`，使用项目已有 Cytoscape / cose-bilkent / dagre；替换工作台内 SVG 实现。支持拖动、平移、缩放、适应、定位、层级和关系探索布局。
- 新增 `ontologyGraphView.ts`，统一搜索高亮、显式匹配过滤、一跳邻域、定义/关联类型和 500 节点上限。只复用现有 `ontologyProjection.ts` 的真实投影边，保留未完整图示公理提示。
- 修改 `OntologyModelWorkbench.vue`，保留定义目录、键盘关系列表、检查器、原有创建表单和高级视图入口；窄屏使用可关闭详情抽屉。
- 修改 `OntologyEditor.vue`、`OntologyVersions.vue` 和 `OntologySourcePanel.vue`，贯通公理来源聚焦/清除；切换工作区、本体和版本时清空旧选择。来源加载中、失败、无复核权限分别呈现，避免把未知计数显示为零。
- 新增投影视图测试与独立浏览器性能 harness；更新工作台、来源范围和页面测试。`components.d.ts` 增加自动发现的 ElCheckbox 声明。没有新增依赖，也没有后端、数据库或 OWL 权威模型修改。

## 静态验证

- 本体测试：14 个文件、59 项通过，见 [tests.log](tests.log)。
- `vue-tsc --noEmit` 通过；范围内 ESLint 无错误（测试 HTML 不属于 ESLint 配置范围，产生一条 ignored warning）。
- `pnpm run lint:precision` 与 `git diff --check` 通过。
- 企业模式 Vite 构建通过，26.16 秒，见 [build.log](build.log)。构建仍提示部分 chunk 大于 1024 kB；本轮未扩展到全站拆包优化。

## 浏览器验证

环境：本地已登录企业 UI `127.0.0.1:5189`，Chromium。使用已有合成本体与来源验收数据，不代表实际设备事实。

- [草稿操作结果](../../../output/playwright/ontology-e1/browse-results.json)：16 项通过，覆盖搜索/失焦、显式筛选、节点和边的键盘选择、一跳、类型、个体视图、两种布局、缩放、适应、平移、真实鼠标拖动、定位保留布局、来源跳转。
- [可重放操作脚本](../../../output/playwright/ontology-e1/browse.js)：实际 UI 操作；API 只用于读取草稿校验。操作前后草稿修订号均为 7，文档摘要均为 `9f071e6328c873555262d1e619ab9b2f91b0e180b5e6d3d7d438b47a9b43a8c6`，imports 摘要相同，监测到的 semantic 写请求为 0。
- [已发布版本结果](../../../output/playwright/ontology-e1/published-results.json)：选择 Probe 声明后来源数量为 1，展示精确历史引文及 REMODEL 状态；清除后恢复 2 条，刷新清空聚焦，不出现绑定来源表单。该 fixture 的继承公理未绑定来源，正确显示 0，不能按同名节点猜测来源。
- [补充结果](../../../output/playwright/ontology-e1/additional-results.json)：390px 下页面无横向溢出，抽屉可关闭及重开；深浅主题画布跟随切换，修复了暗色连线标签底色。切换到隔离工作区显示预期 NOT_FOUND，旧图与来源聚焦均清空；回到 Default 恢复 7 节点。该越界请求产生预期 404 控制台记录。
- [覆盖台账](../../../output/playwright/ontology-e1/coverage.json)：19 项检查通过，台账完整性脚本通过。范围仅为 E1 新增/修改浏览控件，不宣称全站 UI、全部角色或全部 OWL 能力验收完成。来源权限及异步过期响应由组件回归测试覆盖。

截图：[桌面](../../../output/playwright/ontology-e1/desktop.png) · [窄屏](../../../output/playwright/ontology-e1/mobile.png) · [深色](../../../output/playwright/ontology-e1/dark.png)。最终视觉检查 94/100。

## 性能与边界

[合成测量](../../../output/playwright/ontology-e1/benchmark-results.json)：50 节点/96 边 36ms；200/396 为 159ms；500/996 为 465ms；550 输入显示 500 节点并明确提示隐藏 50，493ms。200 节点选择检查器的 Playwright 操作至可见耗时为 41ms。以上为本机开发环境单次测量，非生产 SLA 或内存压力测试。

同页两实例节点数分别为 50 和 7，选中状态分别为 1 和 0，搜索控件 ID 独立；卸载后 Cytoscape 实例 `destroyed()` 为 true。组件卸载时停止布局并断开观察器。

E1 的图仍是现有 OWL 子集的展示投影；完整 OWL 后端投影属于 E2。浏览布局不持久化。未新增本体写行为；发布、迁移、事实接纳及全量后端验收不在本轮范围。
