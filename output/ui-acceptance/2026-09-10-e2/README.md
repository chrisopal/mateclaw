# E2 第一批标准投影验收

2026-09-10，基线 `46ff899f`，`codex/enterprise-semantic-core`。本轮完成 E2 第一批标准只读投影；复杂匿名表达式树留在第二批。

## 实现

- core `OntologyDisplayProjection` / `OntologyDocumentPort`：JDK 展示契约、快照摘要、typed IRI 节点、来源公理引用和明确的覆盖状态。
- owl `OwlDocumentAdapter`：通过 OWLAPI 对象访问投影命名结构、等价/互斥类、逆属性、属性特征和多语言标签；类型与个体同 IRI 分离。非二元关系有界展开并显式标记部分覆盖，不推断事实。固定导入折叠、来源单独标识。
- server `OntologyApplicationService` / `OntologyWireMapper` / `OntologyController` / `OntologyDtos`：独立草稿和已发布 projection GET，复用 viewer 授权、归属检查、固定依赖装载、草稿版本 CAS。limit 默认 500，允许 1..2000；没有写入、推理、缓存或数据库表新增。
- UI `standardProjection.ts` / `useOntologyProjection.ts` / 工作台、画布及两个页面：生产图形使用标准投影，停止消费旧字符串展示解析；保留原组件和 E3 写边界。增加对称边、特征提示、来源覆盖列表、错误重试、截断说明和快照过期保护。加载失败保留原公理及高级编辑入口。
- 简化：复用现有授权、解析、来源导航和幂等写接口，无新依赖；不再让前端从 Functional Syntax 猜测生产图形。历史解析函数仅供既有测试夹具使用。

## 验证结果

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 最终 OWL 适配器测试 | 138 通过 | verification.json |
| 新投影 / 既有本体 server 集成 | 4 + 13 通过 | verification.json |
| 前端本体单元/组件测试 | 17 文件，82 通过 | verification.json |
| E2 草稿与已发布页面 UI | 18 / 18 通过 | coverage.json、results-final.json |
| 既有 E1 图形 UI | 32 / 32 通过，零写请求 | e1-regression.json |
| E3 简单编辑 UI | 11 / 11 通过，独立回读 | e3-regression.json |
| ESLint / 精度 / vue-tsc / enterprise build | 通过 | verification.json |
| server reactor package | 通过，JDK 21 | verification.json |
| 覆盖台账审计 | PASS，18 项全部执行 | coverage.json |

本次共执行 61 组 UI 流程。范围为 E2 新控件及 E1/E3 直接回归，不代表全站验收。最终窄屏容器 clientWidth 与 scrollWidth 均为 312；页面 document/viewport 均为 390。桌面截图、覆盖列表截图和窄屏截图已经人工查看。

环境为 Chromium、enterprise Vite `127.0.0.1:5189`，从当前构建包启动的隔离 H2 OWL 后台 `18109`。准备合成资料时建立专用发布版本和草稿，未修改业务本体。主夹具在后端重启前建立，重启后读回 draftVersion 3 和相同摘要；只读 UI 过程无写请求。`readback.json` 验证 21 条根公理 + 2 条固定导入 = 23，class/individual typed ID 分离，特征及边类型正确。

E3 回归实际修改另一个合成草稿，新增/替换、CAS 输入保留和重新核对、响应恢复均通过，19 条未涉及公理逐条保留。请求丢失测试为 **SIMULATED 网络故障**：真实服务端提交后丢弃响应，再复用原操作编号，版本仅增加一次。

## 发现与修复

1. 根本体包装对象用引用比较排除导入，导致根公理重复计数。将排除改为本体相等性比较，并增加精确 root/import 数量回归；真实 API 从 44 修正为 23，未覆盖计数从 24 修正为 3。见 initial-provenance-failure.json。
2. 图预算裁剪后的受影响公理可能仍标记 FULL。补齐所有受影响来源的部分覆盖标识及图截断状态，添加 n-ary、被裁剪节点、孤立 label 测试。
3. 390px 覆盖列表内部长状态文本溢出 5px。增加所有覆盖文本的换行规则及容器内部宽度断言；保留 mobile-initial.png / visual-initial.json，最终 mobile.png 无内部横向溢出。
4. 初次脚本将折叠公理的不可见正文当作缺失，属于测试脚本错误；改为先展开原公理再核对，失败与重试链最终通过。脚本运行环境没有全局 URL，最初调用未执行 UI 检查，已改为兼容的路径提取。初次结果保存在 results-initial.json。

## 重放

使用已有受控登录浏览器。通过 browser_run_code_unsafe 的 filename 参数执行 prepare.js，得到新合成 ontologyId；导航到 `http://127.0.0.1:5189/semantic/ontologies/<ontologyId>/edit` 后执行 run.js。该脚本从当前 URL 读取 ID，不依赖这次创建的 ID；固定合成内容预期为 21 条根公理和 2 条导入公理。

共享回归脚本分别是 `../2026-09-10-e1-fix/run.js` 与 `../2026-09-10-e3/run.js`。E3 每次创建新的独立夹具。台账检查：`python3 /Users/guojiexie/.codex/skills/ui-regression-acceptance/scripts/check_coverage.py output/ui-acceptance/2026-09-10-e2/coverage.json`。

故障测试对 projection GET 注入断网，恢复后调用真实接口；截断测试将请求预算降为 2，使用真实服务端有界响应。这两项明确标注模拟，不冒充真实网络故障。API 只用于准备夹具和读回；图形、筛选、来源、错误重试均通过真实控件操作。

## 限制

匿名交/并/补、嵌套量词、限定基数和有序属性链尚未图示为表达式树，均保留原公理和覆盖原因。导入展开与编辑不在第一批范围。未执行新一轮 MySQL/Kingbase、Safari/Firefox、暗色专项或全站测试；本次服务只读，未新增持久化逻辑。构建仍有原有大 chunk 提示。
