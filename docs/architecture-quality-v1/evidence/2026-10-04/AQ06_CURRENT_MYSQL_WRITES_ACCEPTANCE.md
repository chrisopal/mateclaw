# AQ06 当前售前应用 MySQL 回归

当前 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，加 AQ05 投影职责切片及 AQ20 未接入 hash WIP。实际来源摘要及脱敏日志归档见 mysql-current-writes-results.json。

真实 JDK21/Maven `-pl mateclaw-server -am -Dtest=PresalesSqlListingTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test` 在自行创建的 loopback MySQL 8.0.46 隔离容器/空 schema 中执行：29 tests，0 failure/error/skip，进程 exit0。环境变量只向该隔离进程提供地址/凭据；schema 使用 mateclaw_aq_acceptance_*，自建容器与匿名卷已删除（exit0），未操作既有用户容器。

覆盖当前应用 SQL 列表 UTF16、投影、create/manual update/replay/重复修订回滚等既有断言。fixture 明确迁到 V218，未证明 V219 或版本化 writer；该类没有执行接受员工结果的 S1 投影路径，不能将 29 项用于宣称 AQ05 新投影通过 MySQL 业务验收。旧 hash 碰撞刻画保留。

Kingbase、真实生产升级/恢复、完整 V2、角色/并发/浏览器和独立业务 QA 均 NOT_RUN；46 正式 AC 状态未修改。测试不批准新 hash/历史回执兼容策略。
