# AQ-05 查询 DTO 工程证据

## 范围与实际状态

起点 ee2a6d259bccd0ecd1dc78c00124ffaf9808d8a3，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，初始工作树干净。仅改 PresalesDtos/Service/Controller 三查询、新合同与本片文档。旧测试和其他工作区未改。

capabilities 四个 boolean；sources 基础/启用图固定记录；trustedStatements 字符串 ID/int 修订及原证据快照。Controller 类型同步；不增加授权、SQL、事务、Bean或依赖。字段名、缺省/显式null、200/500上限、graph去重、来源先授权、404降级与其他错误传播不变。证据集合包含null元素时仍保留；复制与不可变封装保持原JSON快照语义。

## 已执行

JDK21 Maven：`mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=<manifest classes> -Dsurefire.failIfNoSpecifiedTests=false test`。该参数仅允许上游没有指定类，server真实测试计数如下，绝非零测试 PASS。

- 旧实现先执行 HTTP新合同5 + Integration8：13/13 PASS。
- 类型替换首次选定回归：64/64 PASS。
- Controller显式类型与DTO快照纯测试后：66/66 PASS（HTTP5、纯2、Integration8、Runtime16、Atomic5、Fence19、Artifact3、ProjectPersistence4、Solution4），零失败/错误/跳过。
- `verify.py --mode dev --base origin/dev`：1hf7dt03、gkl7pan1、0nti63w5 SCAN_PASS，仅扫描。
- 显式 Spotless apply 后 readonly `spotless:check` 与 `git diff --check` 通过。
- 准备时错误调用Python2，测试未生成；随后仅既有8项执行，已单独存档，不当作新合同证据。更正python3后重新执行13项旧实现合同。

日志经JWT、Spring生成密码及JSON密码/token脱敏；原输入/脱敏输出SHA和次数见 query-dto-test-results.json。证据不是手写PASS替代门禁。

## 提交与审阅

独立审阅 APPROVE_BOUNDED，零 CRITICAL/HIGH/MEDIUM/LOW；覆盖字段/授权/顺序/上限/错误传播与Java调用方。LSP/AST实际尝试均Transport closed，使用Maven编译及人工diff证据，不报告LSP通过。精确tree完整commit门禁尚待实际执行，未记PASS。

## 未测与回退

新合同使用真实认证/Workspace/H2项目/Jackson/HTTP，公开Graph/Statement端口为mock；不能证明真实图运行。正式AC保持NOT_RUN；完整项目/命令DTO、SQL分页、V2对象迁移、MySQL/Kingbase、真实浏览器、生产重启与人工业务签收、远端required CI仍未完成。无需改库，恢复三查询实现与DTO/Controller签名即可回退。
