# AQ-09 售前工作台轮询职责工程验收

起始 HEAD `e0d3b26a398fc12e2e8c5e5ec5af283b7939369c`，base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；独立工作树干净，原项目用户工作不触碰。范围和前置行为计划见 [AQ09_POLLING_LIFECYCLE_PLAN.md](AQ09_POLLING_LIFECYCLE_PLAN.md)。本片属于 AQ-09 页面职责拆分，支持 AQ-04/AC-13 请求范围及 AQ-01/AC-21 晚到结果；不把 P1 拆分称为 P0 完成。

## 问题与结果

旧工作台按 operation 建立轮询，对同时运行的两个任务重复整项目 GET；传输取消后只比较 Workspace/项目，同一项目重载或命令返回后仍可能接受旧响应。三个新增真实 Vue/ElementPlus 组件回归在旧代码全部失败：迟到旧来源覆盖受限重载、旧版本覆盖成功取消、两个运行任务导致三次 GET（含初始加载）。原 35 项仍通过。API 替身故意忽略 AbortSignal，以证明接受端必须自行拒绝旧结果。

新增域内 `usePresalesTaskPolling`，负责单个工作台实例的串行项目读取、计时器和取消生命周期；复用现有 DTO、scope 比较和错误映射。页面保留授权读取和严格 repair-context 白名单、命令与表单策略。成功加载/命令结果替换循环，重载/卸载终止旧循环；读取前后检查循环身份、取消和 Workspace/项目，旧 finally 不影响新循环。拒绝较旧 version、受限循环中的未受限响应；按返回项目的全部 RUNNING 任务决定继续，保留 1000ms 间隔和 600 次上限。

页面删除按 operation 的 Map 和内联循环，从 3220 行到 3194 行；新增 96 行领域组合函数，不引入通用框架、全局 store 或依赖。保留字符串 ID、批准/来源/回执/409 草稿语义；不改后端、迁移、历史字节、布局或 i18n。成功取消后的迟到 403 不清空新项目、不再读取旧修复上下文。

## 验证与审核

`npm test -- --run src/features/presales`：旧 35 项通过，新增前三例在旧代码 3 失败/35 通过；修复后 38 项通过，扩大合同后最终四文件 50 项通过、零失败。新增组合函数覆盖串行/重复启动、重置/卸载释放计时器、在途请求取消、旧成功/403 与替换循环、较旧版本、来源限制、Workspace/项目变化、当前403消息及600次终止。现有断言不删除或弱化，只新增 cancelTask API mock 和四个组件用例。

`npm run typecheck`、四文件 `eslint --max-warnings=0`、`prettier --check --config ../.quality/prettier.json` 均退出0。格式化采用现有质量配置；首次默认格式化的非必要存量改动已移除，原组件测试保持。dev `python3 -B scripts/quality/verify.py --mode dev --base origin/dev` 为 SCAN_PASS `5h521lf5`，应用工具链 NOT_RUN，不作为提交凭据；提交/推送使用后续实际精确树报告。

独立只读 `polling_boundary_review` 审阅批准本轮询切片：取消/版本/来源/错误防线和现有严格修复契约保持，测试增量不改 runner/config。审阅读取真实红/绿日志，未自行执行构建；LSP 无新增组合函数诊断，plain tsc 的既有 Vue import 限制由成功 vue-tsc 核验。代码/日志 SHA256 见 [机器证据](polling-lifecycle-test-results.json)。无检查规则、hooks、依赖或运行器变更；测试增量已经独立审核，正式维护人/QA签收仍待完成。

## 未测、风险与回退

真实浏览器两主题/窄屏/Workspace导航、真实服务端授权变化、传输、数据库、多方言、异步重启、模型与正式业务 QA 均 NOT_RUN。本轮组件 API 使用替身，不是业务验收，AC 台账状态保持。页面其余命令确认/编辑/预览异步路径还需分片审核，不宣称所有异步请求都已修复。600次后沿用原行为停止，不新增超时产品提示；版本防线只作用于本循环，不替代服务端 CAS。

回退本片恢复旧页面循环，不改数据库或历史成果；也会恢复三个旧竞态。下一阶段继续编辑/预览职责拆分与正式角色/服务端验收，售前命令和持久化职责及 AQ-06 迁移仍待推进。
