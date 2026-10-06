# AC30：未绑定员工项目的档案保存

本批修复一个实际浏览器发现的缺陷：项目允许暂不绑定员工，但档案编辑提交 `agentId: ""` 时被当作重新绑定，返回 409/EMPLOYEE_UNAVAILABLE。旧实现的实际请求、响应、截图及版本 1 回读均保留；HTTP 400/403 故障注入的首次恢复因此失败，没有记为通过。

## 最小修复与边界

基线 HEAD `e70924e3bd0a4c8e4527a9f805313b19cc34f60c`，base `origin/dev`=`ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。仅调整 `PresalesService` 的 UPDATE_PROJECT 分支：请求为空员工、存量为 missing/null/文本空串时保持未绑定状态。存量对象、数组、数字和空白不会被误当作未设置。已有员工清空及其他员工输入仍走原绑定校验。

权限、来源与窄修复政策、原始请求 hash、事务、版本 CAS、回执和数据结构保持；没有新增依赖、接口或迁移。两份集成测试增加保护断言。方案见 [实施计划](AC30_UNASSIGNED_PROJECT_SAVE_PLAN.md)。仅格式化新增代码，三个既有文件无独立格式漂移。

## 实际验证

| 验证 | 结果 |
|---|---|
| 旧实现 RED | 63 项，4 个预期失败；全部是无员工保存意外返回 409 |
| 修复后定向 HTTP/H2 与真实 runtime | 63/63 通过；PATCH/commands、回放/异请求/CAS、viewer/跨 Workspace/归档、来源撤权三表不变、员工有效/无效/跨 Workspace/停用对照 |
| 售前、架构及 authority 回归并打包 | 861 项，0 失败/错误，1 项既有 PPT 条件跳过；BUILD SUCCESS |
| 格式 | 三文件 Spotless apply 成功；首次误用 glob 参数失败留档，改用工具支持的正则后成功，没有改 formatter 配置 |
| dev | `mateclaw-quality-eln85mad` SCAN_PASS，`submission_ready=false`；不替代提交门禁 |
| 浏览器 | 普通表单重新登录实际隔离应用；400/403 各截获一次且草稿保留、重试可用；撤除模拟后真实 PATCH 200，版本 1→2，GET 200，刷新/重开值一致，仍未绑定员工 |
| 独立技术复核 | COMMENT，无新增可操作问题；Java LSP/AST 不可用已明示，实际 Maven 编译与回归通过；不替代维护人签收 |

实际命令（JDK 21）：

```sh
mvn -pl mateclaw-server -am '-Dtest=PresalesIntegrationTest,PresalesRuntimeTransactionIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl mateclaw-server -am '-Dtest=Presales*Test,WorkbenchArchitectureTest,ProjectAuthorityFenceTest' -Dsurefire.failIfNoSpecifiedTests=false package
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
python3 run-case.py save-http-errors-recovery mateclaw-ac30-transcript
python3 run-case.py unassigned-readback-stable mateclaw-ac30-transcript
```

浏览器候选 tree `4862bf6660e57a4b2515830c58134a17ab290a1c`；实际运行 JAR SHA-256 `0c1d90e324591260d1c667ac09d06752f0dd56233e83946f363abe6e46ad2b5b`，仅回环地址和合成 H2。53 个售前 UI 文件与工作树逐字节一致；后续证据文档不在运行包内。截图路径按持久浏览器的实际工作目录回收；原 RED 文件摘要已复验。首次回读截图处于过渡动画，另补动画结束后的编辑器和页面截图，不拿过渡图作视觉通过依据。

## 可回读证据与未测项

[浏览器及审阅归档](unassigned-save/browser-and-review.tar.gz) 含 22 个成员，逐成员摘要复验；归档 SHA-256 `1fe6879986f27d7b2d98664e08cffa799d88a92770047b0efa7b445681b40d4c`。[清单](unassigned-save/manifest.json)、[测试与日志摘要](unassigned-save/test-summary.json)、[源码身份](unassigned-save/source-identity.json)、[独立复核](unassigned-save/independent-review.json)。完整原始 Maven 日志保存在本机 `ac30-unassigned-save/evidence/verification-*.log`，路径与摘要见测试记录；不包含运行环境凭据或原始后端日志。

本修复尚待精确暂存树门禁、提交、推送及新合并树 CI，最终状态以 PR #5 对应 HEAD 为准。没有真实模型调用或客户业务签收；AC30 的其余界面覆盖、Kingbase、V2 迁移、性能和远端强制策略仍开放。回退仅撤回此分支行为，会恢复旧保存缺陷，无 schema 回退；不得放宽员工资格或来源权限作为替代。
