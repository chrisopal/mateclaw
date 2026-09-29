# 应用整改与门禁验收

本文用例是后续实施必须执行的目标，当前默认 NOT_RUN。脚本自测见包内 validation，不能替代以下结果。

| ID | 场景 | 必须断言 |
|---|---|---|
| AC-01 | 普通聊天未带项目选项 | 不查询售前/投标表；原工具策略不变 |
| AC-02 | 售前执行改为通用接口 | 工具白名单、来源范围、授权和输出拒收保持 |
| AC-03 | 指定项目执行却无策略 | fail-closed，不退化普通聊天 |
| AC-04 | 同时注册多个 Revalidator | 唯一匹配；不选择错误策略、不静默随机注入 |
| AC-05 | 模块开关组合 | 禁用本体/售前/投标的各组合可启动；依赖业务明确unavailable |
| AC-06 | actor/employee执行前被停用 | 无模型或工具外发；历史保留必要诊断 |
| AC-07 | wiki_disabled/仅KB-A/项目KB-B | prompt、工具、缓存、历史和导出一致拒绝未授权内容 |
| AC-08 | 执行中撤权/来源撤回 | 活动任务拒收；来源支持状态与个人权限不混淆 |
| AC-09 | viewer/member/admin/owner/system admin | UI capability、API、后台任务的有效政策一致 |
| AC-10 | 投标项目owner批准 | 与改造前明确政策一致；没有借统一机制扩大角色 |
| AC-11 | Controller迁移 | 编译依赖无JDBC/DAO；DTO包含原所需字段 |
| AC-12 | 审核任务解绑后回读 | 保留审核者读取规则；错误不泄露来源 |
| AC-13 | Workspace快速切换 | 旧请求携带旧Workspace，响应不污染新页面 |
| AC-14 | multipart/自定义transform | FormData原样上传、边界正确、scope固定函数不覆盖调用方transform |
| AC-15 | Blob/ArrayBuffer/下载 | 内容字节一致；不套JSON envelope解包 |
| AC-16 | 409并发 | 返回明确冲突、保留草稿、不覆盖他人修改 |
| AC-17 | 同对象并发修改 | CAS/版本冲突；不丢更新 |
| AC-18 | 无关联系人变化 | 不使只依赖方案章节的运行失效 |
| AC-19 | 实际依赖版本变化 | 输出明确stale；不得自动采用 |
| AC-20 | operationId相同请求/异请求 | 相同回放一次结果；异请求拒绝 |
| AC-21 | 取消/重启/晚到结果 | 无二次写入与模型自批准；未知计费状态如实显示 |
| AC-22 | 固定Skill被更新 | 历史任务保持原包；新任务按新pin；不能伪造运行加载证据 |
| AC-23 | 迁移前后发布成果 | 原DOCX/PPTX/旧handoff原表示与SHA-256完全一致 |
| AC-24 | 旧消费者 | Bidding现有接收路径与摘要验证通过；Delivery端精确引用不漂移 |
| AC-25 | 旧迁移被编辑 | 脚本非零；新增迁移不改旧字节 |
| AC-26 | 新迁移方言 | h2/mysql/kingbase分别运行，不能只用H2冒充 |
| AC-27 | 售前单写切换 | 回填幂等、对账可读、切换原子；无暗中双权威 |
| AC-28 | 新写入后的回退 | 无反向迁移时明确阻断，不直接切回旧JSON |
| AC-29 | 基线/客户确认 | 事实核验、内部批准、客户确认不互相升级 |
| AC-30 | 功能两主题/布局 | enterprise/classic、只读、空态、错误态、窄屏正常；真浏览器证据 |
| AC-31 | 格式化源码 | 不修改OWL语料、客户原文、已批准文件/快照 |
| AC-32 | 新增跨域/Controller SQL/any/内联文案 | 已识别规则失败；清零路径不得回退 |
| AC-33 | 部分暂存 | gate读取index；完整检查阻断，不stash/不自动git add |
| AC-34 | 中文/空格路径与重命名 | 名称安全、无参数注入；搬运存量不能洗白 |
| AC-35 | 缺基线/缺工具/命令失败/超时 | BLOCKED/FAIL非零，不PASS |
| AC-36 | 零测试/全跳过/缺结果文件 | 明确失败；实际执行数可查 |
| AC-37 | 检查后代码/index变化 | 旧结果无效，重新执行 |
| AC-38 | 已有Hooks/Husky/多worktree | 安装器拒绝盲覆盖/误影响其他工作树 |
| AC-39 | 新增Guard规则/修改policy | base runner仍约束candidate；独立控制面审查 |
| AC-40 | 本地绕过pre-commit | PR仍执行required CI；失败不得合并 |
| AC-41 | workflow job skipped/cancelled | final required job必须失败，不把skip/neutral当PASS |
| AC-42 | base变更/PR更新 | 最新test merge tree与最新审批/检查要求生效 |
| AC-43 | 直接push/强推dev/机器人绕过 | 保护规则阻断；维护人审核可见的例外，无Agent绕过权限 |
| AC-44 | 改workflow/CODEOWNERS/测试配置削弱门禁 | 触发代码所有者审查，未批准不可合入 |
| AC-45 | 真实长文/模型质量 | 经授权项目人工复核，不用FakeProvider证明业务质量 |
| AC-46 | 日志与测试环境 | 不使用生产数据/密钥；公共CI产物无客户敏感原文 |

## P0签收

架构负责人签收依赖与事务边界；业务负责人签收角色政策与兼容行为；QA签收关键回归；仓库维护人签收required check、CODEOWNERS和绕过策略。至少一名独立审阅人，不由实现Agent自签。

不得因命令输出“BUILD SUCCESS”跳过测试数、跳过原因与产物检查；不得将全部用例在Markdown中手工改为PASS而没有运行链接/日志/环境说明。
