# 当前 OWL / M7 / M8 验收入口

本页整理 2026-09-09 当前证据，不替代原规划，不将未验证项视为完成。历史 OWL-01 文档中的“尚未实施”仅描述当时阶段。

| 原规划要求 | 当前证据 | 仍需完成或核实 |
|---|---|---|
| OWL-02-A 标准 AST、逐行正反/往返/编辑 | matrix-remove-preservation.json、matrix-missing-operands.json、facet-compatibility.json | 87 行/435 规格逐项映射还未证明全部完成；部分负例和上下文覆盖不能由总测试数推断 |
| OWL-02-B 新权威模型与持久化 | retirement-isolated-runtime.json；column-retired-persistence-readback.json | 仅隔离 H2 已实际删旧列；其他旧列依赖/退役范围需按 ADR 核实，不能据此宣称所有环境替换完成 |
| OWL-02-C UI、调用方整体契约 | ui-regression-current.json；已有 API/运行时证据 | 宽窄屏对照确认企业配置下响应式布局；复杂文档保存、标签修改、双格式下载和双格式文件重导入已结构比对（ui-complex-edit-progress.json）。此夹具不覆盖锁定 imports 和全部 OWL 构造 |
| OWL-02-D RESET / 质量库存夹具 | reset-column-independent-rebuild.json、reset-column-independent-mysql.json、retirement-rehearsals.json | 不能把临时库演练写成主库执行；Kingbase 无实库验证 |
| OWL-03 有边界推理 | w3c-gap-ledger.json、heap-exhaustion.json | W3C 264 PASS / 2 TIMEOUT GAP，不能宣称完整 DL 推理已验收；30 秒定向复验仍超时 |
| CTX-01 普通 Agent 上下文 | context-acceptance-index.md；context-real-tool-registry.json；context-annotation-mysql.json；context-annotations-runtime.json | 可选同快照推理、缓存撤权、分页及版本隔离已有证据；修复默认工具暴露与本体级注释遗漏。真实 ToolRegistry/绑定 34 项通过，最新临时 MySQL 总计 66 项通过；注释新包已运行。完整逐构造 CTX 和已编译会话撤权刷新仍需核实；部分 KB 权限使用 mock |
| VAL-01 同模型三组对照 | ../semantic-val-01/protocol.md、prepared-run-final/manifest.json | 162 次正式模型结果和专家盲评尚缺；准备请求不算已运行。最新连接探测 HTTP 403（val-provider-recheck.json），未执行正式题集；需修复配置或提供可用模型配置 ID，不能沿用旧余额不足诊断 |
| M7 来源变更复核 | m7-acceptance-index.md；m7-source-h2.json；m7-shared-source-mysql.json；m7-source-browser.json；m7-source-restart.json | 删除、共享、多公理、失效、幂等与越权已逐项映射；H2/MySQL 来源套件各 6/6。页面扫描/对比/独立决定及重启读回通过；修复审核后来源表状态滞后。合成资料、部分 KB mock 与 Kingbase 未验证限制保留 |
| M8 非空图新 OWL 版本迁移 | m8-punning-h2.json、m8-punning-mysql.json、m8-fact-limit.json、m8-revoked-role-mysql.json | 服务端嵌套/punning、撤权、超限链路已补；带已审核事实的页面预检/批准/执行/回滚、工作区切换及重启持久化读回均通过（m8-fact-browser.json、m8-fact-restart.json）。浏览器采用合成夹具；Kingbase 仍未验证 |
| 完整构建、测试、打包启动 | full-backend-latest-run.json；context-standard-identity-runtime.json | 最新根目录 mvn test 正常退出 0，798 份报告全部为本轮新报告：5583 项结果中 5520 通过、63 跳过、0 失败/错误。跳过项单列，不计通过；MySQL/W3C 等需结合独立证据。当前运行包的标准身份、注释和工具过滤已验证，整体规格及 VAL 仍未完成 |

## 下一步顺序

1. 修复后全量测试已完成并记录跳过原因；无需无变化重复运行。
2. 新包运行、历史读回、正常关闭和重启已验证；后续有生产变更再刷新运行包。
3. 核实当前 UI 与用户反馈，再完成原规划的浏览器验收链路。
4. 按能力矩阵和 CTX/M7 条目补齐缺失证据或实现，而非循环追加已有覆盖的测试。
5. 完成 VAL 所需实际模型结果及盲评；外部条件缺失要明确记录。Kingbase 按原规划记录未验证，不冒充实库通过。

本页是未完成项导航，不是整体完成声明。
