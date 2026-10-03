# AQ-05 前端变更请求契约计划

起点2345f23ac2bae0390dedab08f54ecb94d824b2bb，工作树干净；初始dev t0f_dsfa SCAN_PASS；origin/dev基线不变。实际API create/update/command/generate/cancelTask输入全为object，列表为开放Record，编译无法发现漏CAS/operation或动作拼写。

范围：presalesApi.ts、editorSubmission.ts、PresalesWorkbench.vue及新mutationRequest合同测试；文档/证据。复用现有Workspace请求、receipt/editor校验与后端现有Create/Command/Generate/Cancel字段，不新增公共协议/运行时框架/依赖/数据库迁移。

建立固定VersionedMutation(expectedVersion number, operationId string)、ProjectWrite六个可选metadata字段、Create expectedVersion字面量0、Command现有16action且payload Record<string,unknown>显式未验证、Generate现有S1-S8与taskGoal、Cancel仅operationId；ListQuery五个已有过滤与page/pageSize。editor动作和工作台command/generation采用相同类型；create分支显式传0（既有分支body原为0，保持序列化顺序和receipt原输入）。元数据部分保存原可选语义与服务器运行时验证仍保留，不能冒称完整项目/各action payload/schema或模型unknown已经可信。

先在原API新增adapter合同：5 mutation路径/HTTPmethod/原完整JSON字节/capturedWorkspace/64位字符串ID/扩展字段/16action/409和列表params/signal；再类型收紧，编译期验证漏CAS/operation、数值ID、未知action/skill、取消误加项目CAS与任意query拒绝。完整编辑/生成/取消/迟到结果/workspace/409行为由全部售前组件回归复核。无权限/来源/批准/事务/CAS算法或i18n/style改变。

执行旧合同→类型切片→vue-tsc与售前所有回归→dev/lint/固定format→精确commit/push门禁→独立代码审阅/PR回读。回退仅恢复API/调用签名，不触及SQL/数据。正式AC仍NOT_RUN；完整payload/项目DTO/runtime unknown校验、SQL/V2/浏览器/多方言/业务QA继续待完成。

首轮定向178与vue-tsc通过、dev as7lix0z SCAN_PASS；新负例用{}类型被既定no-empty-object-type lint拒绝。改用Record<string,never>表达空JSON对象，保留拒绝缺少CAS/operation的断言，不禁用规则或删除断言；重新执行定向/类型/lint/dev。数值ID负例只证明metadata ownerId，payload中未知JSON仍待runtime字段校验。
