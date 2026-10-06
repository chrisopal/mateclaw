# AC22 历史任务原包工程证据

任务：AQ-01 / AQ-06 / AC-22。原条款为“历史任务保持原包；新任务按新 pin；不能伪造运行加载证据”。实现起点 HEAD `ad4cb39d998c472970a07565e8f122357ed6e55d`，dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，PR base `68d8fc536b6d3a4b9c9064e7322060bd78869faf`。本记录描述候选工作树；最终暂存树、提交门禁、提交和推送证据另按确切身份核验，不能从本文件推断提交成功。

## 实现及兼容边界

- 复用 PresalesProjectRepository，新增 V222 三方言任务包表。包记录绑定 Workspace、项目、task、run、actor、employee 和摘要，仅有插入/读取接口；项目、任务、回执、修订与原包同一事务提交。插入失败整体回滚，外层事务提交后才排队，回滚不排队；延迟排队保留可信 SecurityContext。
- 公共 SAVE_AI_TASK 不再允许伪造 RUNNING、服务端执行声明或覆盖已有执行任务。普通手工草稿和不可信 contextSnapshot 保持兼容；服务器使用包内 typed 创建入口，取消仍走专用入口。
- 内置 Skill 一次捕获原始文件，prompt 从同一捕获内容生成；规范摘要覆盖所有文件、instructions 和可选 S6 包。重放不重新捕获。运行时读权威原包并比对完整文件，继续校验当前模型配置、主体、员工、来源、活动 attempt、取消与结果接纳条件。
- S6 保存已审计 native/quick/no-notes 执行闭包，包含 XML/JSON/许可文件；在私有临时目录执行固定脚本。保留原始引擎说明、文件摘要和 Python/外部依赖身份；当前绑定禁用、依赖变化或可选检查器降级明确拒绝，不换用当前引擎。
- 只有实际 Skill 工具读取产生 project_skill_loaded 事件。模型声明 loadedSkills 不构成加载证明；保留原摘要但篡改 options 文件的请求在工具执行前被拒绝。

无新依赖、无旧迁移修改、无通用运行时业务钩子、无额外存储服务。已有历史任务若从未保存原包，明确 TASK_SKILL_PACKAGE_UNAVAILABLE；无法从当前安装包推断历史字节，因此不做猜测回填。新建任务保存新包。PPT_RUNTIME_CHANGED 及缺包诊断均有中英文本地化。

## 已执行验证

| 范围 | 实际结果 | 断言及局限 |
|---|---|---|
| 原实现 HTTP/H2 RED | 17 项，15 失败、2 正例通过 | 最初测试夹具把版本序列化为字符串，修正为数值后重新获得真实 RED；保留失败记录，不混同夹具错误 |
| 本批售前全回归 | 833/833，0 失败/错误/跳过 | 在后续两项事务测试与引用文件专项补强前执行；不宣称是最后工作树全量检查 |
| 原包及实际工具事件专项 | 114/114，0 失败/错误/跳过 | H2 原包 A 入库后 B 更新；仅引用文件变化时旧工具仍读 A；新任务读 B；真实 ToolExecutionExecutor 发出加载事件；模型伪造声明无事件 |
| 最后事务/编译专项 | 68/68，0 失败/错误/跳过 | TaskCreationAuthority 21、RuntimeTransaction 46、PresentationCompiler 1；含外层提交/回滚和 SecurityContext，以及完整 TaskPackage 经 V222 H2 持久化后运行原版 PPT 编译器 |
| 真实 MySQL 8 | 46/46，0 失败/错误/跳过 | 独立一次性空库完整迁移到 V222；实际原包 48 行回读；原库未改，测试容器/卷已清理。不是 Kingbase 或生产升级验收 |
| UI 定向 | 124/124 | 诊断消费与已有工作台状态回归；不是浏览器矩阵 |
| UI 检查 | ESLint / vue-tsc / Prettier check exit 0 | 针对变更及项目类型检查；不自动修改检查输入 |
| Java 格式 | 显式 Spotless 格式化已完成 | 早期模块前缀 pattern 没匹配文件，不作证明；后用类名 regex 实际匹配 27 文件，最后 3 文件再次格式化。提交门禁仍需 readonly 检查 |
| dev | `ukyalmtw` SCAN_PASS，exit 0 | 词法增量与门禁自测，submission_ready=false，不能代替 commit 门禁 |

执行命令：JDK 21 下 `mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='<各日志记录的测试选择器>' -Dsurefire.failIfNoSpecifiedTests=false test`；真实编译设置已安装的 `PRESALES_PPT_SKILL_ROOT` 与 `PRESALES_PPT_PYTHON`。dev 为 `python3 -B scripts/quality/verify.py --mode dev --base origin/dev`。没有新增 skip、删除断言或降低门禁。

## 可回读证据

本机归档根：`~/.codex/artifacts/mateclaw/aq-cumulative-20261006-fcdteqlz/`。原始日志脱敏后归档，并逐成员回读验证摘要；manifest 同时保留原始与归档摘要。归档不会自动随 Git 分发。

| 包 | 内容 | SHA-256 |
|---|---|---|
| ac22-final-focused.tar.gz | RED、833/114/68 测试日志、最后三套 JUnit XML、UI/格式日志及 33 个候选源码摘要 | cf11e7e4c8eca9586039393699c2d1694deff0c61566ae5bec51080381984fb3 |
| ac22-mysql-runtime.tar.gz | MySQL 执行日志、XML、迁移回读、镜像身份、源摘要及清理结果 | 180129577c29daa36242369a78dc657ff657d56b23d80e3b20efe6ea561ad6de |
| ac22-final-dev.tar.gz | dev 报告、自测及架构扫描 | 8503a247f5a897abfb47c0cb13dcf68df6b93af484eee965b336db91fdb46d53 |

## 审核、风险和回退

独立技术复核 `/root/ac22_final_review` 已对当前生产差异、S6闭包、三方言迁移和测试返回 COMMENT，未发现本批阻断缺陷。该复核回读 68/114/833 及 MySQL 46 项结果；Java LSP 实际返回 tsc skipped、AST 工具不可用，不计为静态检查通过，最新完整编译与工程检查由提交门禁承担。此前针对任务权威的独立复核提出“引用文件单独更新”和“真实工具执行器事件”两项补证，已由上述 114 项专项覆盖。技术复核不是维护人或业务签收；正式 AC 台账不自动改为 PASS。

当前包上限：内置 Skill 256 文件/2 MB，PPT 闭包 512 文件/32 MB（单文件 8 MB）。冻结包增加每任务存储与捕获成本；没有据此宣称 AQ10 性能达标。Python/外部依赖仍属于受校验的运行环境，不是随任务复制的操作系统镜像。不同部署环境须具备匹配依赖才能执行历史包。

回退保留 V222 及原包，不删除历史。旧应用不能理解新任务包时，应先停止相关执行并保留历史只读；不能把旧任务换为当前 Skill 继续跑。Kingbase、真实客户历史 Office/handoff、完整浏览器矩阵、真实模型质量、正式签收和远端强制保护仍未验证或未完成。
