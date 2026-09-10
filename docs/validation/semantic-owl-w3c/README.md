# W3C OWL 2 外部用例验收

来源：[W3C 官方测试库入口](https://www.w3.org/2009/owl-test-cases)指向的 [2009-11 归档](https://www.w3.org/2009/11/owl-test/)，原文件为 `all.rdf`；作者和描述保留在原始 RDF 中。下载地址、日期及 SHA-256 见 `source.json`。这是一份固定历史归档，不声称是现行标准的全部测试或认证。

[OWL 2 Conformance](https://www.w3.org/TR/owl2-test/)区分 APPROVED/PROPOSED/EXTRACREDIT、DIRECT/RDF-BASED 语义和 normative syntax。测试全部通过也不等于证明完整标准一致性。

`python3 scripts/semantic-owl-w3c/index.py` 校验原制品摘要并生成 `case-index.json`：489 个唯一用例，其中 266 个明确标为 Approved、DIRECT、DL；其余记录排除理由。索引只表示范围，当前所有用例的执行状态仍为 NOT_RUN。待接入实际标准解析器和有资源限制的推理入口后，逐项记录通过、失败、资源上限与未支持契约。

正式有界快照记录在 `results-standalone.json`，共 266 项：264 PASS、2 GAP、0 FAIL；命令、只读 classpath、代码与归档摘要见 `results-standalone.manifest.json`。两个剩余 GAP 是 `WebOnt-description-logic-208` 与 `209` 在 15 秒 consistency 上限内超时。singleton `EquivalentClasses` 和两个 tree-shaped shared-anonymous query 的独立证据分别见 `i526009-directed.json` 与 `tree-anonymous-directed.json`。

不得自动删除测试中的公理、补造语义、在线解析未锁定 imports、将异常改成 false，或把不支持用例计为通过。harness 对有共享匿名节点的树形 ABox 只按标准 OWL 直接语义合成为嵌套 existential；环、多父共享、不等约束及其他不支持形状继续记录为 GAP。
