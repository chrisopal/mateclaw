# AQ-04 分页单测订阅边界整改计划

起点5af0981885d4928de353ad7ff0e6123aaf0acf86，tree d977b18879cc20f844723e4358efb3db4a45e353；固定origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。原生HTTP测试已单独提交并通过精确门禁/正常钩子，但完整1020项仍输出一条socket诊断。/tmp临时配置仅在诊断运行包装HappyDOM Fetch.onError，调用原函数且不消音；追踪定位useTeamRuns.test.ts到localhost/api/v1/teams/team-1/events。两次临时setup加载失败已保留，增加仅临时fs allow后全1020通过并定位，不改项目runner。

本片先新增原分页单测“不外发fetch”观察断言，旧实现应红；然后仅模拟订阅公共端口，原默认paged API两次参数/游标/去重/旧API未调用断言不变，补一次订阅与释放断言；整个scope/spy流程finally释放。该文件是分页/协调单测，实际SSE协议已有useTeamEvents合同，真实HTTP仍由前片五项覆盖；本片不声称SSE网络端到端。扩展既有Node子进程回归到这份测试的完整运行，检查实际结果/stderr而不抑制。

只改useTeamRuns.test.ts与legacy诊断回归，生产composable/API/订阅不改，无新依赖或超时/runner/断言削弱。固定格式要求若规范该既有测试文件，在报告中说明仅格式与第一项夹具逻辑变化。测试夹具/环境受限定独立技术审阅，维护者控制面批准仍PENDING。执行dev、相关chat与SSE合同、类型/lint/固定format、精确staged、正常钩子及checked push；归档两源提交、原红例及完整日志SHA。46项正式AC全部NOT_RUN。回退本片会恢复分页单测意外真实SSE请求；无持久化写入。

初始dev cy_pw3qs SCAN_PASS。根pnpm --dir診断首次被12.6.0/12.4.2版本要求拒绝，随后UI cwd使用原本pnpm取得真实12通过/1失败：分页单测fetch实际调用一次；红例失败时旧测试未释放scope导致AbortError另记保留。一次修复脚本在UI cwd重复路径失败，未应用修复却先执行format和测试（结果仍红），改用root路径后继续。修复使用文件局部vi.mock订阅公开模块（默认依赖在import时捕获，事后spy不能替换该已捕获引用）；mock只替代协调单测订阅端口，fetch spy仍只观察，生产默认依赖不改。

最终4文件38项真实HTTP/分页协调/SSE协议与事件ID合同通过，扩展子进程1项、vue-tsc、两文件lint实际exit0。旧文件其余12项仅固定格式变化：HEAD只读快照通过同配置格式化后，第二个it起的完整后缀与当前源字节相等（SHA另归档）。没有删除旧合同。
