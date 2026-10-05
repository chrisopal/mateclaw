# AQ05 项目修订容量工程记录

起点 HEAD `20e29f2e0c3f0eca381a0d4a454b96acb91054f5`，tree `ef3c766bfe3ad006e66c9ec5377237a39d876f2a`；origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。初始工作树干净。计划先行，dev `wml29bnj` 与首片 `eza14022` 均 SCAN_PASS/submission_ready=false。完整提交/推送结果以精确候选 tree 的正常门禁及 PR 为准。

## 实现与边界

新增项目专用 PresalesProjectRevision，精确正数范围 1..9007199254740991，HTTP expectedVersion 使用 Long 且保留整数 number wire；历史存储整数字符串兼容。项目 CAS、Repository/getLong、历史修订与列表版本、快照、acceptedVersion、工具复核及重启/终态写入全部贯通。条目和语义 int 修订不变，无新依赖、框架或权限规则。

V220 为 H2/MySQL/Kingbase 追加三列 BIGINT 扩容，保留 NOT NULL/nullable/主键，不重写 JSON、回执、历史或产物。冻结源码/旧迁移未修改。全链保留真实主体、来源、回放优先级、事务及完整任务身份比较；小数值采用 IntNode、大值 LongNode，使保存前后的身份一致。expectedVersion 四 DTO 显式数值序列化，避免宿主 Long-as-ID 字符串规则改变旧 hash；全局 ID 仍为字符串。

## 行为验证与真实失败

- 旧实现 RED：跨旧上限 HTTP 写入、重启恢复和权限丢失兜底 3/3 失败，分别返回 VERSION_EXHAUSTED 或保留旧 MAX。
- 第一轮类型转换后测试编译指出 4 个 Java fixture 的 int→Long 调用需要显式适配；修复编译后 129 项中 12 失败。包括全局 Long 字符串化破坏原逐字 wire/hash、Mockito int matcher 不再截获 long CAS，以及独立审阅指出的历史文本基线归零。失败全部保留，未删除原 hash/回放/完整身份断言。
- 修复后 7 类 228/228 通过；新增边界规则 22 项覆盖安全上限、坏值、历史文本、节点读回及 ToolScope 拒绝非法范围。新排队 HTTP 在低位、跨旧上限与新上限-2 下对照独立数据库连接读回的完整任务和快照。
- 独立审阅的历史文本基线问题以 "2"/"2147483648" 两条 RED 复现，精确解析后通过。
- 首次全量售前 762 项中 4 失败、1 既有 skip；四个错误来自旧测试把 4294967298 定义为超范围。将纯超范围反例移到 9007199254740992，保留别名匹配反例，并增加旧边界/4294967298/安全上限的成功正例。之后全量 57 类 **767 项：766通过、1既有skip、0失败**。
- 最后扩展真实 PresalesEmployeeRuntime→分派/授权复核→独立 READ_COMMITTED 结果事务测试，覆盖 2、2147483647、2147483648、9007199254740990 四种项目版本，调用方回滚不撤销已接纳结果，列表同步。该类最终 **40/40**；不把模型外部边界替身当真实模型质量验收。
- Spotless 使用 `-Dquality.base=HEAD spotless:apply` 显式格式化；最终完整格式检查与全 reactor 回归由正常提交门禁覆盖。一次测试编辑脚本因缺 import 假设中止，未写入该运行时测试；随后使用全限定类型完成，最终40项为实际新源码结果。

## 数据迁移与恢复

H2 两项测试实际执行完整 Flyway 新安装到 V220、V219→V220 追加及 validate；对照四张售前表全部字段字符串，包含原 JSON 空白、未知/null、旧 receipt、revision、artifact；核对三列 BIGINT 与空值约束及重复主键拒绝。升级后真实 Coordinator 将旧 MAX 的 RUNNING 保存为 FAILED，版本/列表增至2147483648；旧 CAS 拒绝，原历史/receipt/产物字节不变。H2 SCRIPT→独立库 RUNSCRIPT 后逐表回读，Flyway validate 和无重复迁移通过。

MySQL 8.0 隔离容器修订探针 31/31 断言通过：四表真实 COUNT、有效 JSON 合成夹具、三列 BIGINT/空值/主键、2147483648 与9007199254740991精确写读、旧 CAS=0、mysqldump→独立 schema 恢复及全部字段对账。V220 前后快照 SHA-256 同为 `a4c89dc19b621037b692b2709f2a472502cfb5fd2bdb7001a47c8547be42ec6c`；最终/恢复快照同为 `38cfcc662968ab76969fe0b4c4fd018a74b8953e75e88adbe37914782dc60cca`。V220 源码 SHA-256 `30a579cfb02c7b08ef31914a847e78c5bb4727cd950d30a3d621e8591befae1f` 已与工作树核对；镜像 digest `mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。自有容器已清理。

首轮探针报告虽为 PASS，但种子计数为固定文字、快照未覆盖全字段；复核后未作为最终证据，修订版真实查询后重跑，首轮材料保留。MySQL 仅直接执行 V211/V217/V219/V220 SQL，未运行 V218 Java/Flyway validate/实际 Java Repository；H2 完整 Flyway/运行时证据单独报告。Kingbase、生产升级/回退、真实旧业务样本与正式46 AC未验收。SQL 文件存在不是方言运行证据，H2 MySQL mode 不是 MySQL。

## 审阅与剩余

独立 recovery_next_path 完成设计挑战、实测 Jackson 节点陷阱、初审 P2 和修复复核；最新静态复核无新增可操作问题，不替代维护人批准或主流程实测。SQL 三方言实际运行与全量门禁仍按各自证据报告。

上线前须停止所有旧 writer 并完成备份/恢复演练；BIGINT 列本身不是进程围栏。超过旧 int 范围的新写入后不得回滚旧程序、缩列或重置修订；需暂停写入并前向修复。新 MAX 仍有 VERSION_EXHAUSTED 保护；malformed/mismatched 历史仍缺可证明的修复权威，本片不修改它们。完整 V2 对象独立版本/依赖、正式验收及远端强制策略继续开放。

## 可复核材料

源文件、真实失败与通过日志、JUnit XML、独立审阅、dev 报告及两轮 MySQL 探针保存在 [证据包](project-revision-capacity/project-revision-capacity.tar.gz)，逐文件原始/归档摘要见 [manifest](project-revision-capacity/project-revision-capacity-manifest.json)。归档 SHA-256 `a07972251e81ab1c035f00b547cdb6511102dd5a986fa6f8c27f10cd768bd952`；自动脱敏后已逐项回读校验。正常 commit/push 门禁报告产生于候选 tree 固定后，单独在 PR 与外部证据存档关联，不伪造自引用提交证据。
