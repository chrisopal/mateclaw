# 本体管理 M1

M1 已实现并验证：本体列表、类型/属性/关系配置、草稿保存与并发控制、校验、不可变版本发布、历史差异、从历史版本建立草稿，以及版本的新绑定可用性设置。名称与描述按用户要求上下两行排列。

本功能位于 `codex/enterprise-semantic-core` 分支；本次工作树是 `/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`。原企业 UI 工作区保留原状，未推送或部署到远端。

## 运行

使用当前工程已有 JDK 21、Maven、Node 和 pnpm。新核心没有生产依赖，前端沿用既有组件库。

在仓库根目录构建后端：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -DskipTests package
java -jar mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar --server.address=127.0.0.1 --mateclaw.semantic.enabled=true
```

`java_home` 是本机 macOS 写法；其他环境将 JAVA_HOME 指向 JDK 21。`-DskipTests` 只用于上述打包，验收测试记录单独列出。

在另一个终端运行企业 UI：

```bash
cd mateclaw-ui
pnpm exec vite --mode enterprise --host 127.0.0.1 --port 5176 --strictPort
```

打开 [本体管理](http://127.0.0.1:5176/semantic/ontologies)，沿用 MateClaw 登录和工作区。模块默认关闭，必须使用准确的 `mateclaw.semantic.enabled=true`；旧的 `semantic.enabled` 不生效。关闭时状态接口仍可供已登录用户读取，业务 API 和菜单不可用。

本次验收运行实例使用独立 H2 文件 `.superpowers/sdd/2026-09-07-ontology-m1/runtime/m1`，没有连接原工作区数据库。已有运行实例可直接打开，不重复占用端口。上述普通启动命令采用项目默认数据路径，可通过 `spring.datasource.url` 指定自己的数据库。

## 使用流程

1. 新建本体，录入名称和说明，进入草稿。
2. 配置类型、属性和关系；属性可设值类型、单值/多值及数值固定单位。
3. 保存并校验，按错误位置修正。修改后原校验结果失效。
4. 查看变更摘要，填写发布说明，发布不可变版本。
5. 在版本历史选择版本查看定义、比较前后内容，或复制为新草稿。

viewer 可以查看；member 可以维护草稿；admin/owner 可以发布。服务端再次检查当前用户、工作区与资源归属。编辑器保护未保存输入，发布结果未知时锁定编辑并恢复同一个操作；确定失败后可重新处理。

本期没有知识库图绑定、事实审核、图谱查询或 Agent 工具，它们属于 M2/M3。版本“新绑定可用性”是已实现的版本治理属性，不代表图绑定已交付。

## 验证与限制

[验收记录](docs/validation/ontology-m1/acceptance.md)包含测试统计、API/数据库回读、截图与独立审查结论。

- 核心 12 项、后端定向 24 项、前端全量 377 项测试通过。
- 类型检查、所改文件 ESLint、后端打包、enterprise/classic 构建通过。
- 真浏览器完成本体 v1/v2 发布、差异、未保存工作区保护及只读角色验证；H2 完成升级、落盘及重启回读。
- H2 为本次真实数据库验证范围。MySQL/Kingbase 已提供迁移，但没有实跑这两类数据库；没有运行无关的全部 Server 测试。
- 原 `build`/`lint` 包装脚本引用缺失的 `scripts/check-snowflake-precision.sh`；本次使用显式 vue-tsc、ESLint、Vite 命令。全量前端测试有与基线相同的非失败 ECONNRESET 日志；构建保留既有大 vendor chunk 警告。

关闭功能用于停止使用，不删除版本和治理记录。恢复旧本体配置通过建立新草稿并发布新版本完成，不改写历史、不执行破坏性数据库回滚。
