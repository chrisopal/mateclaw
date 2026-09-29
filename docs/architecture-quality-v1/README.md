# 架构检查与实施设计入口

本目录以用户提供的 1.0.0 规范为需求来源。原始 ARCHITECTURE_SPEC、RULES、CHECKS、ACCEPTANCE、CODEX_TASKS 等保留原文；其中“执行下一阶段”的示例提示词不扩大本轮授权。

本轮范围：安装项目检查、记录真实基线、制定整体实施设计。业务架构整改尚未执行。

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
```

dev 只证明词法增量与门禁自测，输出 SCAN_PASS 不等于架构合规或可提交。commit 模式拒绝部分暂存；不能通过清理用户材料、降低规则或跳过测试绕过。CI、CODEOWNERS 和分支保护需要实际合入、远端运行与独立签收；本地安装不是远端强制已生效。
