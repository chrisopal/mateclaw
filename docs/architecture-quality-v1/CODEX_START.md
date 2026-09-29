# 交给 Code Agent 的首轮启动指令

把本包解压到仓库外；先读README并执行安装器dry-run。以下可直接粘贴给Code Agent：

---

请优先执行MateClaw架构与代码优化，不扩大售前、投标或Delivery业务功能。

先核验当前repo、origin/dev、HEAD、未提交/未跟踪内容和根/子目录AGENTS。不要假设仍是文档中ca0ffbf8；不要回退覆盖新代码。

阅读 `docs/architecture-quality-v1/ARCHITECTURE_SPEC.md`、`RULES.md`、`CHECKS.md`、`CODEX_TASKS.md`、`ACCEPTANCE.md`，以及 `.agents/skills/mateclaw-engineering-gate/SKILL.md`。

本轮先完成AQ-00：安装并实际跑通本地工程门禁，整合formatter及锁文件；处理已有hook管理器时不得覆盖。基线问题单独报告。CI可信runner和分支保护需要维护人明确启用，不要自动修改GitHub管理设置。

随后执行AQ-01最小改造：为售前运行边界补行为刻画测试，再适配现有ProjectExecutionOptions/ProjectToolPolicy/Revalidator，移除通用Agent层对Presales具体策略的依赖。发现多个Revalidator注入冲突时用通用注册机制解决，不把更多业务类塞到底座。

每个职责切片完成后运行dev检查；任何授权commit前运行commit检查并核对tree。全程禁止跳过hook/测试、降低阈值、重写存量baseline、删断言、用--fix作为check、将BLOCKED或NOT_RUN写成通过。

保留工作区与来源权限、角色政策、人工批准、幂等、不可变修订、取消及晚到结果拒收、旧发布字节及handoff摘要。目录/格式整理与业务变更分开。没有明确授权，不提交、不推送、不改生产数据。

完成时给出任务ID、文件与职责清单、实际检查命令/退出码/报告路径、运行过的测试、未测环境、迁移及兼容风险。不要用门禁自测替代MateClaw应用验收。

---

P0全部通过后，才按任务映射恢复原售前升级和Delivery功能开发。
