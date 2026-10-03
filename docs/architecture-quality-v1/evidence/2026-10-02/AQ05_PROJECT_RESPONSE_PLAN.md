# AQ-05 项目响应运行时边界计划

起点 a4504532caa5e2acdc22c63883d98a86ffb2508d，tree d069ebfb06294e943005a8ebffd63fa14c8736c7，工作树干净。初始 dev m19866wi SCAN_PASS，终端 exit 0。继续采用 origin/dev 的固定基线。

范围：售前私有 API 解码模块、presalesApi、工作台分页消费、接口测试与证据。现有 Workspace 请求、错误传播、请求字节、CAS/receipt、取消和过期结果规则复用；不新增依赖、服务器权限、数据库或迁移。网络 data 先作为 unknown，经实际结构检查后返回原对象。项目 metadata/集合/已声明嵌套字段、请求 workspace/project 身份必须一致；不转换 ID、不补默认集合、不丢历史扩展、不限制未知状态字符串。递归已知记录用显式栈，未知 context/sourceSnapshot/handoffSnapshot 不展开。分页接受真实 long 字符串或安全非负整数，工作台只在经过检查后转换总数。修复视图严格符合后端 metadata 白名单、12 个空集合、id/role 绑定及 true 标记；这不是授权凭据，服务器授权仍为唯一权威。

兼容性：nested sections/coverage/model items 不要求 id；normal contextCards 可缺失；UNKNOWN binding role 保留。独立 wire 审阅确认 statements.evidenceIds 可以 null，本批不解码 statements/sources/members/employees/capabilities。任意已持久化字段并不等于声明类型合法：已声明 optional 字段存在但类型错误（包括 null）将拒绝，opaque 扩展 null 保留；历史坏记录需独立修复而不静默归一化。

先扩充已有 mutation 伪响应为实际完整 metadata/集合，修正 detail 夹具 ID 与编码请求一致，保留全部路径、请求字节与结果断言。先跑合法既有测试，再新增接口负例并在旧 API 证明拒绝断言失败；实现后重复正反例、全售前回归、vue-tsc/lint/format/dev 与精确 staged commit/push 门禁。独立审阅夹具变化与解码兼容。错误不回显原响应/敏感内容，变更响应失败保留既有编辑与幂等重试路径。浏览器、真实服务历史数据、live model、迁移、独立维护者及远端 required CI 未验证，正式 AC 状态不由单测升级。

回退恢复本批 API 解码入口与分页类型，不涉及持久化数据。

实施验证记录：旧合法 fixture 26 项通过；新增30项中25个拒绝断言在旧API失败、5项合法通过。实现后的首轮56项通过；最终售前215项通过、响应新增37项。新增完整manifest夹具经vue-tsc发现 Object.fromEntries 推断丢失动态键，改为显式 Record<string,unknown> 夹具返回类型；保留所有断言，重跑类型成功。全仓 lint:check 报出存量 useAgentRunGroups.ts:185 prefer-const 与43 warning；不修改本批外源码。六个修改文件独立 eslint --max-warnings=0 exit0，统一门禁按既定changed-file影响规则运行。独立 wire/code 审阅关闭大数组 spread 的LOW问题（逐项push），新增2000层直接解码原对象恒等与manifest兼容测试，限定复核37项通过、无剩余发现。LSP/AST工具 transport closed，不能宣称对应检查PASS。

完整暂存门禁 ds31mykc FAIL/submission_ready=false：ui-vitest 进程 exit1，JSON910项通过但stderr ECONNRESET；其他format/lint/type/Node/两构建成功仍不能提交。原配置全UI重试912项中两项超时（约989秒），其余910通过；pmset日志对应03:07:08维护休眠990秒→03:23:38唤醒，吻合运行暂停。保留两次失败日志，未调整断言/超时/门禁。后续完整门禁使用 macOS 原生 caffeinate -is 包裹检查进程，仅该进程期间临时抑制系统休眠，不改变系统设置；这是运行环境保活，不是跳过检查。
