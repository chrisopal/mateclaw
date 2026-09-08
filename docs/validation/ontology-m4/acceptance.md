# M4 本体增强验收记录

日期：2026-09-08。状态：本地功能验收通过；Kingbase 和生产环境未验证。

工作区：`/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1`；分支 `codex/enterprise-semantic-core`；开发基线 `fe638a973419a10f4af4310cfe914aaa72d5dfee`。验收时 M4 修改尚未提交、推送；Git 交付状态以分支提交记录为准。运行保留本地 API 18088、enterprise UI 5176。

## 交付与使用

1. 本体管理 → 从已发布版本创建草稿 → 编辑类型/属性/关系。可配置别名、弃用提示、TEXT 允许值和 DECIMAL 最小/最大值；保存、校验、比较、发布。
2. 版本历史 → 使用情况 → 对指定图“分析影响”。来源是该图实际绑定版本，目标是所选发布版本或已保存草稿；报告展示扫描版本/时间、受影响计数，支持定位到工作台。
3. 发布版本 → 导出模板；本体列表 → 导入模板 → 选择 JSON → 预览 → 新建草稿 → 校验/发布。示例：[质量本体模板](../../examples/semantic/quality-ontology-package.json)。仅复制模型，不复制图谱、事实、证据或权限。
4. 在知识库绑定一个可用的已发布本体版本后，新建候选按该版本校验。发布本体 v2 不会升级已绑定 v1 的图；非空图仍禁止直接换绑。

| 任务 | 实际交付 | 主要验证 |
|---|---|---|
| M4-01 | 双格式 codec、严格 JSON、旧格式兼容和降级写入保护 | 旧发布 JSON 字节保持、历史操作重放、非法格式/字段/标量拒绝 |
| M4-02 | JDK-only 约束、别名/弃用、逐术语变更分类 | 核心 32 项；闭区间、枚举、类型适用性、分类与稳定 key |
| M4-03 | 草稿/候选/提案/审核/裁决共用核心规则，别名检索 | 新图拒绝越界；旧绑定规则保持；权限和既有质量场景回归 |
| M4-04 | 可视化配置、诊断、警告可发布、只读展示 | 浏览器创建、保存、校验、发布 v2；移动端约束编辑；50 项前端测试 |
| M4-05 | 发布模板导出、严格预览、新草稿导入、摘要与恢复 | H2/MySQL 并发幂等、失败回滚、跨工作区、恢复；浏览器导出→导入→发布 |
| M4-06 | 按图影响分析、完整统计/有限明细、版本及权限重查 | 当前事实含 SUPPORT_LOST、候选/提案、并发 409、截断/上限/时间窗口 |
| M4-07 | 使用情况、发布差异、影响报告和工作台定位 | 实际定位到 0.08 mm 的当前 r2；失败清除旧报告；没有迁移操作 |
| M4-08 | 质量根因全链路、H2/MySQL、双 profile、重启回读 | 下述运行证据、测试和截图；明确未验证项 |

## 质量根因端到端结果

全部资料为本地合成测试数据，不代表模型自动推断出的真实根因。

- 本体 `2097083062522511362` 的 v1 修订 `2097083062535094273`，旧图 `2097084463260663810`；当前尺寸偏差事实 `2097091016499953666` 为 r2、ACCEPTED、0.08 mm。
- 通过浏览器创建 v2：尺寸偏差范围 [-0.05, 0.05]，别名“测量偏差”，新增调查状态枚举“待复核/已确认”。v2 修订为 `2097119581715070978`。
- 对旧图分析目标 v2：扫描 5 实体、4 条当前事实、0 提案；发现 1 条越界事实；图 mutationVersion 20 不变。4 条中包含失去来源支持但仍为 ACCEPTED 的事实，避免漏掉约束冲突。
- 发布 v2 后旧图仍绑定 v1。浏览器“定位”打开当前 r2、0.08 mm、来源/版本所在工作台。
- 新图 `2097122581024112642` 绑定 v2：0.08 返回 422 / VALUE_ABOVE_MAXIMUM；非法调查状态返回 422 / VALUE_NOT_ALLOWED；0.03 mm 成功录入并审核为 ACCEPTED。别名搜索只返回合法可信事实，规范名称仍为“尺寸偏差”。
- 浏览器导出 v2 后导入为“ M4 模板复用验收 ”（本体 `2097122292376305666`）；完成预览、建草稿、校验、发布，初始使用图数 0。导出内容与 API 返回模型一致；原本体不变。
- 最后正常停止/重启 M4 服务，重新登录并回读：v2、导入本体、旧 v1 绑定、新 v2 绑定、可信 0.03 mm 事实均存在。为编辑器验证创建的临时草稿已通过页面丢弃，导入本体 hasDraft=false。

可回读证据：[runtime-readback.json](runtime-readback.json)。包含实际 ID、报告、错误码、可信查询和重启后的数据，不含凭据。浏览器负责建模、发布、导出导入和定位；边界写入/审核由真实认证 API 执行，未声称全部步骤均由浏览器完成。

## 测试与构建

