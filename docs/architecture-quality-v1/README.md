# 架构检查与实施设计入口

本目录以用户提供的 1.0.0 规范为需求来源。原始 ARCHITECTURE_SPEC、RULES、CHECKS、ACCEPTANCE、CODEX_TASKS 等保留原文；其中“执行下一阶段”的示例提示词不扩大本轮授权。

质量门禁已在 `codex/architecture-quality` 提交并推送；业务架构整改从 `codex/aq01-execution` 开始，按实施设计逐切片推进。当前第一片只抽离项目会话工具策略边界，完整 AQ-01 尚未验收。

根据项目负责人补充要求，这套检查现已写入根 AGENTS、工程门禁 Skill、PR 模板和审核台账，默认适用于全项目后续开发。可依据实际调整，但调整也必须提供覆盖差异与验证证据并经过审核。

- [实施设计](IMPLEMENTATION_DESIGN.md)：现状、公共契约、阶段依赖、事务/迁移/回退与 ADR。
- [检查台账](REVIEW_CHECKLIST.md)：AQ-00–11、AR/TS/UI/DB 规则与 AC-01–46 的责任和证据。
- [当前验证记录](SETUP_EVIDENCE.md)：实际命令、结果、存量、未测范围和启用边界。
- [机器可读验收台账](acceptance-register.json)：所有 AC 场景默认 NOT_RUN，不从脚本自测推导应用验收。
- [原始架构要求](ARCHITECTURE_SPEC.md)、[验收要求](ACCEPTANCE.md)、[CI 启用流程](CI_SETUP.md)。

## 日常执行

```sh
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
# 仅当预期内容已完整暂存且没有未跟踪输入时：
python3 -B scripts/quality/verify.py --mode commit
# SSH 远端推送：保留完整 pre-push 门禁，并维持长检查期间的连接
scripts/quality/push-checked.sh -u origin <branch>
```

dev 只证明词法增量与门禁自测，输出 SCAN_PASS 不等于架构合规或可提交。commit 模式拒绝部分暂存；不能通过清理用户材料、降低规则或跳过测试绕过。CI、CODEOWNERS 和分支保护需要实际合入、远端运行与独立签收；本地安装不是远端强制已生效。


最新 SQL 列表投影片：[AQ06_LISTING_PROJECTION_ACCEPTANCE.md](evidence/2026-10-03/AQ06_LISTING_PROJECTION_ACCEPTANCE.md) / [manifest](evidence/2026-10-03/listing-projection-test-results.json)。现有表派生事实、四类原子 writers、旧筛选/故障顺序与 wire 保持；真实 MySQL 29 项及 205 条 mysqldump 恢复证据不替代 Kingbase/完整 V2/生产回退/业务 QA。

Java 迁移入口与冻结源码检查已补强，见 [AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md](evidence/2026-10-03/AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md)。尚有版本化请求 hash/历史回执兼容、闭包人工审阅、ArchUnit/封口、独立维护人批准和远端强制等工作；46 正式 AC 仍 NOT_RUN。
