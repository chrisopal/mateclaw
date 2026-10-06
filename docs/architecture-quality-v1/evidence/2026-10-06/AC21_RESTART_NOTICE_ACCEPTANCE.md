# AC21 重启计费提示与坏记录隔离

Task AQ-01/AQ-06，AC21 原条款为“取消/重启/晚到结果：无二次写入与模型自批准；未知计费状态如实显示”。基线 HEAD `7e2f610396c283b1dded8e418ffda1a645714e05`，tree `37cfdd057de7c5a3b5d9a5bdc2066044a788dd5e`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。基线提交/推送已经通过正常门禁，修改前工作树干净；初始 dev `5v6lyxch` 为 SCAN_PASS。

## 最终行为与边界

取消、重启中断、晚到结果丢弃和终态保存失败均明确说明模型计费未知，并提示人工重新执行。复用现有 employee_issues 双语目录及宿主语言切换；取消通知按领域 CANCELLED 状态显示，不依赖历史任务是否有 error。SUCCEEDED/DRAFT 不显示取消提示。没有自动重试、费用推断、新任务字段或运行 API 变更。

新增 Java 测试刻画现有隔离行为：语法坏 JSON、数组根、null 根与健康项目并存，坏行先被检查，健康任务仍终止；第二次恢复健康原字节不变，坏行正文/版本不变，旧结果移除，其他集合/任务扩展及另一工作区保持；未调用模型、上下文或服务命令。恢复算法、权限、来源、批准、数据库及发布成果未改变。

ADR-AQ-046 明确没有修复权威时保持拒写。非法/不一致历史版本已有拒写与逐项目隔离，不能把自动修复坏数据添加为本项要求，不能取最大版本、重置或强写。

## 回归证据

| 阶段 | 实际结果 | 说明 |
|---|---|---|
| 初始重启提示 RED | 1失败，117项被显式过滤 | 页面仅显示 INTERRUPTED_BY_RESTART 原码；过滤项不算已测 |
| 第一片 GREEN | 118/118 | 重启提示及既有错误展示保持 |
| 独立复核后完整 RED | 3失败、119通过 | 取消无error、晚到丢弃及终态保存失败缺计费说明 |
| 最终完整工作台 GREEN | 122/122，无失败/跳过 | 实际挂载英→中→英切换、无自动command/generate、非取消对照 |
| H2恢复隔离 | 44/44，无失败/错误/跳过 | 三种坏正文新增刻画；保留版本容量、晚到、CAS、撤权及跨工作区断言 |
| 格式/静态检查 | PASS | 三前端文件Prettier只读检查、ESLint、vue-tsc、ID精度均exit0 |
| Java显式格式 | PASS | Spotless完成，JDK21 JavacTask前后AST一致，实际编译通过；Java LSP未运行 |
| 修改后dev | SCAN_PASS，066qwoe8 | 只证明增量扫描与门禁自测；submission_ready=false |

最初从仓库根调用 pnpm --dir 触发固定包管理器版本保护，随后在 UI 目录使用项目版本执行；该工具错误不算业务 RED，未修改版本、锁文件或绕过保护。

实际命令：

```sh
# mateclaw-ui 工作目录
pnpm exec vitest run src/features/presales/__tests__/presalesWorkbench.test.ts
pnpm exec prettier --check --config ../.quality/prettier.json src/features/presales/shared/messages.ts src/features/presales/components/PresalesOverview.vue src/features/presales/__tests__/presalesWorkbench.test.ts
pnpm exec eslint --max-warnings=0 src/features/presales/shared/messages.ts src/features/presales/components/PresalesOverview.vue src/features/presales/__tests__/presalesWorkbench.test.ts
pnpm exec vue-tsc --noEmit
bash ../scripts/check-snowflake-precision.sh
# 仓库根，JAVA_HOME为Temurin21
mvn -B -pl mateclaw-server -am '-Dtest=PresalesTerminalReceptionTest' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -Dquality.base=HEAD spotless:apply
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

日志：`/tmp/ac21-restart-ui-{red,green}.log`、`/tmp/ac21-restart-history.log`、`/tmp/ac21-restart-java-ast.log`、`/tmp/ac21-terminal-notices-{red,green,format,format-check,lint,typecheck,precision,dev}.log`。最终精确暂存树、正常提交钩子、推送及CI证据记录在交付归档/PR，按实际结果核验，不由本文预先宣称通过。

## AC21原条款独立核对

独立只读 ac21_restart_closure_review 两轮复核：上述显示缺口已修正，无新增可操作问题。评审同时回读以下已有证据，未单凭本片UI测试关闭原条款：

- 真实三个JVM、不同PID、同一H2文件库：RUNNING/version2→FAILED/version3→原字节/version3；回执/修订保持2，启动矩阵9/9。
- H2 CAS冲突、晚到结果和取消占位拒写，保留并发业务修改及另一工作区。
- TaskAcceptance17/17覆盖取消后的成功/失败均拒收；ModelAdapter9/9覆盖递归权限声明拒绝和UNTRUSTED_DRAFT。

因此AC21归类为 ENGINEERING_EVIDENCE_COMPLETE_PENDING_SIGNOFF。正式签收仍NOT_RUN；独立技术审阅不代替维护人/QA签收、提交门禁或最新合并树CI。

## 风险、未测与回退

未核对真实供应商账单，不把计费未知推断为未收费；未执行完整浏览器布局验收，AC30仍独立开放。任意阻塞hook/锁/dispose/最终SSE不提供无限等待SLO保证；不修改生产数据或自动修复坏历史。

回退为撤回本批组件及双语目录修改，会恢复缺失计费提示；没有数据迁移或数据回退。新增恢复测试应随既有拒写与隔离规则保留。来源、角色、API、ID、事务及批准语义保持，未更改门禁/规则/依赖。
