# E2 第二批表达式验收

基线：92f89541。范围：标准只读表达式投影及草稿、已发布版本的表达式树。执行结果以 coverage.json / verification.json 为准，初始台账在浏览器操作前建立。

## 重跑

使用 JDK 21 构建当前 worktree 的 mateclaw-server，启动 scripts/semantic-owl-runtime/start.sh（18109）；企业 UI 5189 的 `/api` 代理指向 18109。复用受控浏览器登录，不在脚本中填写凭据。

用现有 Playwright MCP `browser_run_code_unsafe` 的 filename 参数执行本目录 `prepare.js`，它仅创建隔离合成本体并发布测试快照。将浏览器导航到返回 ontologyId 的 `/semantic/ontologies/{id}/edit`，再执行本目录 `run.js`。测试脚本只操作界面及独立读取；最终结果写到 results-final.json 并回填覆盖台账。

脚本按当前 URL 派生 ontologyId，可复跑新的合成数据；fixture.json 是本次实际数据，不要求固定使用。E1/E3 共享回归分别复用上批保存脚本，E3 会创建新合成数据。

## 本批边界

命名定义图继续沿用既有投影；复杂表达式以独立只读树表达，保持属性链顺序。不会写回树结构、执行推理或把基数转换为业务字段校验。固定导入仍折叠，超出本批支持范围或预算的公理保留原文和覆盖原因。


## 验收结果

- 后端定向：OwlDocumentAdapterTest 140、ProjectionIntegration 5、OntologyIntegration 13，共 **158 通过**；core/OWL 全量由实现阶段执行，27/180 通过、1 项既有跳过。
- 前端：17 个文件、**84 项通过**；vue-tsc、变更文件 ESLint 和 enterprise 构建通过。构建保留既有大 chunk 提示。
- 本批台账：草稿和已发布各 8 项表达式检查，加列表/草稿/已发布的大屏适配 3 项，**19 项通过**；控制台账覆盖审计 PASS。
- 共享回归：E1 32、E2 18、E3 11，共 **61 组通过**。合计 **80 组浏览器检查通过**。E3 合成数据写入和重试按原脚本读回；表达式浏览零写请求，草稿版本 3 和文档摘要保持一致。
- 正式只读回读见 readback.json：草稿/已发布各 21 个表达式节点，23 条公理引用；HasKey 明确未支持，2 条固定导入引用折叠；均未截断。

## 发现、修复与简化

1. 首次运行对不可变表达式集合排序导致 GET 500，已改为可排序集合。initial-failure.json/png 保留首次失败，最终运行恢复。
2. 表达式展开标识与主语不明显、技术 ID 和值重复显示：恢复原生展开箭头、补充主语及运算符摘要、翻译操作数角色、删去重复内容，缩小移动端缩进。93 分视觉复核通过。
3. 窄屏首次截图截取了侧栏动画中间帧：属于证据采集问题，现等待侧栏边界退出视口后截图。不是侧栏产品缺陷。
4. 用户截图的大屏留白由 semantic.css 的 max-width:1440px 造成，移除该限制及居中外边距。2400px 视口下页面由 1440px 扩展至可用的 2180px；3840px 下为 3620px。保留正常内边距，命名图画布由现有 ResizeObserver 同步扩展。wide-initial.json/png 与 wide-results.json 保留前后证据。
5. 大屏测试初次选用了无草稿的已发布 fixture，产生脚本前置超时；更换既有草稿 fixture 后相同断言通过，见 wide-script-correction.json。

## 变更位置与重跑补充

核心变更在 OntologyDisplayProjection、OwlDocumentAdapter 及其测试；UI 为 standardProjection.ts、新增 OntologyExpressionTree.vue、OntologyModelWorkbench.vue 和 ontology/semantic.css；服务器只新增接口序列化与只读回归测试，不改写接口。

本批保留现有命名图和 E3 编辑接口；不增加依赖、存储表或独立解析器。复杂表达式仅查看，未支持构造、固定导入和展示预算限制会保留原文及原因。当前交付不含复杂表达式编辑或推理，不代表全应用所有页面验收。

共享脚本：`../2026-09-10-e1-fix/run.js`、本目录 `e2-regression.js`（将本批已支持的全称限制预期更新为 FULL）、`../2026-09-10-e3/run.js`。对应输出为 e1/e2/e3-regression.json。大屏复跑执行 `wide.js`，结果见 wide-results.json。本次变更未修改全局 UI 测试 skill 文件。

## 原生窗口交还修正

用户后续报告右侧裁切，根因是测试在用户标签遗留了 2400px 模拟视口，物理窗口实际为 1920px。上文大屏测量证明的是模拟视口内布局，不能证明实际窗口交还正确。现 run.js、wide.js、e2-regression.js 全部在临时标签执行，finally 关闭临时标签并返回用户原生标签；不得在交还前对用户标签调用 setViewportSize。旧 E1/E3 脚本也应由临时标签执行。原生窗口与刷新证据见 ../2026-09-10-native-window/README.md。
