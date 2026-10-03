# AQ-05 领域响应DTO与handoff边界整改计划

起点ce293693fcca7a3250e5950fda02083a7e0adbfb/tree d47ff6e9a93c4e55a922744ca6ce9b53455ea145；origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，隔离工作树初始干净。前轮提交/推送/PR回读为progress，完整目标继续active。

气味：项目集合统一Entity[]、嵌套章节/响应/引用共享整个PresalesRecord，handoff返回unknown绕过准入。先保护已有project/repair/query/editor/workbench回归，盘点真实服务构造与已保存wire，建立域内公开Requirement/Clarification/SolutionRevision/GenerationTask/Artifact/Handoff DTO及材料/基线/fit/review/release。缺失保留历史optional，必需id/task.status沿用已有边界；已声明新字段从unknown检查，不靠断言假证明。Handoff检查schema/捕获workspace/engagement/caseRef与核心结构，原对象/JSON/扩展保留，不改冻结快照。

范围api/presalesDomainTypes.ts、presalesApi.ts、presalesResponse.ts、新domainDto.test.ts及仅必要类型消费修正。公共导出保持旧名，仅本域依赖，复用decoder primitives/record遍历；无新依赖/schema框架。类型不代替server auth/事务/来源，raw状态保留wire并由既有Known/Unknown/Missing展示；完整领域状态政策/V2仍待。

先锁旧回归、再新增真实构造字段与nullable coverage.percentage/字符串ID/重复refs/部分历史记录/opaque snapshots正例，非法已声明字段/嵌套/unsafe revision负例；handoff错scope/project/schema/核心形状拒绝，实际Axiosadapter/对象和JSON字节保持。取得旧准入与编译合同实际红例后再修，不削弱旧断言。每片dev/回归/type/lint/格式；精确staged/普通hook/checked push、限定独立只读review、源码/日志SHA归档。

新增声明字段的错误类型会从未知扩展变成拒绝，真实存量抽样未完成。不从write payload推断返回必填，不把Handoff校验当摘要/来源授权。冻结handoffSnapshot/sourceSnapshot/assertion/rejectedOutput保持unknown，使用合成夹具，无DB/旧Flyway/权限/生产操作。回退仅恢复客户端声明/准入，不写存储。所有46正式AC NOT_RUN，ADR Proposed；后端用例、SQL/V2迁移、域状态/error、三方言/恢复/黄金字节、浏览器/异步/模型/维护者QA/requiredCI仍待。

开发操作记录：首次计划写入误用了UI cwd，FileNotFoundError在写入前发生；set-e未执行后续回归。已改绝对路径重试，不计为检查结果。


实际行为保护4文件181项。初始dev afzycwss SCAN_PASS。新增旧准入反例34失败/3通过（37），旧vue-tsc真实exit2六个字段/返回形状问题；实现首轮38合同绿、编译发现旧handoff mock缺必需信封及Solution status/evidenceRefs漏声明，补真实信封保留全部断言及声明后376售前/type通过，dev fyd8ff44 SCAN_PASS。

独立来源盘点确认公共authorId/previousId、澄清answeredBy/At、方案baselineVersion/provisional/fitGapRefs/coverage.percentage、任务pin/queuedAt/finishedAt与发布manifest字段。历史读取不补齐，不能将新生成保证升为必填；日期原始UTC LocalDateTime/Instant文本不重写。模型信封coercive检查不保证原始节点类型，schemaVersion/needsHumanReview/assumptions/unknowns/warnings保持unknown；七个items/capabilityMaps/cases/solution/solutionDraft/review/reviewDraft分支只校验已声明显示字段。context副本/sourceSnapshot/assertion/rejectedOutput/frozenhandoffSnapshot仍opaque。

基线引用nullable事实元数据先1/40失败，再以迭代work项reference语境/两个WeakSet区分普通记录，保留 ontologyRevisionId/evidenceIds null，仅引用允许；共享对象不能借引用洗白普通材料。首次实现脚本遇到formatter展开数组，漏插entryReference导致两条TS2304与16项运行失败，且旧find callback把nullable引用标成Record导致编译错误，修正后378项/type/lint通过。组件仅删错误callback注解，旧Workbench只补pending handoff信封，取消/撤权断言不变。

独立review MEDIUM：发布冻结sourceRefs将基线对象与澄清原节点合并，不能当纯对象数组。新增混合frozen正例先真实1/41失败，再改为unknown[]仅检查数组，ordinary sourceRefs仍string[]，不改持久化/冻结JSON。最终56新合同、全售前16文件394项、vue-tsc/六文件零警告lint/固定Prettier通过；独立闭环56/type/三文件lint exit0，MEDIUM关闭无新缺陷。LSP Transport closed，不冒称LSP/AST PASS或维护者/业务QA签收。最终dev结果另见dev-last日志。新增字段错误类型准入收紧/nullable引用修复/冻结形态覆盖分别记录，生产历史存量与真实服务器端到端未抽样。
