# AQ-05 逐动作命令载荷工程验收

16种售前action与各自已声明写字段建立类型关联，编辑命令沿用同一个Intent，页面接受完整命令并在传输前剥离kind/continueEmployee。原API运行时、权限/CAS/receipt/校验顺序、i18n/主题与数据库保持。类型化不是权限或服务端schema验证，也不是完整领域DTO验收。

起点27410414be0994c0bbbbe0080dc07e87c1066587，固定origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；源码0fd3d6b9450c51289c7ab3165d14e25ba143c328，tree 2369bf4dbae59c0aac801b34f658c752ad3cf8b1。计划AQ05_COMMAND_PAYLOAD_PLAN.md，机器证据command-payload-test-results.json；其中逐文件源码SHA、日志原输入SHA/归档SHA与遮蔽次数可核对。显示副本仅规范行尾/末尾空行，不改原检查结果。

## 修改与兼容

- api/presalesCommandTypes.ts：逐动作Payloads映射与Intent判别联合；已声明ID为string，嵌套sections/requirementResponses/issues和引用数组明确类型；baselineVersion为number，模型result/contextSnapshot/presentation仍unknown。
- api/presalesApi.ts：CAS/operationId与Intent组合为请求，保持既有公开API运行实现。shared/editorSubmission.ts把command分支关联到实际编辑动作；不改变JSON-copy、默认值、required/answer-source规则。
- pages/PresalesWorkbench.vue：解绑、发布、审批、归档和编辑保存都传完整intent，避免分离action/payload；保留原请求action→payload→expectedVersion→operationId顺序。UI元数据明确剥离，repair白名单、captured scope/session、批准、保存/409/receipt保持。
- 新commandPayload.test.ts：16动作通过真实Axios序列化adapter逐字节比对原JSON及捕获Workspace/编码project ID；不是实际服务器网络验收。编译合同检查已声明ID/数组/嵌套字段错误类型、editor payload对应和模型unknown。旧125项测试源码/断言未修改。

写字段保留optional及扩展unknown，兼容原不完整请求和服务端授权→来源→回执→CAS→业务校验顺序。status/origin等原文本不trim/uppercase/默认升级，不以展示词表定义领域批准。ARCHIVE原忽略扩展仍可序列化；服务端继续完整验证非可信模型数据。

## 正反例与真实检查

行为保护旧3文件125项通过。初次测试fixture的map把action推宽，先修正测试上下文后，旧请求vue-tsc实际exit2：17条仅合同错误（16错误字段/嵌套及1 narrowed-title）。新源码类型检查exit0。Vitest运行expectTypeOf不等于编译证明。

独立评审发现ReviewIssue只声明历史text，实际编辑器使用description。最初插入脚本anchor不匹配，失败发生在写入前；review-red空日志实际exit0，不计红例。重新正确插入description:number后vue-tsc真实exit2一条TS2554；补description?:string保留text及真实字段正例，最终exit0。限定独立复核MEDIUM关闭、无剩余发现，独立17命令合同/vue-tsc/五文件lint exit0；LSP不可用，不冒称LSP或维护者签收。

- 主线程最终售前15文件338项，通过；新增17项，旧测试未削弱。vue-tsc、五文件零警告eslint、固定Prettier均exit0。
- 初始dev ht7tx20i、源码dev xih076gs均SCAN_PASS；这是扫描，不能代替提交门禁。
- 精确源码门禁：staged lrbxkot_ PASS/submission_ready=true、hook jfpcd5e3 PASS/submission_ready=true，target_identity均等于源码tree。完整UI1037、Node5（含完整18项HTTP/分页子进程）、固定格式/lint/vue-tsc/ID精度、enterprise/classic两构建通过；全Vitest日志无socket hang up/ECONNRESET。原传输诊断/分页夹具回归继续执行。
- Java与cost-tool按固定前端影响规则NOT_APPLICABLE；不借旧后端绿灯声称本批已测。实际命令/步骤报告归档于command-payload-tests。

## 未完成与回退

完整Requirement/Clarification/SolutionRevision/GenerationTask/Artifact/Handoff DTO/runtime schema、领域状态与稳定错误、后端ObjectNode用例拆分、SQL分页、V2单写切换/三方言迁移/备份恢复仍待。真实浏览器角色/409并发/Workspace/两主题窄屏、异步重启/取消/晚到结果、模型质量、独立业务QA与维护者控制面批准未完成，required远端CI NOT_VERIFIED。46正式AC全部NOT_RUN，PR保持draft，ADR Proposed。

回退恢复本批五份源码及计划/证据，恢复宽泛类型但不操作数据；无旧Flyway/新依赖/生产数据变更。原项目WIP未触碰。服务端授权和事务不由类型声明替代。
