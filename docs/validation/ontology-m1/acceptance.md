# M1 验收记录

日期：2026-09-07。代码审查基线：`9da3971b`。范围：SEM-01～03，以及用户追加的名称/描述上下两行布局。M2/M3 未实施。

## 结果

| 层次 | 证据与结果 |
|---|---|
| Core | 12 tests，0 failures/errors/skips；生产依赖为空 |
| Server 定向 | 24 tests，0 failures/errors/skips，包含 3 项 core 架构测试；真实授权/CAS/幂等/治理回滚/默认关闭/迁移 |
| UI | 60 files / 377 tests PASS，其中 semantic 5 files / 18 tests；最终补丁后复跑通过 |
| 静态与打包 | vue-tsc、所改共享文件与 feature ESLint、Java 21 reactor 打包 PASS |
| 两种构建 | enterprise 25.49s、classic 23.93s；编译产物均实际登录并读取真实本体列表 |
| 数据库 | 独立 H2 文件从 V190 升级到 V191；关停服务后 SQL 回读两版本及治理记录，再启动回读一致 |
| GUI 业务流程 | 创建“设备维护”，添加2类型、1 DECIMAL/V属性、1 MULTI关系，保存校验并发布v1；从v1修改类型名称/描述发布v2，查看准确前后差异 |
| 权限 | live HTTP拒绝viewer写和member发布；viewer浏览器无新建/编辑/发布/availability操作 |
| 视觉与交互 | 名称/描述上下等宽；未保存工作区切换取消后输入完整；浅/深色稳定帧可读；390px页面无横向溢出，表格在自身容器内滚动 |
| 审查 | 三项任务的spec/quality审查及整个分支的集成审查均APPROVE，无必须修复项 |

不是“整个仓库所有测试都通过”的声明：未运行无关 Server 全套，MySQL/Kingbase 真实环境未验证，未推送或远端部署。

## 可复现命令

在本工作树根目录：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-semantic-core -am -Dmaven.compiler.proc=full test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest='Semantic*Test' -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

在 `mateclaw-ui`：

```bash
./node_modules/.bin/vitest run
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/eslint src/features/semantic src/router/index.ts src/views/layout/MainLayout.vue src/composables/capabilities.ts src/i18n/locales/zh-CN.ts src/i18n/locales/en-US.ts src/stores/useWorkspaceStore.ts
VITE_UI_PROFILE=enterprise NODE_OPTIONS=--max-old-space-size=6144 ./node_modules/.bin/vite build --outDir /tmp/mateclaw-semantic-m1-enterprise --emptyOutDir
VITE_UI_PROFILE=classic NODE_OPTIONS=--max-old-space-size=6144 ./node_modules/.bin/vite build --outDir /tmp/mateclaw-semantic-m1-classic --emptyOutDir
```

`/tmp/mateclaw-semantic-m1-*` 是本次专用构建目录。不要对含个人文件的目录使用 emptyOutDir。Surefire 参数仅允许依赖模块无指定测试，目标测试计数已实际核对。

## 证据索引

- [Java 测试统计](evidence/java-test-summary.json)
- [22 项真实 API 检查](evidence/api-verification.json)
- [SQL 落盘回读](evidence/database-readback.txt)、[重启回读](evidence/restart-verification.json)
- [真实角色 HTTP 检查](evidence/role-verification.json)、[GUI 对应数据回读](evidence/ui-verification.json)
- [两行元数据](evidence/metadata-two-rows.png)、[v2 差异](evidence/ontology-v2-diff-light.png)
- [窄屏](evidence/ontology-mobile.png)、[深色只读](evidence/ontology-viewer-dark.png)、[classic 构建](evidence/ontology-classic.png)
- [视觉复核](evidence/visual-verdict.json)
- [Core 审查](evidence/task-1-review.md)、[Server 审查](evidence/task-2-review.md)、[UI 审查](evidence/task-3-review.md)、[最终集成审查](evidence/final-review.md)

证据中的名称、ID和内容均为此次隔离验收数据，不是生产导入数据。截图仅对应已观察状态；M1不包含推理、图谱、事实或绑定运行证明。

## 发现并关闭的问题

- 配置前缀按设计统一为 `mateclaw.semantic.enabled`，补正确启用/默认关闭/旧名无效测试。
- draftVersion跨放弃/新建保持单调，避免旧浏览器的ABA覆盖。
- 宿主Long→String规则对版本/计数作局部数值输出，资源ID仍为字符串；时间输出UTC并在UI本地化显示。
- 不确定发布与operation404保持原操作及编辑锁；确定4xx失败后释放并允许新操作。
- Vue模板多语句格式化问题改为命名搜索函数，并通过真实组件编译测试。
- 用户要求的两行布局已落地；窄屏原页面宽886px的问题通过限定页面宽度解决，复核route viewport与page均384px。

## 基线与环境说明

前端初始基线55 files / 359 tests通过；全量测试前后各有同样的非失败ECONNRESET/socket日志（不是零日志声明）。构建含现有Monaco等大chunk警告；缺失precision wrapper未被伪装为通过。核心/Server定向及已修改UI文件的类型/Lint均通过。

一次共享target下的并行Maven复核出现fixture class加载失败，改为串行独立运行后通过；未用跳过测试解决。H2关停时等待连接释放后才进行独立SQL回读，没有绕过文件锁。
