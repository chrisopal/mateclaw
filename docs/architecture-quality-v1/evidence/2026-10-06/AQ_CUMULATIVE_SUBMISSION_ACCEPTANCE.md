# 累计架构整改提交工程证据

2026-10-06，按用户既有授权提交到 `codex/aq01b-execution`。源码提交 `35679f9620b67aeb18abbd2220cba5e3e3cd2e08`，base `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，checked tree `4ab411bd6a81a11eb9874320a5a3311d805bf473`。3672 个已审阅路径完整暂存，无未暂存或未跟踪输入；提交后 tree 完全一致，工作区干净。证据补充单独作为文档提交，不倒写源码门禁身份。

## 实际检查

- 初始和清理后 `python3 -B scripts/quality/verify.py --mode dev --base origin/dev` exit 0 / SCAN_PASS，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。
- 显式 `python3 -B scripts/quality/verify.py --mode commit --report-dir /tmp/mateclaw-cumulative-commit-20261006` exit 0、PASS、submission_ready=true；任务标识 `mateclaw-cumulative-commit-20261006`。
- 正常 `git commit -F /tmp/mateclaw-cumulative-lore.txt` exit 0；pre-commit 完整复核 PASS、submission_ready=true；任务标识 `529cpdbq`。未绕过 hook。两次 target_identity 均为上述源码 tree。
- 两次均执行 JDK 21 / Maven clean verify：6,707 项中 6,637 项执行通过、70 项跳过，零失败/错误；UI Vitest 1,195/1,195，Node 5/5，guard 91/91，cost tool 16/16。Spotless、Prettier、ESLint、vue-tsc、ID 精度及 enterprise/classic 构建均通过。跳过项不是 PASS；大型 chunk 构建警告保留。
- 架构新增违规 0；存量 2,438，未改 baseline。生产字节码规则和清零路径按本批独立控制面审核执行，不宣称全仓零违规。

## 可回读证据

[cumulative-submission](cumulative-submission/) 内两份 `*-gate.tar.gz` 包含各自原始 report.json、architecture.json、Vitest JSON 及全部步骤日志。每份 manifest 记录压缩归档摘要、原文件摘要、脱敏后摘要及数量；已逐文件解包核对。原 report.json 中 log_sha256 对应脱敏前文件，须使用 manifest 的 archived_sha256 验证归档字节，不能混用。只替换测试 JWT、生成密码及 password JSON 值，不改结果或断言。

独立技术审阅：`cumulative_control_review` 对 7 个控制面文件及 5 个 canary，`cumulative_runtime_review` 对 19 个生产文件和相关测试；均 COMMENT、0 个具体问题。详见 independent-reviews.json 的范围摘要。Java LSP / ast-grep 未运行，不把 agent 技术审阅当作维护人审批。

## 行为及回退边界

售前查询/执行/变更/下载及材料、需求、差距面板拆分，Workbench 1226 行；服务接收、发布授权、新排队 DTO 等职责分离，PresalesService 799 行。保持 IDs 字符串、wire、来源授权、事务、精确修订、取消、CAS 和冻结产物语义。完整 DTO/V2 对象迁移、坏/耗尽历史 RUNNING 恢复仍开放。

源码结构回退可按提交处理；写入新摘要回执后不可直接退回旧 writer，必须单独设计迁移。测试 JWT 不应恢复到 Git。临时文件按 cleanup-manifest.json 中工作树外备份恢复；未操作生产数据、部署、合并或仓库管理。

本记录关闭的是本批精确源码树的本地工程提交检查。正式 46 项 AC 仍 NOT_RUN；生产数据库方言、完整浏览器/模型/Office、客户验收和维护人批准未由本批替代。远端 push/最新合并树 CI 及分支强制状态需推送后回读，结果记录于 PR #5；截至本记录，目标分支 required checks 为 off，workflow active 不能视作强制生效。

计数更正（2026-10-06）：上述完整 guard 套件为 91 项（原 gate 72 + Java migration 19）。早期摘要及源码提交消息将 72 项子集误标为完整计数，原始归档日志均记录 Ran 91；日志及历史 Git 对象不改写。独立控制面审阅的 72 项子集结果仍保持其原范围。
