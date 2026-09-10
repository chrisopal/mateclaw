# CTX-INFERENCE 当前缺口与接入约束

状态：下列记录为接入前发现的缺口。2026-09-09 已实现可选推理接入与短事务边界，H2 定向回归、MySQL 61 项、隔离新包启动/历史读回已通过；不能再把“固定 NOT_RUN”视为当前实现。

- 原要求：`docs/validation/semantic-owl-01/context-contract.md` 的 CTX-INFERENCE；后续计划要求 OWL-03 后接入。
- `SemanticContextService.context` 无条件构造 `NOT_RUN / NONE`，请求 DTO 无推理选项。
- 独立 `SemanticReasoningService.reason` 已有运行前后快照校验与结构化状态，但这不证明 context 接入完成。
- context 当前事务 timeout=15 秒，而规划 worker 可运行 30 秒。直接在现有事务内调用会产生资源生命周期冲突。

## 接入条件与验证依据

1. 默认只读检索仍为 NOT_RUN；显式推理参数进入签名 cursor 的查询摘要，禁止分页切换任务。
2. 推理须使用 context 已固定的 asOf、revision、document/import digest、graphMutationVersion；运行前后重新授权并拒绝不同快照。
3. 有界 worker 不应占用 context 的 15 秒数据库事务等待。读取快照、执行 worker、重新验证采用分离事务边界。
4. 完整结果状态映射，包括失败和资源上限；不能把超时、矛盾或不支持转换为否定结论。
5. 推导结果与原断言分开；无证明引擎时 explanationStatus=UNAVAILABLE，不能编造前提列表。
6. 返回推理信息计入实际序列化预算。分页不能重新运行后拼接来自不同快照的结果，且不应在每页重复启动昂贵 worker。

## 验证要求

- 显式请求的成功、矛盾、超时、不支持、资源上限状态。
- worker 执行期间撤权、事实/证据变化、绑定版本变化均拒绝过期结果。
- 无推理参数继续 NOT_RUN，不启动 worker。
- 推理参数变更使旧 cursor 无效；含推理结果的响应仍满足预算。

这是实施前发现的缺口，不是完成证据。


## 当前实施结果

- 默认无推理选项仍 NOT_RUN；可选 reasoning 参数绑定续页签名，默认 scope=ACCEPTED_FACTS。
- TransactionTemplate 分隔读取阶段，context 的 NOT_SUPPORTED 边界确保 worker 不占用数据库事务。
- 推理结果保留原始 outcome 与 inputDigest；失败或矛盾不输出结论，最小证明仍明确 UNAVAILABLE。
- 结论单独预算分页，续页复用结果并复核完整推理输入；针对问题切片外的证据变化也拒绝过期结果。
- 缓存最多 64 个、单结果 256 KiB、10 分钟过期。容量淘汰和单结果超限已在临时 MySQL 验证。
- 真实 HermiT 经普通 ToolCallback 返回传递性结论，在 H2 与 MySQL 均通过。
- 证据：`context-inference-semantic-regression.json`、`context-inference-mysql.json`、`context-inference-runtime.json`。

剩余边界：未执行真实 LLM 对话或 VAL 盲评；不能以本次接入代替完整 CTX 逐构造矩阵验收。
