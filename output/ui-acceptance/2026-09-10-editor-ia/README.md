# 本体业务模型编辑验收

本次将本体草稿编辑改为“业务模型 / 参考资料 / 检查发布”，主操作收敛为保存、检查或发布；文档交换和高级维护归入“更多”。新增对象只填名称，关系选择对象，属性选择内容类型，规则选择关联要求及数量。前端发送结构化业务命令，后端负责系统标识和 OWL 映射。

主要代码：`OntologyEditor.vue`、`OntologyModelWorkbench.vue`、新增 `BusinessModelForm.vue` / `businessModel.ts`、`useOntologyDraft.ts`；服务端 `OntologyWireMapper` / `OntologyApplicationService` 与 DTO/Controller；资料选择相关 `OntologySourcePanel` / `sourceSelectionApi` / source-material 读取接口。

保留权限、草稿版本比较、重复请求恢复、发布版本不可变、来源快照和复杂 OWL 文档交换。业务表单仅支持明确映射的对象、关系、属性与五种直接关系规则；复杂嵌套或带注释的规则通过高级维护保留，不在简化表单中强行覆盖。

## 验证

- 前端本体测试：20 文件、106 项通过；最终文案调整后工作台测试再次通过。
- 后端：模型映射集成 15 项、资料集成 7 项通过；包含规则更换关系/对象、复杂规则拒绝、版本冲突和重复请求恢复。
- TypeScript、修改文件 ESLint、精度检查、生产构建、diff 检查通过。
- 真实 Chromium：新增设备和传感器、建立关系和属性、保存数量为 0 的规则、改名、取消重开、资料标题选择/正文摘录、刷新回读、OWL 双格式导出、Functional `.owl` 导入、检查并发布真实合成版本。
- 1366 / 1440 / 1920 / 390 宽度均无页面横向溢出；真实深色主题、键盘打开/Escape 关闭和焦点回返已验证。测试使用独立标签页，用户原生视口保持 `null`。
- 本地 18109 已重新构建启动，健康状态 UP；5189 前端使用当前工作树。没有远程部署。

`coverage.json` 是本轮新增及重组交互的 14 条验收链，非整个应用控件穷举。复杂表达式、高级 OWL 原始编辑、权限矩阵和来源复核详细流程沿用已有测试，本轮未逐项重新进行浏览器验收。

## 复跑

使用已登录的 Playwright 浏览器工具调用 `browser_run_code_unsafe`，`filename` 指向本目录脚本绝对路径。顺序：`business-flow.js` → `source-flow.js` → `layout-flow.js` → `exchange-flow.js`。

`business-flow.js` 每次创建隔离模型；把返回 ID 更新到另外三个脚本。`exchange-flow.js` 导入的 `import-model.owl` 是本次导出 Functional 文档加换行的合成文件；新模型复跑时应先导出该模型并以此准备导入文件。发布后如需再次验收编辑，先为该合成版本创建新草稿。不得用生产模型替代这些写入测试。

脚本修正记录：Element Plus 将 testid 传递给 textarea 本身；响应体须在导航前读取；工具环境没有全局 Buffer，导入改为本地合成文件；响应式截图须等待侧栏过渡结束。初次窄屏截图为过渡中间帧，最终以 `mobile-final.png` 及复跑截图为准。

运行中曾观察到独立通知摘要接口 500；本轮本体操作与最终页面未出现对应错误，该通知接口不属于本次改动。构建保留现有大包提示。
