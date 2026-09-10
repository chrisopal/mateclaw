# OWL-01 测试规格与验证边界

本轮交付测试定义与可复用输入。`capability-matrix.json` 的 435 个测试 ID 是后续要求，**不是 435 个已实现/已通过测试**。11 个 Functional Syntax 文件是语义测试输入，另有一个 RDF/XML 输入；本轮只验证清单关联和 XML well-formed，不使用自制解析器冒充 OWL 校验。

## 标准矩阵测试生成规则

每行 `{id}-P` 将 positive_spec 包装为含完整合法声明/前缀/imports 的完整文档，预期 parse、保存、再加载保留结构。文档级规则行需独立测试文档，不套片段包装。`{id}-N` 测 negative_spec，含语法错误、profile 全局组合错误或明确不一致模型；预期分别断言具名错误，不能统称异常。`-RT` 在 RDF/XML 与 Functional 两个方向往返；`-EDIT` 修改其他 label/公理后该构造仍保持；`-CTX` 验证授权者可通过公理 ID 取得原构造，非逻辑注释不变成推理结论。

所有构造至少一项具体合法输入。negative_spec 是每类最小反例要求，OWL-02 实现时必须展开每种构造的具体操作数与预期，而不是复制一个通用 parse error 测试充数。组合测试另见 fixture-manifest 与 datatype/全局限制维度。导出顺序/前缀允许不同，匿名个体按一致重命名比较；不能比较公理条数就判无损。

## 本轮已落盘的语义夹具

以 [fixture-manifest.json](fixture-manifest.json) 为输入与预期的唯一清单；`semantic_execution=NOT_RUN` 不得由静态脚本改为 PASS。

- SEM-01：存在性与开放世界。
- SEM-02 / N：功能对象属性推出同一；增加不同断言后矛盾。
- SEM-03 / N：互斥类型导致整体矛盾；只有不可满足类而无实例时可整体一致。
- SEM-04 / N：功能数据属性两个不同整数值矛盾；不同词法表达同值不矛盾。
- SEM-05：domain 类型与关系链蕴含，不能推出物理根因。
- SEM-06：transitive 非简单属性用于最大基数，DL profile 拒绝。
- SEM-07：非法 integer 词法，标准适配/数据值检查不得悄悄改为字符串。
- SEM-08：正负同一对象断言矛盾；与事实导入审核边界联测。
- domain.rdf：XML 可解析；预期 OWL 中由属性 domain 推出 :a 为 :A，推理尚未运行。

## 治理和业务规格

完整契约引用：[CTX](context-contract.md)、[RESET](reset-spec.md)、[后续文件级任务](../../superpowers/plans/2026-09-08-owl-02-and-followups.md)。还须验证：

| ID | 输入/动作 | 可观察通过条件 |
|---|---|---|
| GOV-CAS | 两人读取同 draftVersion 后依次保存 | 第二次冲突，无丢失公理 |
| GOV-PUBLISH | 发布 v1 后改草稿或 imports 制品 | v1 内容/锁/来源/策略摘要不变 |
| GOV-IMPORT | 导入含个体、公理注释、外部闭包 | 完整保存；已审核业务事实数量不增加 |
| GOV-REPLAY | 同 operationId 不同请求 | 拒绝而非重复创建；相同请求返回同结果 |
| GOV-SOURCE | quote/digest 不匹配或资料越权 | 不能生成有效来源绑定/可信事实 |
| QUALITY-OBS | 实测 0.026 mm，公差上限 0.010 mm | 实测正常入库，独立比较策略标超差 |
| QUALITY-CAUSE | 只有设备/测针/环境关联，无磨损检测 | 输出候选调查方向，不断言测针磨损是根因 |
| INVENTORY-GENERIC | 换成 Item/Location/StockObservation | 通用服务不修改、不内置质量领域词汇 |
| M7-STALE | 支撑公理的来源摘要变化 | 生成待复核项，发布版本不变 |
| M8-UPGRADE | 非空图 OWL v1→v2，含未映射谓词 | dry-run 阻断；映射/审核/CAS 后才能切换，原历史可解释 |

## 本轮验证入口

`python3 docs/validation/semantic-owl-01/verify_artifacts.py` 校验矩阵七列、唯一 ID、规格路径、夹具存在、XML 结构及文档链接，并生成 `static-verification.json`。它不证明 OWL 语法、标准语义、Java 库兼容、业务行为或运行数据库安全。
