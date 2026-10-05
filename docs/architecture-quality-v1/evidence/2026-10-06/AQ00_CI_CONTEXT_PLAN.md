# AQ-00 工作流报告路径上下文修复计划

起点 HEAD 7693496dfdf1e4e2fbaa469ff8a16d10dd886896，工作区干净；dev base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。远端 run 37358625755 在 workflow 解析阶段失败，未启动 jobs：Line 18 Col 19 的 job env 使用 runner.temp，被 GitHub 拒绝。此前本地完整门禁通过不能证明 GitHub 工作流解析成功。

保持报告目录位于 runner temp、trusted base runner、候选 merge tree、权限、版本 pin、全部检查及 Required 聚合不变。把 REPORT_DIR 的 runner 上下文延迟到执行步骤的 env，上传步骤直接使用同一 runner.temp 路径。无业务/数据库/API/授权/事务变更，不改分支保护。

先新增离线回归保护 verify job env 不引用 runner 上下文，并在原 workflow 复现 RED；再改上下文位置，运行 guard 自测及 dev。独立控制面审阅，完整暂存；正常 pre-commit 运行统一精确树 commit 门禁，exit 0/submission_ready=true 后才能形成提交，正常 pre-push 再验证全范围。GitHub 后续运行是实际解析验收；如出现其他失败按真实原因处理，不降低规则。

回退仅恢复 workflow 路径接线会重现解析错误；不撤销源码整改。保留远端失败 URL 与本地正反例，正式 AC 与 required enforcement 不由修复关闭。
