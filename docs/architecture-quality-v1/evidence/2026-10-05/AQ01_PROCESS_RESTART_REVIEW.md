# AC-21 进程重启测试独立审阅

独立只读审阅 `/root/process_restart_review`，范围为 ModuleStartupMatrixTest、ModuleStartupProbe、PresalesRestartProbe 和本批计划；未修改代码或并发执行 Maven。结论 COMMENT，0 个剩余可操作发现。检查了生产 main、真实 HTTP/权限/仓储、流请求未完成时 halt、ApplicationReadyEvent 恢复、三次进程身份、相同文件库、版本与原字节幂等、私有临时凭据及隔离清理。

审阅曾读取到父测试旧的 setup_initialized=false 断言，刷新后确认已按阶段明确断言：ordinary/seed 为 false，recover/repeat 为 true。该区别符合生产 DatabaseBootstrapRunner 通过用户数判断初始化的行为，原八组合空库断言保留。第一轮 SQL 列名错误和第二轮旧空库断言失败均归档。

独立回读第三轮定向日志：1 项通过，0 failure/error/skip；三个 PID 独立，相同 H2 文件库，版本2→3→3，回执/修订各2→2→2。共享八组合的后续验证见验收记录。

三份 Java 文件的 LSP 请求均返回 tsc skipped: no tsconfig found，不是 Java 诊断通过；AST-grep 不可用，采用源码检查。因此不出具 APPROVE，也不替代维护人控制面批准、正式业务 QA 或远端 CI。
