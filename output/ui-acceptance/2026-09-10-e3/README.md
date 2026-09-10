# E3a 简单定义编辑验收

2026-09-10，基线 905a5740；实际 OWL 后端 18109，enterprise Vite 5189。仅操作独立合成本体，没有编辑业务本体，也没有发布版本。

## 实现与验证

- 名称、描述、命名父类、对象属性 domain/range、数据属性 domain/range 的逐公理新增/替换；IRI 不变，显示语言可独立选择。
- 预览 REMOVE/ADD 后提交，支持重置、取消、重复/未变化/无效输入拦截。复杂表达式和带注释公理继续使用高级编辑器，未引入简化写回整份文档。
- 统一草稿保护未保存内容、并发请求、权限和发布状态。CAS 冲突保留输入，明确载入最新版本后再预览；不确定响应冻结输入并重试原操作编号及原请求。
- `results-final.json`：11 组真实浏览器流程通过；`supplement.json`：另外 3 组值域/数据域/输入边界通过。每次保存均通过独立 API 回读断言。
- `shared-regression.json`：既有 E1 草稿/已发布视图 32 组 UI 回归通过，无写请求。
- 首次标签替换后其余 21 条公理保留；主流程结束后其余 19 条未涉及公理逐条比对保留，包括复杂 existential restriction。空 imports 和空 policy 原样保留。
- 真实另一客户端写入导致 CAS 冲突，输入保留，明确载入后保存成功。响应丢失为**模拟网络故障**：真实服务端处理后主动丢弃响应；恢复复用完全相同的请求，版本 9 → 10，仅增加一次。
- 桌面 1500×1000 与手机 390×844 截图人工检查，文本可读、预览换行、按钮可操作。另一次稳定手机回读：document/viewport 均 390，取消后焦点回到“编辑此定义”。主流程即时 bbox 包含弹窗入场动画，不能用于稳定纵向几何结论，以截图和后续稳定检查为准。
- 最终 16 文件 / 76 单元和组件测试通过；改动文件 ESLint、Snowflake 精度检查、vue-tsc、enterprise build 通过。构建仍有大 chunk 提示。

## 控件台账

| 控件/边界 | 已执行步骤 | 证据 |
| --- | --- | --- |
| 编辑入口 / 编辑项目 | 选节点、打开、选原公理、失焦、更换项目 | 主流程 label/comment/multi-domain |
| 定义内容 | 新建切换字段、既有条目锁定字段 | superclass / 组件测试 |
| 内容 / 语言标签 | 输入、Tab、语言回显、非法语言、重复及无变化 | label/comment + supplement |
| 目标 IRI / 已有目标 | 无效输入、完整 IRI、选择已有对象、Tab | superclass/datatype + supplement |
| 预览 / 确认 | 检查原公理及 ADD/REMOVE、提交、独立回读 | 主流程所有写入 |
| 重置 / 取消 / Escape | 重置原说明、取消后重开为空、Escape 关闭 | comment/mobile-cancel + supplement |
| 冲突载入 / 恢复保存 | 真实 CAS、重新载入、冻结输入、原请求恢复 | cas/lost-response |
| 显示语言 | en/zh-CN 选择与失焦、目录文字联动 | language |
| 只读 / dirty / 权限 / scope | 已发布无入口；hook 测试阻止写入和过期响应 | readonly + ontologyDraft tests |
| 窄屏 / 焦点 | 390px 表单操作、稳定截图、关闭后焦点 | mobile.png + 稳定浏览器检查 |

## 重放

使用已登录的 Playwright 浏览器与上述隔离运行环境，执行 `run.js`（MCP browser_run_code_unsafe 的 filename 参数）。脚本从既有合成 CMM 草稿 2097694137454493697 克隆新本体，每次返回新的 ontologyId；已发布只读夹具为 2097611586143510530。不覆盖先前测试数据。`results.json` 保留首次探索批次；`results-final.json` 为最终可重放批次。

补充步骤：在最终返回的 ontologyId 中，将“使用测针”的关系值域改为 urn:cmm:Environment，将“环境温度”的属性定义域改为 urn:cmm:Machine；分别预览保存并回读。选择原“测量环境 E3”名称直接预览应提示无变化；新增同名 @zh-CN 应提示重复；语言 bad tag 应拒绝预览；Escape 关闭。此步骤已在 2097886344974053378 验证到 draftVersion 12。

## 范围与限制

未新增依赖，复用原公理接口和幂等协议。未验证非空导入闭包、非空策略和真实业务来源绑定迁移；本次不迁移来源绑定。恢复状态保存在当前页面内，浏览器刷新/关闭会警告，但不是跨会话恢复队列。浏览器覆盖 Chromium，未执行 Safari、Firefox 或暗色专项测试。复杂规则编辑、删除、批量 IRI 重命名仍在后续阶段。
