# 累计工作树完整应用工具链预验收

本轮未修改生产代码、测试、依赖或门禁，完成累计架构整改的应用工具链检查与证据回读。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，HEAD tree `3a16cabe1768953a4887c2613a1238b222815f55`。工作目录为独立 architecture-quality worktree；主 checkout 保持原状。

## 实际结果

| 检查 | 实际结果与范围 |
| --- | --- |
| Java 21 根 reactor clean verify | 902 份 XML，6486 项中执行 6416 项，0 失败、0 错误，70 跳过 |
| 正式 base Spotless | 通过 |
| 前端格式、lint | base 到工作树的适用变更输入通过；不是全库存 lint |
| vue-tsc、ID 精度 | 通过 |
| 完整 Vitest | 1093 通过，0 失败、0 pending |
| Node TAP | 5 通过，0 失败、0 skip、0 cancelled |
| enterprise/classic 前端构建 | 均通过 |
| cost-analyzer | 16 测试通过；Python/JS 语法检查通过 |

70 个 Java 跳过项：66 个缺 SEMANTIC_MYSQL_TEST_URL；各 1 个为 macOS 条件排除、缺 PRESALES_PPT_SKILL_ROOT、缺 semantic.val.export、未启用 W3C OWL harness。它们保持未测，未更改既有 skip 或断言。测试使用隔离临时 skill 目录。902 XML 与 Maven 日志核对：7 个额外嵌套类日志归入父 XML；两个 PlanGeneration 类以 DisplayName 输出，分别6、7项，与源码注解及 XML 对应。

运行期间合格文本输入 snapshot 前后均为 `6754c1c930b3c9eeb219a06b5f0375001aa62c23c1cc417a5bc1ced80b4afa29`。该身份不覆盖全部二进制或生成物。最终 dev 在证据文档落盘后另行运行，其实际任务 ID、输入身份与日志在 final-report.json.gz / final-manifest.json.gz；不能用最终文档身份冒充应用测试时身份。文档落盘前后逐文件摘要核对，只允许本轮证据文档变化。

## 检查命令与证据

所有参数、退出码、耗时、日志摘要在 [full-toolchains-results.json](full-toolchains-results.json)；实际临时编排器、运行结果、完整日志、Vitest JSON、TAP、902 XML 及逐条摘要保存在 [full-toolchains-tests](full-toolchains-tests/)。XML 已在下一次 Maven 前归档；归档对 JWT、日志密码脱敏并保留原始与脱敏摘要。原始结果中的 control=true 仅为影响分类，不代表该手工编排器运行了全部控制面检查。

```sh
mvn -B -Dquality.base=ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93 spotless:check
mvn -B -Dmaven.compiler.proc=full -Dmateclaw.skill.workspace.root=<隔离临时目录> clean verify
pnpm exec vue-tsc --noEmit
bash scripts/check-snowflake-precision.sh
pnpm exec vitest run --reporter=json --outputFile=<本轮报告>/vitest.json
node --experimental-strip-types --test --test-reporter=tap test/agentBindingSearch.test.ts test/workspace-request-transport.test.mjs
pnpm build --mode enterprise
pnpm build --mode classic
python3.13 -B -m unittest discover -s tests -v
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

临时编排复用当前 verify.py 的环境清洗/JDK21/实际应用检查命令，但未调用 ensure_exact_worktree，不是精确暂存树的 commit/CI 门禁，submission_ready=false。Prettier 3.6.2 的 package/lock/config 由独立审阅另行核验；当前 base 到工作树无 desktop/webchat 变更，不宣称其已测试。独立技术 COMMENT 与限制见 independent-review.txt.gz；不替代维护人批准。

## 剩余工作与回退

远端只读回读：PR #5 OPEN/DRAFT，远端仍为上述 HEAD、statusCheckRollup 为空；目标 base 分支 codex/aq03-task-query 的 branch API 返回 protected=false / required_status_checks enforcement_level=off。该结果仅针对本次读取的目标分支，不推导其他分支或所有 ruleset。没有提交、推送、合并、部署或管理配置变更。

46 正式 AC 仍 NOT_RUN。V2 hash writer/replay 未集成，旧回执策略待明确；稳定 DTO、其余应用用例、对象/修订/依赖/attempt 数据迁移仍开放。真实历史升级恢复、Kingbase、Office 成稿、完整浏览器/模型/业务验收、维护人批准与远端强制 CI 未完成。前批定向 MySQL 证据不计入本轮跳过测试。

本轮仅新增/修正文档和压缩证据，无产品逻辑简化或数据迁移。回退只移除本轮 full-toolchains 文档、证据及 README 入口，不撤销前批安全与架构修复。
