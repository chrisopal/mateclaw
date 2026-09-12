# T12 本轮浏览器操作记录

执行时间：2026-09-12 晚至 2026-09-13。原持久化 H2，enterprise UI，独立 T12 合成工作区。
基线 `82b9a2b0`。所有发布、审核、升级与来源治理操作均限合成验收数据。

## 人工模型生命周期

1. 本体列表 → 新建本体 → 填写“ T12 生命周期界面验收 ”及描述 → 创建。
2. 业务对象分别新增“产线”“设备”；每次填写名称 → 预览变更 → 确认保存。
3. 新增关系“位于”：所属对象设备，关联对象产线；选择后失焦，预览确认内容，再保存。
4. 新增属性“编号”：所属对象设备，内容类型文本；预览保存，刷新检查对象和关系仍存在。
5. 运行检查 → 检查发布页运行检查 → 四项通过 → 填写发布说明 → 确认发布 v1。
6. 建立草稿，新增“工厂”，重复检查发布 v2。版本比较 v1→v2 显示新增声明、名称与文档变更，表头为中文。
7. 归档模型 → 对话框确认 → 刷新，建立草稿入口消失、两个版本均不可新绑定。
8. 恢复模型 → 确认 → 刷新，允许编辑恢复；版本仍不可新绑定。只开启 v2。
9. 应用到知识库 → 选择独立资料库 → 失焦核对名称 → 建立绑定 → 打开知识工作台。
10. 再新增“车间”，检查发布 v3；已有知识库仍固定 v2。

读回：`lifecycle-revisions.json`、`readback-results.json`。三个版本定义数依次 12、14、16。

## 实体、关系、证据及迁移

- 对象类型下拉修复前显示全部 7 个技术 IRI，包含属性、关系和数据类型；修复后只提供设备、产线、工厂。
- 通过业务对象表单创建 EQ-01（设备）和 L-01（产线），刷新确认中文类型与原 IRI 均保留。
- 独立资料通过 API 准备；资料与证据页选择该资料、失焦后点击保存原文版本。任务最终 SUCCEEDED，原文版本为 1。
- API 仅准备一个关系候选及精确证据，不能计作 UI 创建事实。知识记录页点击候选、打开证据核对“设备EQ-01位于产线L-01。”及码点 [0,16)，填写理由、点击确认。
- 已确认知识显示“EQ-01 · 位于 → L-01”，r2、有证据支持；关系图出现两个对象。
- 本体版本升级页选择 v3 → 生成计划 → 批准 → 执行 → 恢复原版本解释。
- 第一轮为无实体/事实图，第二轮含 2 实体、1 已审核事实。第二轮无 IRI 变更，迁移与回滚后事实修订为 r3、r4，证据 ID 保持不变。
- 数据回读验证 v2→v3→v2、审核状态不变、历史不覆盖。未覆盖任意复杂 IRI 替换或生产规模迁移。

证据：`entity-business-types.png`、`relation-fixture.json`、`relation-accepted.png`、`relation-migrated-readback.json`、`migration-rollback.json`。

## 来源治理

1. 资料与证据页填写撤回理由 → 撤回整个来源，来源显示已撤回；已确认知识页由 1 条变为 0 条。
2. 删除本轮 API 准备的合成原始资料，模拟来源消失；没有删除旧设备或图书案例资料。
3. 来源变化复核 → 扫描来源变化，发现 UNAVAILABLE，1 条已确认知识 r4 受影响，禁止据此提出新结论。
4. 选择保留历史、填写理由、失焦核对、保存复核决定。
5. 首次提交实际失败：`expectedGraphVersion` 是字符串 `"10"`，服务器严格输入边界返回 400。见 `source-review-failure.json`。修复后真实扫描返回 number 10，界面提交成功，刷新后无待复核记录；持久化回读为 REVIEWED / KEEP_HISTORICAL。见 `source-review-passed.png` 与 `readback-results.json`。

## 真实智能体问答

- 建模师只绑定建模工具。直接让它查询图谱失败，误用本体接口；保留 `wrong-agent-query.json`。
- 专用查询智能体绑定 SemanticTool，首次绑定失败因为全局工具未启用；管理员启用后绑定成功。
- 修复前可读到已审核事实，但缺少类型中文标签；不能可靠回答类型名称。
- 修复后同一真实会话再次调用 semantic_search，使用返回 termLabels 回答“P-101 的类型是泵”，引用正确事实 r2 与证据，明确 UNKNOWN 不能证明今天有效。
- `query-retest.json` 保留实际模型与工具轨迹；没有使用伪造模型回复替代验收。

## 权限、视口及错误输入

- `roles.cjs`：创建独立查看者/成员，以真实登录验证 UI；成员新建并保存模型，查看者只读，成员发布不可用，跨工作区浏览器与 API 均不返回模型内容。11 项断言，详见 `roles-result.json`。
- `responsive.cjs`：列表、编辑、版本页 × 390/768/2560，9 个页面视口；实际适应画布点击 6 次。截图逐张检查，详见 `responsive-result.json`。不代表所有弹窗、表格列和角色视口组合全覆盖。
- 本体列表 → 导入模板 → 选择损坏的 `invalid.ofn` → 预览，解析被拒绝，创建草稿保持禁用。截图 `invalid-import.png`。

## 执行说明

最初几个自动化等待使用了不正确的按钮名称或“保存后弹窗关闭”假设，已通过当前 DOM 重新定位。新增业务对象保存后弹窗重置是现有行为，不能把等待超时当成保存失败。

后端重启期间已有浏览器页收到连接失败；这些与停机窗口一致。没有把该页控制台称为零错误。`verify_readback.py` 只读核实持久化状态，不会重放发布、删除或模型调用；角色脚本会创建新的独立测试账号/模型。

本轮前端回归命令：

```sh
cd mateclaw-ui
npx vitest run src/features/semantic/graph/__tests__/workbenchEntityTypes.test.ts src/features/semantic/graph/__tests__/semanticWorkbench.test.ts src/features/semantic/graph/__tests__/workbenchFocus.test.ts src/features/semantic/ontology/__tests__/ontologyProjection.test.ts
VITE_UI_PROFILE=enterprise npm run build
```