| 检查 | 结果 | 本机原始日志 |
|---|---|---|
| Core Maven | 32 passed，0 skipped | `/tmp/ontology-m4-core.log` |
| Server Semantic*Test | 85 discovered，68 executed passed，17 MySQL opt-in skipped，0 failure/error | `/tmp/ontology-m4-full-final.log` |
| 独立 MySQL 执行 | 16 passed / 0 skipped；追加混合时间窗口参考用例 1 passed / 0 skipped；共 17 个不同用例 | `/tmp/ontology-m4-mysql.log`、`/tmp/ontology-m4-mysql-mixed.log` |
| 语义前端 Vitest | 18 文件，50 passed | `/tmp/ontology-m4-ui-final.log` |
| vue-tsc | 通过 | `/tmp/ontology-m4-tsc-final.log` |
| 目标 ESLint | 0 error / 0 warning | `/tmp/ontology-m4-eslint-final.log` |
| Snowflake ID 精度检查 | 通过，数据库 ID 保持字符串 | `bash scripts/check-snowflake-precision.sh` |
| enterprise / classic Vite 生产构建 | 两者通过；保留仓库已有分块体积警告 | `/tmp/ontology-m4-enterprise-final.log`、`/tmp/ontology-m4-classic-final.log` |

复现命令（worktree 根目录，Java 21）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-semantic-core -am test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl mateclaw-server -am \
  -Dmaven.compiler.proc=full -Dtest='Semantic*Test' -Dsurefire.failIfNoSpecifiedTests=false test
cd mateclaw-ui
bash ../scripts/check-snowflake-precision.sh
pnpm exec vitest run src/features/semantic
pnpm exec vue-tsc --noEmit
pnpm exec eslint src/features/semantic src/i18n/locales/en-US.ts src/i18n/locales/zh-CN.ts
pnpm exec vite build --mode enterprise --outDir dist/enterprise
VITE_UI_PROFILE=classic pnpm exec vite build --mode classic --outDir dist/classic
```

MySQL 使用既有 opt-in 测试环境和受控临时凭据，不把密码写进命令/文档。M4 没有新增 SQL 或依赖，未修改既有迁移 checksum；数据库仍为既有 V196 结构。本次在实际开发 worktree 分别执行 Snowflake 精度检查、tsc、目标 ESLint 和两种 Vite 构建；未用主 checkout 的脚本状态替代工作区验证。

## 影响分析容量与一致性

Apple M4 Pro、本地 H2 / Docker MySQL，单次 10000 条当前事实 API 分析耗时（不含造数、非 p95/SLO）：

| 分布 | H2 | MySQL |
|---|---:|---:|
| 相同值重叠 | 317 ms | 406 ms |
| 不同值密集重叠 | 210 ms | 265 ms |
| 不相交区间 | 220 ms | 276 ms |
| 范围违规 | 181 ms | 349 ms |

补充覆盖 60 条固定随机种子的区间/UNKNOWN 与独立两两判定参考结果一致，半开区间边界、替换自身事实、多提案冲突也有验证。当前事实/提案各最多 10000；明细最多 200，但统计不截断；10001 明确返回 504 / IMPACT_INCOMPLETE。主动预算 4.8 秒、事务上限 5 秒，不把时间预算失败显示为“无违规”。

只读事务结束前清除 MyBatis 会话缓存，再读 graph mutationVersion、draftVersion 和权限；并发写图/草稿返回 409。扫描不包含历史修订迁移或证据支持重算，不能据此承诺所有历史都可迁移。

## 修复与架构边界

本轮实测发现并修复：

- MySQL 并发同 operation 导入锁等待后仍读旧快照：短工作区行锁加 READ_COMMITTED，重复请求回读同一结果。
- 影响分析尾部版本读取被会话缓存命中：清缓存后重查，避免返回过期“通过”。
- 密集 SINGLE 槽位两两扫描：改为按时间扫描与值分组，复用核心冲突判定。
- 1.0/1.00 数值等价摘要不一致：合法 DECIMAL 边界统一规范化。
- 严格嵌套包解析与旧 null 输入错误码：统一 codec，未知字段/重复 key/错误标量拒绝，语义空值返回既定校验响应。
- 影响定位取消初始加载、状态检测重复请求、影响失败残留绿色报告、JSON.parse 隐藏重复 key：均有行为修复和回归。
- 属性枚举换行/空格丢失、只查看另一属性即触发未保存提示：保留输入缓冲，只在实际类型变更时整理约束；补测并经浏览器查看→取消验证。

核心仍只依赖 JDK；宿主负责权限、事务、数据库和 HTTP；UI 复用成熟管理页。没有新服务、额外数据库、规则引擎、任意表达式、实体等价或自动迁移。新图规则来自绑定修订，不由全局最新版本覆盖。

## 界面验证

enterprise/classic × 浅色/深色 × 390/1280/1920 共 12 个视口组合，页面宽度均不超出视口。窄屏长表在表格内部横向滚动；侧栏动画稳定后正常隐藏；属性抽屉可滚动到上下界/枚举并操作底部按钮。新流程无浏览器错误；项目既有自定义 i18n 编译器提示保留。

[视觉验收记录](visual-verdict.json)。主要截图：

- [影响分析结果](images/impact-after.png)、[定位当前事实](images/located-fact.png)、[导入预览](images/package-preview.png)
- [企业浅色 1280](images/enterprise-light-1280.png)、[企业深色 390](images/enterprise-dark-390.png)
- [经典浅色 390](images/classic-light-390.png)、[经典深色 1920](images/classic-dark-1920.png)
- [数值约束 390](images/editor-range-390.png)、[枚举编辑 390](images/editor-enum-390.png)

## 未验证与下一阶段

- Kingbase 无实机环境；不声称生产数据库兼容已验收。生产并发规模、长时间压测与生产灾备回滚未演练。
- 已验证同版本服务重启与数据回读；不能把程序回退到不识别 format 2 的旧二进制。需要回退时必须使用仍能读双格式的兼容构建。
- 文档自动抽取、LLM 生成候选、复杂本体推理和非空图版本迁移属于后续 M5/M6，本次未实施。
- 本地合成验收数据保留用于复查；本记录不代表生产部署完成。
