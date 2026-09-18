# 售前工作台

实现分支：`codex/presales-v1`；从语义核心当前 HEAD `64891a33` 创建的独立 worktree。

## 启用与回退

后端使用 Java 21 启动，增加 `--mateclaw.presales.enabled=true`。需求基线与发布还需要 `--mateclaw.semantic.enabled=true`。关闭售前 flag 隐藏 API，不删除售前数据库表和记录；关闭语义 flag 不能绕过 G1/G2，项目管理仍可使用。
前端使用现有 `mateclaw-ui`，enterprise 模式 `pnpm dev --mode enterprise`，入口 `/presales`。没有独立站点、身份或数据库。

数据库 Flyway V211 按现有 h2/mysql/kingbase 方言路径迁移；实际验证范围见 validation.md。必须备份真实数据库并按既有升级流程操作，本次不修改生产运行环境。

## 权限与业务边界

隔离单位为 Workspace；viewer 只读、member 管理草稿，admin/owner 进行业务批准。业务负责人不提升权限。正式需求来自现有语义审核后的 Statement 修订。G1 不代表客户确认。
执行绑定本工作区已配置并启用的售前数字员工，沿用员工模型与运行配置，不再提供独立模型选择。八个 presales Skills 随原有 classpath Skill 加载器安装。任务固定项目来源和版本，复用员工运行框架并限制越出项目范围的工具和自动记忆。员工整理的项目理解、需求候选与澄清进入工作台，人工补充信息并继续执行；不自动接受事实或审批发布。

## 成果

Markdown、DOCX、PPTX 由固定模板产生，Office 文本可编辑。发布候选先渲染存储，G2 绑定同一批摘要，发布不重新渲染。内部资料不自动放进客户附录。来源失效与版本变化需重新核对。

## 验证

后端命令需要显式设置 `JAVA_HOME=$(/usr/libexec/java_home -v 21)`（本机 Maven 默认选择 Java25，不能用其 Lombok 失败判断业务代码）。

```
mvn -pl mateclaw-server -am -Dtest='Presales*' -Dsurefire.failIfNoSpecifiedTests=false test
cd mateclaw-ui
pnpm test -- src/features/presales
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/presales
pnpm build --mode enterprise
```

不自动对外发送，不创建投标、合同或项目交付对象，不预填生产演示数据。完整 spec 的验收状态逐项保存在 validation.md，未验证项不宣称通过。
