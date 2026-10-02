# AQ-04 公共 Workspace 请求边界工程验收

起始 HEAD `5d56b1db0c41919279005edb7948b1ed3ec28e60`，base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；独立工作树起始干净，原项目 WIP 未触碰。范围与顺序见 AQ04_WORKSPACE_REQUEST_PLAN.md；本片关联 AC-13–16，正式业务 QA 保留 NOT_RUN。

## 缺陷、边界与兼容

原 ontologyApi.scopedConfig 位于语义内部，由售前/投标横向借用；三个包装器通过 spread 覆盖完整 config 的 transformRequest 与 signal。投标上传重复默认转换和 pin，且复制的 transform 最终被包装器覆盖。四项红例在旧实现全部失败：function/array 自定义转换被默认 JSON 覆盖、显式空数组失效、仅 config.signal 的预取消请求仍被派发。

公共 `src/api/workspaceRequest.ts` 使用调用者转换（包含空数组），未指定才取 Axios 默认转换；复制列表，最后强制设置捕获的 X-Workspace-Id，保留数据/headers/params/timeout/response transforms 和 this 绑定。独立 signal 优先，否则保留 config.signal。最终 pin 使用 rewrite=true，覆盖自定义 transform 写入的假范围或 false header；不修改调用者的 header 对象或 transform 数组。Workspace Header 仍不是授权凭据，公共入口不增加 ACL 或角色规则。

公共入口返回全局 Axios 响应拦截器的现有结果，不擅自解包领域 envelope。语义/售前/投标各自保留原 `.data` 与错误适配；语义导出和业务 Blob 请求保持原路径、参数、响应类型与已有 signal。全局 interceptor、Token、语言、ID、批准政策、后台和迁移未改。删除 scopedConfig 和跨域私有 helper 导入，删除投标上传重复 pin/默认转换与手工 Content-Type；直接提交现有 FormData 字段，由实际传输生成 multipart boundary。

两份原有密集 adapter 的固定 Prettier 格式化先独立提交，再提交行为修复。格式前后编译输出的 JavaScript 语法树一致；源文本/规范化文本比较首轮因换行保留不同而失败，未将文本比较误记为行为变化或通过。格式提交的精确树门禁 k04eps8j PASS、submission_ready=true；其完整报告对应自己的树，不冒充后续功能树。

## 实际测试及审核

使用真实 http Axios 实例及原拦截器，受控 adapter 证明 transform 顺序、this、最终范围、JSON/空转换、自定义 header/config 不变、响应转换、预取消和在途取消、三域409错误（语义字段与traceId保留）、上传字段、下载路径/mode/signal与字节。七文件定向 **26/26 PASS**，含既有 ontologyApi 九项及投标下载一项；新增十六项，不删除原断言。前端 vue-tsc 实际退出0。

三个回归通过实际 Axios fetch adapter 向随机端口 127.0.0.1 HTTP 服务发送请求，验证捕获 Workspace、带真实 boundary 的文件和全部 multipart 字段、Blob/ArrayBuffer逐字节相同，以及服务端已收到请求后的取消。夹具只在 localhost，结束关闭连接并恢复 http.defaults。取消时 Happy DOM 的原生 fetch 输出 socket-hang-up/ECONNRESET stderr，但测试捕获取消拒绝、零未处理错误；日志原样保留，不抑制未知错误。

第一轮公共 API 测试导入语义内部触发 AR-003，测试按所属域拆分，公共层合同不再依赖业务内部；没有修改规则/存量 baseline。Node HTTP 夹具首轮缺 Node 类型；移动到现有 test/support 的 Node-only .mjs 并提供准确 .d.mts 接口，没有新增依赖或改 tsconfig/runner。迁移夹具遗漏 slowStarted 引用的失败与未处理拒绝属于夹具错误，修复后25项/类型通过；原红例与静态失败日志保留，已覆盖的类型/迁移夹具失败仅保留上述诊断摘要。定向测试不能代替最终完整门禁；精确功能树、正常 commit/push 报告及远端 SHA 在 PR #5 提交证据中读回。

独立 workbench_confirmation_audit 按 code-reviewer prompt 审阅 helper、三个适配器、所属域测试及Node夹具/声明，无阻断；文件诊断零错误，读取首轮25项与最终26项日志。新增测试仅补断言和装配，不修改 runner/gate/依赖/全局拦截器；该工程审核不替代正式维护人或QA签收。

完整功能树首轮 gt6qxkxk：776项中775通过、一项既有 biddingDownload 测试失败。原夹具只模拟 http.get，公共入口调用 http.request 后出现 TypeError。用真实http+受控adapter替换该夹具，保留Blob对象同一性及URL/responseType/mode断言，另加捕获Workspace断言与finally恢复；不减少断言、不改生产实现/runner。首轮门禁与失败摘要原样保留，修复后26项定向通过，后续完整门禁必须重新执行，不能把首轮记为提交通过。

## 未测、风险及回退

真实浏览器两主题/窄屏及网络导航、业务服务端角色/授权变化、真实409并发事务、多方言、异步重启、生产环境、真实模型和正式 QA NOT_RUN。受控 adapter 与 loopback 验证传输契约，不证明后端来源授权、幂等或真实业务并发；页面迟到结果拒收由之前独立生命周期切片处理。AR-003零容忍封口属于单独控制面审查，本片未调整policy。

回退功能提交可恢复旧适配器；格式提交可独立保留或回退。不改数据/数据库，回退会恢复已证实的自定义转换与取消信号丢失风险。下一步继续售前表单/用例职责、服务聚合边界、单写迁移与真实环境QA。
