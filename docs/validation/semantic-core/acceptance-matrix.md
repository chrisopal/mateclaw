# M3 验收矩阵

| 维度 | 结果 | 证据与边界 |
|---|---|---|
| SEM-09 工作台 | 通过 | KB入口、固化快照、实体登记、候选、修改、冲突、证据、来源治理与正式图；30项语义UI测试 |
| SEM-10 工具 | 通过 | 默认关闭；真实工具注册和 Agent 过滤；Web身份、当前权限、跨工作区和非Web负测；真实模型返回事实/修订/证据 |
| 后端全量 | 通过 | 5,030 tests，0 failures/errors，3 skipped；core 由 reactor 同时验证；server-test-summary.txt |
| UI全量 | 通过 | 65 files / 389 tests；最后 loading 指令补注册后重跑30项语义测试、类型检查和模块lint |
| 构建 | 通过 | enterprise/classic；已有vendor大块告警；build-*.txt |
| 全库lint | 基线未通过 | 原有 useAgentRunGroups.ts:185 prefer-const，1 error / 43 warnings；该文件未修改 |
| H2 HTTP全链路 | 通过 | 发布、绑定、Unicode证据、并发确认200/409、修改提案、380/400冲突、关系、撤回、历史；SemanticEndToEndIntegrationTest |
| H2实际落盘/重启/备份 | 通过 | restart-after-withdraw.json、backup-readback.txt；原工作区数据库未使用 |
| MySQL 8.0 | 通过 | 相同HTTP流程、Unicode、并发事务；独立空库及V190→194升级；mysql-summary.txt |
| Kingbase | 未验证 | 无真实环境，不能用H2或Postgres代替 |
| 功能开关运行态 | 通过 | 关闭后status=false、业务404、登录/原KB可用，随后恢复enabled=true；feature-off.json |
| 浏览器业务闭环 | 通过 | 实际创建快照/实体/候选、409冲突阻止直接接受、保留380V、多值hasPart、查看证据、撤回来源；原文含emoji，范围[0,64) |
| 浏览器样式 | 有限通过 | enterprise 1366/1440/1920/390、浅色与1440深色截图；classic1440实际加载；未逐一覆盖每种尺寸×主题×profile的所有本体旧页面和键盘流程 |
| 自动抽取 | 非本阶段 | 固化不会自动生成候选，人工选证据与建模 |

## 真实场景标识

- graphId：2096946072925818882，固定本体修订2096869358018138113（版本2）。
- 知识库：2096945981489991682；验收来源“M3 设备核验记录”。
- 380V事实：2096955716628983809，确认revision2；400V竞争事实：2096955791493115905，拒绝revision2。
- 证据：2096955716557680641；SHA256：73a4c58e4b6b44f040d6ae6fdbd47690af06748797c773ffc23006e591adbea8。
- Agent：2096956318532579329；调用traceId：2a38b309-2daa-4870-8f82-d882fc841477。测试后全局工具已恢复关闭。

## 已修复的验收发现

- 工具沿用共享授权查询，不伪造HTTP SecurityContext；搜索不再静默只检查前100条事实。
- 邻域去重，限制节点数时不返回悬空边；边携带准确修订号。
- 修改提案返回具体内容，审核无需猜测变更。
- 图布局与移动标题换行修正，新增页面显式注册loading指令。
- 测试夹具限于semantic-test profile，避免全量应用测试扫描到mock Wiki bean。
- MySQL V192/V193 collation错误已修正，历史校验和变化与部署限制见README。

完整后端测试成功退出时 Surefire 报告等待30秒后终止残留测试JVM；没有失败测试。该退出清理现象保留记录，不作为功能失败或生产稳定性证明。
