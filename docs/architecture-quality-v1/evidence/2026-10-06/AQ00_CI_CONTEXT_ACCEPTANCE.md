# AQ-00 GitHub 工作流上下文修复记录

基于 `7693496dfdf1e4e2fbaa469ff8a16d10dd886896`，远端 [run 37358625755](https://github.com/chrisopal/mateclaw/actions/runs/37358625755) 因 job env 的 runner.temp 表达式不可用而在解析期失败。API jobs/check-runs 均为空，无应用测试运行；不能将此前本地工程 PASS 写成远端 CI PASS。

修复仅将 REPORT_DIR 移到 Execute 步骤 env；上传 with.path 直接引用同一 runner.temp/mateclaw-engineering-report。trusted base、候选 merge tree、BASE_SHA、只读 token 权限、依赖/action pin、全部测试和 Required 聚合不变。未改变分支保护，也未安装依赖。

新增离线回归在修改前对原 job env 明确失败，修改后全部 93 项 guard 自测通过（72 gate + 19 migration + 2 workflow）。该测试仅保护本次 runner 上下文错误，不是完整 YAML 或 GitHub schema 验证器。系统 Ruby YAML 解析并核对实际生成/上传路径相等，job env 无 runner 引用；diff --check 通过。初始/修复后 dev exit 0、SCAN_PASS，任务 pfra2uww/5ke4xwa6，仍不等同提交或远端通过。

[ci-context](ci-context/) 保存 RED/GREEN、dev、远端失败与保护状态证据，manifest.json 逐项 SHA-256。完整 guard 数量更正见累计提交工程证据：原 91 项，早期摘要的 72 是子集计数，未改原日志或历史 Git。

提交由正常 pre-commit 执行精确暂存树统一 commit 门禁；hook 返回 0 且 submission_ready=true 才能形成提交。推送由正常 pre-push 复核范围。实际提交/报告身份和远端后续运行链接记录于 PR #5，不提前标记通过。正式 AC、维护人审批、生产环境和 required enforcement 仍独立未完成。

独立审阅提出低优先级回归保护缺口后，补充 runner 索引访问拒绝及生成/上传路径一致性断言；三份内存变异（job dot、job index、上传目录不同）各触发恰好一个断言失败，无执行错误，未改真实 workflow 来制造结果。

独立只读技术审阅：`/root/ci_context_review`，最终 COMMENT、0 个剩余问题；初审 LOW 已整改并复审。focused 2/2、diff --check 通过，LSP/AST/actionlint 不可用，未冒称运行。审阅 workflow SHA-256 `683aa28850396145984e32cbfee486f80b6a3dc4d136f394e7386d0d01c558b0`，最终 test SHA-256 `ba7ced968a74d2f4d8f554a20fbabe47a5413d3cebd1f96f149f724e3c33a4aa`；不是维护人批准。
