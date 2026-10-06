# AQ-05 项目响应运行时工程核验

源码提交 89672035069e4a61d2a4f8419249fae235743acf，tree 6cfa8fa252eb58d91f85104c57fe37a3854ca1d8；起点 a4504532caa5e2acdc22c63883d98a86ffb2508d，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。隔离工作树起点干净，原项目 WIP 未触碰。工作始于10月2日，最终检查于10月3日完成。

presalesResponse 是本域私有纯解码器。项目详情及五类变更、列表和修复接口先获取 unknown，经实际检查后返回原对象。校验捕获 Workspace/已有项目 ID、字符串 ID、正安全整数版本、必需集合及声明字段；不补默认数据、不改变请求 JSON、receipt/CAS 或服务端授权。create 允许新项目 ID，其他四类变更必须匹配请求 ID。未知状态字符串和历史扩展保留；context/sourceSnapshot/handoffSnapshot 不展开。nested sections/coverage/model items 不要求实体 ID，normal contextCards 可缺失。嵌套声明记录用显式栈逐项入栈，支持深层和宽数组。

修复投影严格匹配后端16个 metadata、12个空业务集合、true 标记及只有 id/role 的绑定，UNKNOWN 角色保留；额外敏感字段或非空业务集合均拒绝。列表不得含详情集合，total 保留数字或规范十进制字符串，仅安全整数供工作台转换。错误固定代码 PRESALES_RESPONSE_INVALID 和通用消息，不回显响应内容。

已有 adapter 正例26项先通过；新30项中25项拒绝断言在旧 API 失败、5项合法通过。修复后响应合同37项、全部售前215项通过；包含五类变更 Workspace 拒绝、四类变更项目 ID 拒绝、新建 ID、64位字符串 ID、2000层原对象恒等、manifest/coverage/model/context兼容及 repair 泄漏拒绝。已有两处伪响应补完整 metadata/集合并使编码路径 ID 一致，保留全部请求字节/路径/结果断言。独立 wire/code 审阅关闭 LOW 数组 spread 问题，最终无剩余发现；限定复核37项和两文件 ESLint 通过。LSP/AST transport closed，不能称这些工具通过。

初始 dev m19866wi、切片 v5gqojqn、最终 n63291a_ 均 SCAN_PASS。精确暂存 10_99hoh 和正常提交钩子 nsttsukj 均 PASS/submission_ready=true，对应同一源码 tree；全 UI 912 项、固定 format、修改文件 lint、vue-tsc、ID 精度、Node 测试、enterprise/classic 构建 PASS。Java 按既定 frontend-only 影响规则 NOT_APPLICABLE；没有沿用旧 Java 成绩。报告、日志及 SHA 见 project-response-test-results.json。

真实失败保留：新增 manifest 夹具类型推断丢失动态键，改为显式 Record<string,unknown> 夹具而保留断言，最终类型通过。全仓 lint:check 的存量 useAgentRunGroups.ts:185 prefer-const 和43 warning 未修，六个修改文件零警告 lint 通过。首次完整门禁 ds31mykc 因 ECONNRESET exit1，JSON910项通过仍记 FAIL；之后全 UI 重试912项中2项约989秒超时，其余910通过。系统 pmset 记录同期维护休眠990秒，吻合暂停；相关111项复核通过。后续门禁/钩子使用原生 caffeinate -is 临时保活，仅检查进程期间生效，不改测试超时、断言、门禁或系统设置，完整复查912项通过。失败记录与主机事件归档。

兼容风险：已声明 optional 字段存在但类型错误（含 null）将新增拒绝，opaque 扩展 null 保留；真实历史服务端数据尚未抽样。standalone statements/sources/members/employees/capabilities 未接入解码，statement evidenceIds nullable 类型契约仍待处理。完整领域 payload/Project DTO、SQL 分页、V2/多方言迁移、真浏览器、live model、业务 QA、独立维护者签署与远端 required CI 未完成。46项正式 AC 仍 NOT_RUN；ADR-AQ-026 Proposed。回退本批 API 解码入口、分页类型和转换，无数据库操作。
