# AQ-04 传输诊断与分页夹具工程验收

本批修复两处已复现的测试诊断来源，生产请求/分页/SSE、权限、事务、CAS/receipt、wire和迁移均未修改。它是测试合同与夹具整改，不是正式架构或浏览器业务签收。

固定base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。第一源码 5af0981885d4928de353ad7ff0e6123aaf0acf86，tree d977b18879cc20f844723e4358efb3db4a45e353；第二源码 988a34e7b923db3069b5e1844689f2824362c7ce，tree 4535f06111154882ca63e943b45e6a7e2d708573。两个计划分别为AQ04_TRANSPORT_DIAGNOSTIC_PLAN.md和AQ04_TEAM_FIXTURE_PLAN.md；两份manifest保存各自源码快照SHA、日志原输入SHA/归档SHA、实际report及限制。归档显示副本仅规范行尾/末尾空行，敏感值遮蔽计数逐文件可核对。

## 修改与反例

1. workspaceRequestTransport.test.ts、workspaceLoopback.mjs/d.mts：非DOM真实HTTP合同只在该文件使用Node原生fetch/File/FormData/Blob/AbortSignal。最小hoisted window/document保留Axios browser FormData头分支，Map存储只提供原拦截器所需方法，globals均恢复。保留原3项multipart字段/boundary/捕获Workspace、精确二进制字节、取消拒绝；新增服务端取消断连观察、HTTP409与意外断连必然拒绝两个反例。没有mock网络、替换生产函数或抑制console。
2. useTeamRuns.test.ts：默认分页API单测仅增加文件局部订阅公开端口fixture，原两次分页参数/游标/去重/旧API未调用断言保持；fetch spy只观察无外发，订阅/释放各一次，整个scope与spy finally释放。其余12项经固定Prettier格式化后的源后缀逐字节相等，独立TypeScript AST比较一致。没有改变生产默认依赖或实际SSE协议测试。
3. test/workspace-request-transport.test.mjs：既有legacy runner自动纳入，子进程完整执行上述两份18项测试，等待真实退出，检查signal/status、JSON成功/无失败/无pending、六个关键标题、实际stderr无socket诊断。既有runner配置/依赖/超时不变。

HappyDOM Fetch取消销毁底层HTTP后onError仍console.error。旧3项实际exit0但stderr含ECONNRESET，普通console spy无法观察初始化时持有的另一console，故旧子进程回归真实exit1。纯Node第一轮原boundary断言捕获Axios头规则差异（4通过/1失败），增加原调用方事实后五项通过，没有放宽原断言。初次vue-tsc真实TS2304 setImmediate，改用setTimeout(resolve,0)且扩展try/finally后通过。

第一源码完整门禁/钩子仍有另一socket诊断，未以窄绿色假称全套修复。仅/tmp临时Fetch.onError观察调用原方法定位到分页单测意外真实localhost SSE；两次临时setup加载失败不计门禁，修复临时fs allow后诊断运行1020通过并定位。原分页fetch观察断言12通过/1失败证明缺口；订阅fixture后相关38项及18项子进程均通过。临时诊断配置未进入项目或改变正式runner。开发中根pnpm版本拒绝、重复UI路径写入失败和未修复时测试红例均如实记录在计划/日志。

## 最终检查

- 初始/最终dev：第一 d_yfpopa/abbtqumv，第二 cy_pw3qs/po656kpe 均SCAN_PASS，不是提交授权。
- 第一源码精确门禁：staged fmhp1t0y PASS/submission_ready=true、hook xov1mk_5 PASS/submission_ready=true，匹配第一tree；完整UI1020、Node5项、固定格式/lint/vue-tsc/ID精度和enterprise/classic两构建通过，但历史该次仍有分页socket输出。
- 第二源码精确门禁：staged a7_llqco PASS/submission_ready=true、hook e3lqmpjq PASS/submission_ready=true，匹配第二tree；完整UI1020、Node5项、固定格式/lint/vue-tsc/ID精度和两构建通过。最终完整Vitest日志无socket hang up/ECONNRESET。
- 相关4文件38项：5真实HTTP、13分页协调、18 SSE协议、2事件ID；子进程1项独立完成；类型与两文件零警告lint通过。Java/cost-tool按纯前端影响规则NOT_APPLICABLE，不复用旧后端结果冒充本批已测。
- 独立native首次提出类型/spy恢复两项，修正后限定四文件无剩余缺陷；第二限定分页/子进程审阅无新缺陷并独立重跑分页/SSE/child/lint。LSP不可用，额外裸checkJs缺Node类型非PASS；本地TypeScript AST差异比较不冒充MCP AST门禁。

## 限制与回退

维护者控制面批准仍PENDING，正式46项AC全部NOT_RUN，真实浏览器CORS/角色/两主题窄屏、SSE网络端到端、取消重启/晚到结果业务、真实模型/存量抽样、多方言迁移/备份恢复、远端requiredCI与独立业务QA均未完成。HTTP409拒绝只证明传输错误保持，不证明真实并发CAS；取消断连不证明无二次领域写入。PR保持draft，未部署/合并/改管理或生产数据。

稳定领域DTO/action payload、应用用例拆分、SQL分页、V2单写迁移与AQ07控制面审核仍待。回退只恢复本批测试/fixture及对应证据，会恢复测试噪声/分页单测意外外发；无持久化或协议迁移。原项目WIP未触碰。
