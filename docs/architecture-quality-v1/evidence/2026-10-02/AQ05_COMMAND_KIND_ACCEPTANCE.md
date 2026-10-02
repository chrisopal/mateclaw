# AQ-05 命令类型工程证据

起点53d12d43f3380b1aee438e17fa5b3bdea588870b，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，工作树干净。改动PresalesDtos、PresalesService、两个新合同及本片文档；旧测试/用户WIP未改。

16个既有action为内部CommandKind，未知保留UNKNOWN+raw。HTTP仍为expectedVersion/operationId/action/payload四组件；action字符串及ObjectNode payload不改。package-private解析方法@JsonIgnore，不进入wire/hash。应用服务三个admin动作、三个repair动作、员工SAVE_AI_TASK限定和命令分派使用kind；授权/来源/replay/CAS/归档/默认拒绝顺序、事务/fence/receipt均保留。默认拒绝未来无handler枚举，分类不能授予执行权。

## 已执行

JDK21：`mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=<manifest classes> -Dsurefire.failIfNoSpecifiedTests=false test`，参数只允许上游无选定类，server计数真实非零。

- 旧实现：新HTTP4 + Integration8 共12/12 PASS。
- 首次类型替换72/72，保留默认拒绝后再次72/72，零失败/错误/跳过。包含纯3（16已知、7未知、四字段原wire）、HTTP4、Integration8、Runtime16、Atomic5、Fence19、Artifact3、ProjectPersistence4、Solution4、GenerationCoordinator4、GenerationController2。
- HTTP合同验证unknown原文及授权/CAS错误顺序、三个admin权限、原序列化request hash、同键回放/异请求409、撤权后repair精确白名单；拒绝不写项目。
- dev0woji4h1/vojgcovj/nktl__fs：SCAN_PASS，仅扫描。显式Spotless apply后readonly spotless:check与git diff --check通过。
- 独立APPROVE_BOUNDED，零CRITICAL/HIGH/MEDIUM/LOW。四Java文件LSP/AST实际尝试均Transport closed；用Maven编译和人工diff证据，不报告LSP通过。

日志经JWT、Spring生成密码及JSON凭据脱敏，输入/输出SHA及替换次数见manifest；源码SHA记录，旧测试无删改/skip/快照更新。

## 提交

源码提交 `7423f4f8dc47ca3f504f40b4478bc72d3708e171` / tree `bb655ce80cd6fccedc6b22bb7a27a11e22f4d39b`。完整暂存门禁 w6e1tv8k、正常提交hook tc63v5y_ 均 exit0/PASS/submission_ready=true，target均绑定该tree。Java6077总数/6007实际执行/70既有跳过、零失败错误；UI846/846、类型/ID/Node及两模式构建通过。无UI formatter/lint目标由固定规则判NOT_APPLICABLE。原真实报告存于command-kind-tests；应用验收仍NOT_RUN。

## 未测与回退

完整命令payload与项目聚合仍ObjectNode，完整AQ05未完成；SQL分页、V2独立对象/依赖/单写迁移、真实MySQL/Kingbase、浏览器/模型/生产重启/人工业务QA、远端required CI均未据本片验收。AC保持NOT_RUN。回退恢复字符串分类/switch和内部解析，无数据库操作。
