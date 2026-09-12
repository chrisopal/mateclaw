# T12 端到端续验与手册更新

执行：2026-09-12 至 2026-09-13。基线 `82b9a2b0`，原持久化 H2、enterprise UI。

**本轮冻结的 12 个端到端场景已完成，发现的 3 处产品缺陷均已修复并重测。T12/M3 的生产数据库、容量及故障演练门槛仍未整体通过。** 本报告补充前一轮设备/图书真实建模证据，不将旧测试数量计入本轮。

## 验收结果

| 场景 | 本轮实际结果 | 证据 |
|---|---|---|
| 智能体知识问答 | 实际调用 semantic_search，回答 P-101 是泵；事实 r2、证据 ID 正确；UNKNOWN 不证明今天有效 | query-retest.json、readback-results.json |
| 来源治理 | UI 撤回来源，可信关系 1→0；合成原文删除后扫描发现 1 项影响；UI 保留历史并刷新，回读 REVIEWED | source-review-failure.json、source-review-passed.png、readback-results.json |
| 实体与关系 | UI 创建设备/产线；核对精确原文并审核关系；关系图及中文断言显示成功 | relation-fixture.json、relation-accepted.png |
| 版本迁移 | 计划→批准→执行→回滚；带 2 实体/1 事实 v2→v3→v2，四次事实修订保留 | relation-migrated-readback.json、readback-results.json |
| 归档恢复 | 归档后只读，恢复后版本仍停止新绑定；管理员单独开启 v2 | lifecycle-revisions.json、ui-run.md |
| 查看者权限 | 可读列表、发布版本、草稿；编辑入口不可用 | roles-result.json |
| 成员权限 | UI 创建模型并保存对象、刷新回读；发布不可用 | roles-result.json |
| 工作区隔离 | 对另一真实工作区本体，浏览器无内容且 API 拒绝 | roles-result.json |
| 响应式 | 列表/编辑/版本 × 390/768/2560，共 9 视口；6 次适应画布点击 | responsive-result.json、responsive/ |
| 人工建模 | 对象、关系、属性预览确认保存，刷新持久化，三版检查发布 | lifecycle-revisions.json、readback-results.json |
| 错误导入 | 损坏 Functional 文件预览被拒绝，创建草稿禁用 | invalid.ofn、invalid-import.png |
| 版本比较 | v1→v2 正确显示新增工厂及文档变更，表头中文 | ui-run.md、lifecycle-revisions.json |

角色脚本共 11 项断言通过，响应式 9 项通过；这些是上述场景的子检查，不与 12 场景相加为统一测试数。

## 修复与简化

1. 查询响应新增 `termLabels`，仅返回选中事实引用的模型术语标签，保留原 `predicateLabels`。修复前真实模型只能看到类型 IRI；修复后可靠回答“泵”。未改变权限、可信事实过滤或业务时间语义。
2. 实体类型下拉复用已有业务模型投影，只列 class 节点并显示业务名称。删除“所有 signature IRI 都是对象类型”的错误推导；提交值仍是原 IRI，未知显示标签仍可回退 IRI。
3. 来源扫描/复核的图版本字段复用现有数字计数序列化器，修复扫描后提交决定的 400。保留 Snowflake ID 字符串和严格请求校验，没有通过放宽边界规避错误。

运行配置另有一项纠正：知识查询工具原为禁用状态，已管理员启用并绑定专用验收智能体。建模师的误用失败轨迹保留，不作为成功问答。没有新增依赖、重置数据库或覆盖既有用户数据。

## 测试与运行证据

- Java 21：查询窗口 3 项、来源变化集成 6 项，共 9 项，零失败/错误/跳过。来源计数回归使用真实 MockMvc HTTP 序列化路径。见 test-results.json。
- 本轮相关前端 4 个测试文件、10 项通过；类型检查、改动文件 ESLint、enterprise 构建通过。保留既有大分包构建警告。
- 后端最终打包成功，原启动脚本启动，18109 健康检查 UP；5189 enterprise。最终构建已加载数字版本与查询名称修复。
- `verify_readback.py` 最终通过：3 版本、2 实体、4 事实修订、2 计划回滚、来源保留历史、可信为 0，旧设备案例仍保留 1 条可信事实。
- 手册更新为业务生命周期顺序，32 张真实/历史图片内嵌。真实离线浏览器验证目录跳转、图片放大/关闭、附件下载一致，零页面错误；详情见 docs/user-guide/ontology/verification-result.json。

## 复跑

```sh
python3 output/ui-acceptance/2026-09-12-t12-e2e/verify_readback.py
node output/ui-acceptance/2026-09-12-t12-e2e/roles.cjs
node output/ui-acceptance/2026-09-12-t12-e2e/responsive.cjs
node docs/user-guide/ontology/build.mjs
```

只读回读脚本不执行模型、发布或删除。角色脚本会创建新的独立合成账号和模型，密码只在内存；响应式脚本只读固定样例。环境依赖和实际 UI 步骤见脚本及 ui-run.md。历史失败截图/轨迹保留，不能将其当最终成功截图。

## 仍未验收的范围

- 这是明确场景的端到端通过，不是每个控件、所有角色/视口组合及所有 OWL 表达式的穷举验收。复杂 IRI 替换、同一性治理、多来源修订冲突和所有弹窗组合仍有覆盖缺口。
- 原生 MySQL/Kingbase、本机不可用 Docker 对应的迁移演练、生产容量/并发目标、真实执行中断与供应商限流尚未完成。前一轮隔离备份恢复不能替代生产恢复演练。
- 本轮关系候选通过 API 准备，审核与证据核对通过 UI 完成；不能称为从原文自动抽取关系的全新 UI 验收。自然语言和资料建模主链见前一轮报告。
- semantic_search 当前按文本包含匹配，多词自然语言检索可能无结果；真实智能体通过简化检索词得到事实。没有宣称语义召回率或行业准确率达标。

保留现有数据和 enterprise 启动方式，仅提交本地 Git，不推送远端。
