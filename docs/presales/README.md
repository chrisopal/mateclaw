# 售前工作台

实现分支：`codex/presales-v1`；从语义核心当前 HEAD `64891a33` 创建的独立 worktree。

## 启用与回退

后端使用 Java 21 启动，增加 `--mateclaw.presales.enabled=true`。需求基线与发布还需要 `--mateclaw.semantic.enabled=true`。关闭售前 flag 隐藏 API，不删除售前数据库表和记录；关闭语义 flag 不能绕过需求确认与发布审批，项目管理仍可使用。
前端使用现有 `mateclaw-ui`，enterprise 模式 `pnpm dev --mode enterprise`，入口 `/presales`。没有独立站点、身份或数据库。

数据库 Flyway V211 按现有 h2/mysql/kingbase 方言路径迁移；实际验证范围见 validation.md。必须备份真实数据库并按既有升级流程操作，本次不修改生产运行环境。

## 权限与业务边界

隔离单位为 Workspace；viewer 只读、member 管理草稿，admin/owner 进行业务批准。业务负责人不提升权限。正式需求来自现有语义审核后的 Statement 修订。内部需求确认不代表客户确认。
执行绑定本工作区已配置并启用的售前数字员工，沿用员工模型与运行配置，不再提供独立模型选择。八个 presales Skills 随原有 classpath Skill 加载器安装。任务固定项目来源和版本，复用员工运行框架并限制越出项目范围的工具和自动记忆。员工整理的项目理解、需求候选与澄清进入工作台，人工补充信息并继续执行；不自动接受事实或审批发布。

## 成果

Markdown、DOCX 使用文档模板；数字员工生成的 PPTX 使用已绑定 ppt-master-plus 的原生转换器，保留可编辑文字与图形。历史方案保留原模板导出。发布候选先存储，成果审批绑定同一批摘要，发布不重新渲染。内部资料不自动放进客户附录。来源失效与版本变化需重新核对。

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

## 后台任务与 PPT 引擎

生成请求先持久化任务再返回；页面按任务刷新。单实例重启后未完成任务标记中断，人工可发起新运行，不自动重复模型调用。取消阻止结果被采用；不保证所有外部供应商立即停止计费。

PRESALES_PPT_SKILL_ROOT 指向已审核且与数字员工绑定技能目录一致的 ppt-master-plus 包；PRESALES_PPT_PYTHON 指向具备该包依赖的 Python 3.10+ 解释器。启动脚本映射为 mateclaw.presales.ppt-skill-root / ppt-python。目录及解释器由部署配置指定，不接受模型参数。未配置则制稿任务明确失败。

本轮集成是平台受控制稿适配器：员工编写独立 SVG 页面，使用技能包的 lockless flat 编译接口、质量检查及原生 DrawingML 转换。不是完整交互式 Strategist 模板选择和设计确认工作流；人通过工作台预览、评审、审批控制成果。保留技能版本、包指纹、输入与输出摘要。当前只允许文字和矢量基本图形，不加载网络图片或外部文件。历史未附带技能产物的方案保留旧模板导出，技能生成失败不回退旧模板。
