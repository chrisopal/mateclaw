# E3b 概念限制规则编辑验收

2026-09-10，本批范围 PASS。16 个控件实例、24 项分配检查全部通过；12 条主浏览器场景、2 条附加环境场景通过。不代表整个应用或全部 OWL 构造验收完成。

## 交付

草稿本体结构中选择命名概念 → **编辑概念规则**。支持存在、全称、最小/最大/精确基数的新增与逐条修改，包括改变限制类型。可选择已有关系/限定概念或输入完整 IRI，主体 IRI 只读。保存前显示 REMOVE/ADD 预览，复用现有原子写入、CAS、操作幂等机制。

仅完整标准投影确认的根文档、无注释、直接命名对象限制进入已有规则列表。嵌套、带注释、导入、数据限制等保留高级编辑入口；没有解析公理展示字符串来猜测结构。原有来源绑定不自动移植到新公理。

改动文件：`restrictionEditing.ts`（投影识别与写入生成）、`OntologyRestrictionForm.vue`（表单）、`OntologyModelWorkbench.vue`（入口与加载边界），及对应三个前端测试文件；`SemanticOntologyIntegrationTest.java` 增加持久化原子替换回归。无新依赖、无后端生产接口改动；直接复用现有保存流程，未添加另一套编辑状态或全文重建。

## 证据

- [控件台账](coverage.json)：24 PASS，0 FAIL/BLOCKED/NOT_RUN/UNCONFIRMED/N/A；执行覆盖率 100%。台账审计只检查记录完整性，实际证据如下。
- [主流程结果](result.json)：五类新增、五类替换；零值、无效输入、重复/无变化、重置；真实并发写入触发 CAS；响应丢失后冻结与同 operationId/同请求体恢复，仅增加一个版本；独立 GET、刷新和只读版本。
- [附加结果](extra-result.json)：暗色下手动 IRI 与选择提示，390px 下将最大基数改成最小基数并真实保存、刷新回读。
- 保真：主流程原有 19 条公理全部保留，含嵌套表达式、带注释规则、数据限制、属性链及 HasKey；ontology IRI、imports、policy 不变。H2 集成测试验证无效 ADD 会回滚 REMOVE、拒绝 viewer 写入、CAS 409、操作重放、API GET 与公理索引表一致。
- [静态/测试摘要](checks.txt)：本体前端 99 项测试；后端 H2 两个集成类 19 项；定向 ESLint、精度检查、vue-tsc 与 enterprise 构建通过。构建仍有既有大 chunk 警告。
- 截图已人工查看：[宽屏](wide.png)、[移动表单](mobile.png)、[移动预览与确认](mobile-preview.png)、[暗色](dark.png)。[视觉判定](visual-verdict.json) 94/100。移动端长表单可纵向滚动，确认按钮位于可视区内；宽屏无横向页面溢出。
- 原生用户标签在测试前后保持 `viewport=null`、`innerWidth=outerWidth=1920`。所有模拟尺寸仅用于临时标签，结束后关闭并回到原标签；主题恢复原值。

## 验收中发现与复测

1. 表单接入时修正 typed IRI 与规则识别参数不一致、切换操作符后的隐藏基数残留，并补全中文操作符和错误文案。
2. 手动新 IRI 曾让已有项选择器显示空白；修复为提示项，并保持输入原值（包括 `class:` / `objectProperty:` 合法 IRI scheme）。组件测试及暗色浏览器复测通过。
3. [首次脚本结果](initial-result.json) 的关闭按钮英文定位在中文界面超时；改用 Element Plus 稳定的 `.el-dialog__headerbtn`，等待弹窗进入动画结束后截图，复跑原操作通过。这是脚本定位问题。
4. [构建并行期间结果](build-overlap-result.json) 曾在刷新后检查到弹窗消失，截图见 [当时页面](failure.png)。未复现数据丢失，四次独立重新进入均显示 5 条可编辑规则；停止构建/编辑并等待稳定后完整复跑通过。该瞬态归为开发环境干扰的判断，不作为已确认产品根因。
5. 标准投影暂缺或刷新失败时，已打开表单保留输入并禁止新保存，防止使用保留的旧投影继续编辑；待新投影就绪后恢复。

## 复跑

工作树 `/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`，分支 `codex/enterprise-semantic-core`，基线 `e2ccc56a` 加本批提交。使用已认证 Chromium、Vite `127.0.0.1:5189`（enterprise）、后端 `18109`。先完成构建再运行浏览器，避免开发热更新打断验收。

调用现有 Playwright MCP `browser_run_code_unsafe`，`filename` 指向本目录绝对路径的 `run.js`；另执行 `extra.js`。二者均自动创建独立合成本体，并在 finally 关闭临时标签；API 仅用于测试数据准备、并发客户端和独立回读，写流程通过真实控件。保存返回 JSON 后可运行：

```sh
python3 /Users/guojiexie/.codex/skills/ui-regression-acceptance/scripts/check_coverage.py output/ui-acceptance/2026-09-10-e3-restrictions/coverage.json
pnpm --dir mateclaw-ui exec vitest run src/features/semantic/ontology/__tests__
VITE_UI_PROFILE=enterprise pnpm --dir mateclaw-ui build
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home MAVEN_OPTS='-Xmx3g' mvn -pl mateclaw-server -am -Dtest=SemanticOntologyIntegrationTest,SemanticOntologyProjectionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 边界

本批不含任意嵌套树编辑、带注释原子规则的表单修改、数据属性限制或 E4。本地持久化与浏览器使用 H2；MySQL/Kingbase 实库、其他浏览器、服务重启读回未在本批重测。响应丢失是网络模拟，服务端提交和恢复回读是真实执行。未推送或部署远端。
