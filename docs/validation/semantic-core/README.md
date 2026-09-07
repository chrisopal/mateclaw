# M3 工作台与 Agent 取证验收

日期：2026-09-07。基线：M2 `875a3f5b`，分支 `codex/enterprise-semantic-core`。SEM-09、SEM-10 已实现；SEM-11 完成下表所列本地验证，Kingbase 和完整浏览器组合矩阵保留未验证项。未推送、未部署。

## 使用流程

1. 启用 `mateclaw.semantic.enabled=true`，在本体管理维护类型、属性、关系并发布版本。
2. 进入知识库的语义绑定面板，绑定已发布本体版本，点击“打开知识工作台”。绑定决定该图允许使用的类型与谓语。已有事实的图不自动跟随本体升级。
3. 在“实体登记”登记实际设备、部件等对象；在“来源”选择知识库资料并固化快照。这里只固定文本，不自动抽取事实。
4. 在“提出候选”选择主体、本体谓语和值，选择固定快照的原文片段作为证据。关系候选选择目标实体。先创建码点证据，成功后才提交候选。
5. 管理员在“候选与事实队列”查看原文、修订并确认；同期单值冲突必须在“冲突审核”选择保留的事实并填写理由。成员可提出修改，管理员在“修改提案”查看具体内容后审核。
6. “可信事实”只展示已接受且当前仍有有效证据支持的事实和关系。点击关系可打开事实审核与证据。“来源”支持撤回整个来源或仅排除某个快照，历史修订保留。

本体规定“设备可以有什么属性、哪些关系连接哪些类型”；图谱记录“P-101 的电压是 380V、它安装了 M-01”。快照与证据说明这些事实来自哪里。版本绑定固定解释与校验规则，不能保证来源内容本身正确，仍需审核。

## Agent 使用

工具迁移默认 `enabled=false`。模块启用后，在工具管理显式启用 **SemanticTool**，再给指定 Agent 绑定 **SemanticTool**。工具方法为 `semantic_search`；`semanticTool` 是 bean 名，不能作为 Agent 管理 API 的工具目录名称。

从已登录 Web 对话询问指定 graphId 的事实。工具从宿主 ChatOrigin 取得请求人和工作区，每次重新核对当前用户、工作区及知识库权限，只返回有限可信事实、statementId、revision、evidenceIds 和 traceId。工具参数不能自行声明身份。匿名、缺工作区、外部 IM、cron 均拒绝。

实际验收临时启用工具并调用真实模型，返回 380V、事实修订 2 和正确证据编号；结束后已恢复工具关闭。见 [模型结果](evidence/model-result.json)。这是一次真实取证验证，不代表所有模型回答都可靠。

## 复现命令

从工作树根目录执行，JDK 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full test
```

MySQL 使用两个独立、可丢弃的空数据库，设置 `SEMANTIC_MYSQL_TEST_URL`、`SEMANTIC_MYSQL_TEST_USER`、`SEMANTIC_MYSQL_TEST_PASSWORD`、`SEMANTIC_MYSQL_UPGRADE_URL` 后执行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=SemanticEndToEndIntegrationTest,SemanticMySqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

升级数据库必须从空库开始，测试先迁移到 V190 再到 V194；重复使用已在 V194 的库不能证明升级路径。本次使用隔离 MySQL 8.0、回环端口 13316，未连接用户数据库。

在 `mateclaw-ui` 执行 `npx vitest run`、`npx vue-tsc --noEmit`、`npx eslint src/features/semantic`，以及分别设置 `VITE_UI_PROFILE=enterprise` / `classic` 的 Vite build。全库 lint 仍有基线 `useAgentRunGroups.ts:185` 的 prefer-const 错误及既有警告，未修改无关文件。

## 数据与回滚边界

H2 使用独立文件库，关闭进程后复制备份、以独立路径打开备份并查询，再恢复原实例启用状态。重启回读：图版本 14，来源 WITHDRAWN，可信事实 0，证据 HTTP 404，电压事实的两次修订仍保留。见 [回读](evidence/restart-after-withdraw.json) 和 [备份查询](evidence/backup-readback.txt)。示例图当前为空是撤回验收结果，可在历史队列查看原记录。

MySQL 首次实跑发现 V192 的固定 collation 与 V191 继承数据库 collation 不一致，导致外键创建失败。本次修正尚未对用户 MySQL 部署的 V192、V193，统一继承数据库字符集排序规则。**这两份文件的校验和发生变化**；上游 V190 及以前、既有 H2/Kingbase 文件未改。V194 为新增默认关闭工具迁移。SHA256 前后清单用于审查，不是 Flyway 内部 checksum。若其他环境已执行旧版 V192/V193，不能直接套用本次迁移或盲目 repair，应单独制定兼容的前向迁移。

停用时关闭工具和模块，保留快照、修订和治理记录；不删除语义表，不改写已发布本体。Kingbase 没有真实环境，不能据 H2/MySQL 结果宣称支持已验收。自动抽取、自动实体对齐与自动本体迁移不属于本次 M3。

详见 [验收矩阵](acceptance-matrix.md)。
