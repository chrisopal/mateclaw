# M7 来源复核证据索引

对照 `docs/superpowers/plans/2026-09-08-owl-02-and-followups.md` 的 M7 条目。此页只覆盖 M7，不代表 OWL/CTX/VAL 整体完成。

| 原要求 | 可观察验证 | 证据 |
|---|---|---|
| 删除资料 | 已发布本体不改写；旧观察过期；UNAVAILABLE 产生待复核；原文快照仍可回读 | `changedAndDeletedSourcesRequireReviewWithoutRewritingPublishedAxioms`；真实浏览器删除夹具 |
| 共享来源 | 两个本体复用原始/变更快照，复核 ID 不能跨本体使用，第一本体决定不处理第二本体 | `ontologiesSharingMaterialShareSnapshotsButNotReviewDecisions` |
| 一段支撑多个公理 | 两个绑定分别得到 CHANGED/UNAVAILABLE 项；共享 observedSnapshot；单项决定不确认另一项 | `sharedSourceChangeFansOutToEveryAxiomWithIndependentReviewAndDeduplicatedSnapshots`；浏览器逐条处理 |
| 已失效来源 | 原始快照保留、旧观察不能继续决定，人工 KEEP_HISTORICAL 不重写发布公理 | 删除/失效集成用例；浏览器来源对比显示原文与不可用说明 |
| 重复通知幂等 | 相同 operationId 重放一致；新 operationId 重扫同一版本也不重复生成复核项 | 多公理变更测试；原删除/决定重放测试 |
| 越权 | 跨工作区 404、普通成员不能决定 403、跨本体 reviewId 404、Agent KB 撤权后拒绝来源读取 | `changedAndDeletedSources...`、`wrongQuoteCannotMutateDraftAndAgentSourceReadIsVersionAndPermissionBound`、跨本体共享测试 |
| 保留原快照与人工审核 | Unicode code point 精确片段；原始/观察快照分离；审核不修改发布公理，不自动创建草稿 | `bindsExactSharedSnapshotAndCopiesWithoutLosingPublishedHistory`；浏览器读回 |
| 页面状态一致 | 审核保存后再次读回来源绑定，来源表与待复核列表同步 | `ontologySourceScope.test.ts` 新回归；修复前红、修复后绿与浏览器实测 |

上述后端用例位于 `OntologySourceReviewIntegrationTest`（6 个方法），由 `SemanticMySqlOntologySourceReviewIntegrationTest` 继承执行。H2 6/6，临时 MySQL 全套 64/64（其中该套件 6/6）；不能把其余 58 项算成 M7 专属覆盖。完整报告摘要与哈希见 `m7-shared-source-mysql.json`。

浏览器在隔离服务 18109 / 企业前端 5189 执行扫描、对比、KEEP_HISTORICAL 与 REMODEL。人工删除条件只作用于本轮创建的合成资料。API 回读确认原始快照及发布本体不变，两个决定独立且没有自动草稿。具体日志/读回索引见 `m7-source-browser.json`。

限制：KB 可见性部分集成夹具使用 mock；真实浏览器采用合成资料，不能推断真实工业业务收益。两份非删除版本的差异快照由后端集成测试验证，本轮浏览器验证的是删除后的原始快照对比。Kingbase 未提供实库验证。
