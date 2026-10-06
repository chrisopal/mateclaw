# AC30 执行记录链路修复：工程验收

## 问题、边界与实现

成功售前任务的“查看执行过程”曾打开空聊天。初始基线为 `5a02519f2d0cb8d208d0befaf973325f688259b8`，初始 dev `3yfmuv1n` SCAN_PASS。先以运行时回归重现未保存消息，再补真实流内容保存；不从最终任务结果伪造历史。

- `PresalesEmployeeRuntime` 复用 `AgentStreamAccumulator` 保存输入目标、真实输出分段、工具元数据及用量。原结果解析、任务接纳 CAS 和人工审批权威保持；流结束后复验活动状态，取消后的晚到输出仅留作 interrupted 记录。
- 公共 conversation 包定义 `ConversationTranscriptPolicy`，售前适配器复用项目/来源读取权限，并核对 Workspace、任务、run、员工和快照 actor。缺失、多策略或异常拒绝读取。通用层不导入售前域。
- `project_execution` 记录不出现在普通聊天列表；通用发送、删除、改名、停止、插队和 Agent chat/stream/execute 均不得修改。消息、轨迹、状态每次读取重新授权，系统管理员无来源授权兜底。
- 前端从服务端 status 读取只读属性。未列入普通会话列表的执行深链仍检查服务端，刷新与异步切换不解除只读。
- 运行时使用空消息 sink，不注册共享聊天广播；避免把受限记录通过其他共享 SSE 订阅入口外发。

无新依赖、数据库迁移或审批升级。保留既有历史项目读取政策，未擅自要求历史任务员工必须等于项目当前员工。只保存 taskGoal，完整内部提示词不另存入消息。

## 证据和实际检查

浏览器候选树 `e6f9fe69b2a5af6bf59177df1951973c8b018e4f`；JAR SHA-256 `fc3217668c57b99b191b456120a968607c0bbc71b9f6f9fee613735e5d67a377`。归档 `transcript-tests/source-identity.json` 保存全部变更文件摘要，提交时核对源码不变；后续文档和独立格式提交改变 Git tree，不将其冒充浏览器启动时 tree。

| 检查 | 实际结果 |
|---|---|
| 三次故障回归 | 初始消息未保存、Agent 直接写入口、取消后的晚到状态均先出现预期 FAIL，再修复；原日志摘要保留 |
| Java 相关包 + ArchUnit | 1024 项，1023 执行通过，1 项既有条件跳过，0 失败；`package` BUILD SUCCESS |
| UI 只读与切换专项 | 2 文件、20 项通过；类型检查和变更文件 lint 退出 0 |
| 最终实现 dev | `g1wt8zsr` SCAN_PASS；不是 submission_ready |
| 实际生成 → 执行链接 → 刷新 | 真实应用 HTTP 与浏览器：实际输出可见，刷新仍可见，输入框持续禁用 |
| 读取/写入 HTTP | admin/viewer 各 3 个读取入口 200；7 个通用修改入口 403，消息数量未改变 |
| 来源撤销 | 实际 PUT wikiDisabled=true 后 GET 确认；admin/viewer 共 6 个读取入口 403，无候选泄漏；恢复配置后读取 200 |
| 运行取消与晚到 | 真实 RUNNING 任务在本地供应商 held 时取消；释放后 GET 仍 CANCELLED、无 result、contextCards 保持 3；助手原始输出 272 字符，status=interrupted |
| 取消界面回读 | 按确切 run 定位卡片，显示已停止接收和未知计费提示 |

Java 命令：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full '-Dtest=Presales*Test,Conversation*Test,ChatController*Test,AgentControllerOriginTest,WorkbenchArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false package`。

取消最后一轮原脚本因两个取消卡片的 alert 同时匹配而失败；保留 `transcript-tests/evidence/runtime-cancel-final.log.gz`。没有再次取消或改任务数据库；通过已接纳 taskId 的实际 API 回读，以及确切 run 卡片的浏览器回读补齐证明。早期来源配置用错 wikiEnabled 的失败也保留在本机原始记录，最终使用 wikiDisabled 并回读验证，不将早期失败记为通过。

独立技术复核：原会话 `/root/ac07_cached_source_boundary` 对公共读写边界、Agent 写入口和最终流结束复验进行了分段审阅，未报告本批新增阻断。此为 Agent 技术复核，不是维护人批准或正式业务签收。测试断言增加，未改门禁/基线/旧迁移或删除断言。

## 提交、局限及回退

项目要求独立格式提交。11 个旧文件先用现有固定 Spotless/Prettier 格式化，再单独提交行为改动；较大格式差异与功能修改在 Git 历史分开。格式动作不在 check 命令内执行。提交、正常 hook、受检 push 和新 CI 结果记录在 PR #5 的最终交付栏；不能使用旧 HEAD 的绿色 CI 代替本次检查。

仅隔离 H2、本机后端及真实浏览器；模型为明确标注的本地确定性 OpenAI 兼容供应商，属于 SIMULATED_MODEL，不是 AC45 真实模型质量验收。没有生产数据、管理员配置、合并或部署操作。

旧的空执行记录无法从未保存的流中恢复；旧候选继续可读，不承诺追溯补全。流完成与任务最终接纳仍有各自状态含义，记录 completed 不等于业务批准。保留受保护 kind 后，回退时必须保留读取和写入围栏，不能直接恢复将 actorId 当普通用户名的访问逻辑。

AC30 全工作台双主题、窄屏、空态、错误态仍未全部覆盖；本修复不是 AC30 或整套架构验收完成。工程分类保持 33 项证据齐备、13 项缺口，正式签收状态未更改。

## 独立浏览器补充验收

独立 verifier 在同一候选的桌面 1440×900 和窄屏 390×844 验证取消提示、只读刷新及跨页返回，未发现本切片产品缺陷。通过实际 UI 编辑并保存合成候选，刷新后仍为 `UNTRUSTED_DRAFT / AI_SUGGESTION`，`needsHumanReview=true`；项目 V12→V13，contextCards 3→4，基线/评审/发布仍为零。此保存不是正式人工签收。

验收代理最初误选备份 JAR，已通过端口 61806 的实际监听 PID 45303 定位 `server.jar` 并核对上述 SHA，修正说明和原/新摘要均保留在其身份记录。独立报告与 23 个摘要匹配的完整证据见 `transcript-tests/runtime-ui-acceptance.tar.gz`，并保留可直接阅读的报告及 JSON。S1 不含演示制品，演示预览未跑；完整 AC30 仍未完成。
