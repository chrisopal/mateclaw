# AQ-05 新建生成任务类型边界工程验证

本片按[计划](AQ05_QUEUED_TASK_DTO_PLAN.md)将新建任务从字符串键拼装改为固定DTO，并保护原wire及快照值。仅architecture-quality工作树，HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，分支`codex/aq01b-execution`，初始117项dirty。保留累计WIP，无提交/推送/部署/生产数据操作。

## 实现

新增包内PresalesQueuedTask记录：操作及执行身份、字符串ID、模型/Skill固定版本、RUNNING/QUEUED初始枚举、时间/会话、人工评审标记和contextSnapshot。JsonPropertyOrder与Include.ALWAYS保持原18个字段的次序与显式null。快照在构造与访问时深拷贝，保留未知扩展、嵌套null与大ID字符串。它只表示新排队任务，不能用来解码历史任务状态或声称旧数据已迁移。

GenerationService直接创建此记录，仅在既有SAVE_AI_TASK持久化命令边界转换为ObjectNode。原输入验证、member权限、读取/来源、requestHash、operation回放、版本容量、employee/context/pin调用、存储回读及事务提交后排队保持。runId仍是UUID，queuedAt仍为UTC Instant字符串；二者生成没有对外固定值。存储返回的task（含id/version/authority）仍是coordinator输入，不把未保存DTO直接交给执行器。

没有新增业务服务层、bean、依赖、API、表、迁移或权限。GenerationService168→169行，新DTO64行，生产总计增加65行；收益是声明式字段/状态契约与固定快照，不宣称代码更短。JSON context尚未整体强类型化，完整GenerationTask历史读取、其他领域DTO和V2对象/修订迁移仍开放。

## 实际检查

先在原Service上新增4个序列化刻画用例，与原生成/运行时事务/禁用启动合同共62/62。四种mapper为默认、真实JacksonConfig、NON_NULL和SORT_PROPERTIES_ALPHABETICALLY；逐字序列化比较18字段、显式null、Long.MAX_VALUE字符串ID、中文/转义、扩展快照、acceptedVersion、持久化后排队和同operation不重复排队。未通过“期望DTO序列化等于实际DTO”自证，而使用独立旧wire文本。

提取后原62项及2个快照防变/显式null直接合同64/64。最终58类744项：743通过，0失败/错误，1既有skip——PresalesPresentationCompilerTest.compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes缺少PRESALES_PPT_SKILL_ROOT。包括实际Spring/H2事务与权限合同、ProjectExecution、ProjectAuthorityFenceDatabase和编译WorkbenchArchitectureTest。计数仅采用本轮日志Running清单对应XML，排除旧target文件。

独立审阅指出baseline主类使用增量编译的证据限制后，另用JDK21 javac将归档before Service编译到隔离目录，再以该目录优先classpath实际运行同4个wire合同，4/4通过。程序先比较PresalesGenerationService的CodeSource与隔离目录的toRealPath，确保没有误跑当前类。before源码SHA为360074a8515166f0dcb99730b2555c45b736d70ebeaf14fb7f9fc33b0e2ea0da，编译class为0c502878eeb24deab8b1d4937bc17f0bbc8c2a0f74821b330e9ac4a886372307；当前class为83a46e51532cf7e6f31d55952c5c201e18f2cec53a7567472e53fecd34c39f06。复用targeted XML中java.class.path及已编译合同类；不计入744项JUnit总数。首轮直接比较/tmp与/private/tmp的URI导致路径检查失败，后改为双方真实路径比较；保留失败与成功日志/结果，未修改合同断言或工作树源码。

```sh
# cwd=/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw
# 各Maven命令的JAVA_HOME取自/usr/libexec/java_home -v 21
mvn -o -B -pl mateclaw-server -am test '-Dtest=PresalesQueuedTaskTest,PresalesQueuedTaskContractTest,PresalesGenerationControllerTest,PresalesRuntimeTransactionIntegrationTest,PresalesDisabledStartupReadContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -B -pl mateclaw-server -am test '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,ProjectExecution*Test,SemanticGraphBindingTest,GraphOptionalBindingContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -B -pl mateclaw-server -am -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

显式格式化仅4个本片文件，随后Spotless非修复检查exit0；原测试文件未改。初始dev rww8z5mg、源码后ommecm0s均SCAN_PASS/submission_ready=false，dev本身不执行应用工具链，上列命令是独立实跑。最终文档/归档后再次执行dev并在仓库外对照gate.snapshot与target_identity，避免报告回写改变被检树。

`queued-task-tests/archives.json`记录before/after源码、日志、XML、计数和源码hash；压缩及解压SHA逐项回读。测试JWT/生成密码脱敏。新增测试和类型边界由独立只读代理审阅，结论及工具能力见[AQ05_QUEUED_TASK_DTO_REVIEW.md](AQ05_QUEUED_TASK_DTO_REVIEW.md)。

## 未测项、风险和回退

正式46项AC继续NOT_RUN。精确暂存树commit门禁、远端required CI、维护人/QA/客户验收、真实模型及MySQL/Kingbase本轮均未运行；无前端改动，不重复UI/浏览器工具链。PPT环境skip继续保留。任意自定义ObjectMapper插件不在四种实测配置的保证范围。

后续完整DTO、V2单写迁移、坏/耗尽历史RUNNING恢复和工作台真实业务验收继续开放，不能把新任务类型化当作历史恢复已解决。开发回退恢复归档before GenerationService、删除新DTO和两份测试；保留前序任务接收/权限/回执修复，无数据库回退动作。
