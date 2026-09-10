# OWL 逐项证据核对入口

原规格为 `../semantic-owl-01/capability-matrix.json`，87 行，每行 P/N/RT/EDIT/CTX，共 435 项。运行 `python3 scripts/semantic-owl-audit/matrix.py` 更新同目录 JSON。

当前索引：311 个 VERIFIED_EXAMPLE、50 个 PARTIAL、74 个 MISSING_DIRECT_EVIDENCE。**这不是完成率，也不是完整 OWL 支持声明。** VERIFIED_EXAMPLE 只证明冻结示例对应路径；所有行仍受矩阵末尾的跨构造参数维度、持久化/UI/推理等独立要求约束。缺少映射不等于代码缺失。

下一步按以下顺序处理，避免重复测试已通过示例：

1. 对 74 个缺少映射项先读现有测试并匹配原 negative_spec/positive_spec，能直接支持的归档，不能支持的再补具体用例。重点是逻辑反例、声明闭包、datatype map、RDF/XML 边界与导入闭包。
2. 对 50 个部分证据项明确还缺哪一个断言。语法缺操作数不是逻辑不一致证据；校验失败不是发布事务或上下文保留的证据。
3. 单独核对参数维度：基数 36 组合已通过上下文路径；datatype/facet、NaN/INF、时区、值等价、匿名个体复用、嵌套及跨公理组合不能靠 435 项编号数量代替。
4. CTX 与 M7/M8 参照各自索引；当前全量回归见 full-backend-latest-run.json，之后新增仅测试的定向结果独立记录。
5. VAL 的正式 162 次实验和专家盲评仍是独立门槛。当前模型 HTTP 403 已提出配置问题，不能用工程测试代替业务效果验证。

索引只引用已存在且可解析的证据文件，保留原规格哈希，不改写原规格中的 planned 状态。原始标准/运行时证据仍是判定依据，脚本生成成功不等于验收通过。
