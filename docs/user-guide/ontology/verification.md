# 手册验证与截图说明

更新时间：2026-09-13。本文区分功能源码核对、既有 T12 证据和手册构建检查。

## 证据范围

主手册以当前 semantic-m1 的 features/semantic 界面及 T12 support-boundaries.md 为依据。2026-09-10 设计文件仍标记为设计提案，不能单独证明功能完成。

既有 T12 REPORT.md 记录：设备自然语言及图书资料建模、人工确认检查发布、设备模型知识库绑定、RDF/XML 往返、一个样例审核前后可信数量 0→1。它明确 T12 尚未整体通过；原报告中的测试统计不是本次手册构建重新运行的测试。

本轮续测汇总位置为 `output/ui-acceptance/2026-09-12-t12-e2e/REPORT.md`；原 T12 目录保留原轮次证据。以下为续测已取得结果，不代表整体 T12 完成：

- 真实查询智能体经工具配置修正和业务名称返回修复后重测：P-101 为泵，事实 2098776967022428161 r2、证据 2098776966951124994；UNKNOWN 时间不证明今天有效。
- UI 新增对象、关系和属性，发布独立模型 v1/v2/v3，查看中文版本差异，归档及恢复；恢复不自动开放新绑定。独立知识库绑定完成。
- UI 保存 EQ-01 设备和 L-01 产线；候选关系由 API 准备，UI 核对原文并确认，不冒充 UI 创建候选。
- 含 2 实体、1 事实的图经 v2 → v3 → 回滚 v2；撤回来源使可信数量 1 → 0。
- 合成原文由 API 删除后，UI 扫描发现 1 条受影响记录；保留历史首次遇到 Long 版本字段字符串的 400；修复后，真实扫描返回数字版本、界面提交成功，刷新和只读数据库回读确认 REVIEWED / KEEP_HISTORICAL。可信数量仍为 0，保留历史不自动恢复可信。
- `roles-result.json`：11 项断言通过，10 张真实截图；viewer/member 权限与跨工作区隔离，UI 编辑及持久化回读。
- `responsive-result.json`：3 页面 × 3 视口共 9 项通过，6 次适应画布点击成功，截图逐张查看；不包含所有行按钮与弹窗组合。

尚有完整角色/视口/操作组合、原生 MySQL/Kingbase、生产规模、真实中断恢复及供应商限流等验证门槛；是否补齐以该目录最终报告为准。

## 截图来源

| 手册文件 | 原始来源 | 性质 |
|---|---|---|
| images/t12-published-source-labels.png | output/ui-acceptance/2026-09-12-t12/published-source-labels.png | 已存在真实发布版本来源界面 |
| images/t12-sample-evidence.png | output/ui-acceptance/2026-09-12-t12/sample-evidence.png | 已存在真实样例证据界面 |
| images/t12-e2e-entity-business-types.png | output/ui-acceptance/2026-09-12-t12-e2e/entity-business-types.png | 真实实体中文类型与保存界面 |
| images/t12-e2e-relation-accepted.png | output/ui-acceptance/2026-09-12-t12-e2e/relation-accepted.png | 真实关系审核确认界面 |

图片按原文件复制，不生成、不替换截图内容。历史附录图片属于原 M1–M5 验收，不能证明本轮状态。

## 手册复建

在仓库根目录运行：

```sh
node docs/user-guide/ontology/build.mjs
```

构建使用现有 marked 依赖，内嵌图片和下载附件，无新增依赖。手册验证结果保存在同目录 verification-result.json；它只证明文档生成、引用和截图副本检查，不证明产品端到端流程。
