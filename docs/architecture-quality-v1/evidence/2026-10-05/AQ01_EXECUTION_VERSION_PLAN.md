# AQ-01：执行快照精确版本边界修复计划

基线 HEAD 为 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，origin/dev 为 ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，保留累计 dirty worktree；初始 dev 25eyu33a 为 SCAN_PASS/submission_ready=false。

问题：ContextProvider、EmployeeRuntime、ExecutionRevalidationProvider、GenerationService 和 GenerationCoordinator 在执行快照或任务版本边界使用 asInt，导致小数、超范围或缺省值经截断/默认值形成错误的修订匹配。复用 PresalesProjectItems.positiveRevision/matchesRevision；不新增解析器、依赖、DTO或数据库迁移。历史合法正整数字符串继续支持，数值小数（含2.0）、超范围、零、负值、null、容器与非法文本拒绝。保留调用方既有错误码、先权限后来源检查、operation replay优先及取消清理。

先编写独立参数化边界合同测试：快照创建、context两侧版本重验、工具前两侧版本重验、runtime构造、入队前持久化回读、取消预留和terminal写入。旧实现必须得到可复现RED；合法整数/历史文本及真正版本变化为正/反控制。确认无模型/重验/来源读取/排队/错误CAS等后续副作用。再逐边界移除截断转换，使用既有helper精确匹配。

终态发现非法项目版本时不能用截断版本写回；走既有VERSION_CONFLICT重试和安全失败恢复，原始坏记录的人工/受控数据修复不由本片自动执行。回归覆盖既有生成、runtime、source、原子结果接受、事务集成和生产ArchUnit；运行Spotless与dev。记录真实RED/GREEN、受检源码及日志摘要；独立代码审阅不替代正式QA签收。完整AC状态与远端CI保持真实未完成。
