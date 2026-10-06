# AQ-05 前端变更请求工程核验

源码提交2a509fecb19dde184535ae7f91eaad10b6af153f，treee759075674d3594020cb16f720402aba2b2b35ae；起点2345f23a，工作树原本干净，原项目WIP未修改。计划先于改动，原API旧24项请求/Workspace合同通过。

presalesApi移除5个object输入和开放列表params：VersionedMutation明确expectedVersion/operationId，ProjectWrite保留原六个可选metadata字段和partial update，Create版本必须0；Command支持原16action，payload为Record<string,unknown>明确未验证；Generate明确S1-S8/taskGoal；Cancel仅operationId；ListQuery为q/status/ownerId/stage/page/pageSize。编辑映射satisfies已知action，工作台command/openGeneration/generation采用同一类型。create分支显式覆写0，原该分支body就是0，属性插入顺序和receipt原输入保留。

新增adapter合同逐字比较原JSON、5类mutation路径/method、encoded project/task、capturedWorkspace、字符串大ID、未知扩展、16action、409码/消息/原输入及列表signal/filters。编译期合同证明漏CAS/operation、metadata数值ownerId、创建非0、未知action/skill与数值query不可赋值；取消仅声明operationId。unknown payload中的数值ID/任意值并未因此获得校验，不把前端类型当服务端授权或完整schema。取消请求不误加项目CAS（后端Cancel本来只有operationId）。

最终售前11文件178项与vue-tsc/lint exit0；dev paxgp6yj SCAN_PASS；独立限定代码审阅0缺陷，核对后端全部16action及S1-S8。暂存mateclaw-quality-jhezzkxs与正常commit hook mateclaw-quality-psvp6oco PASS/submission_ready=true，同一source tree；全UI875、固定format/lint/type、ID精度、Node、enterprise/classic构建PASS。Java按既定影响规则NOT_APPLICABLE（本片无后端/控制面变化），没有沿用历史Java结果冒称本批执行。

真实失败保留：新负例expectTypeOf<{}>被no-empty-object-type拒绝，改为准确空JSON Record<string,never>保留同一拒绝断言，不禁用规则、不删断言；全部候选重新检查。LSP plain tsc4文件零诊断，不足以检验Vue模板；实际vue-tsc通过。AST未安装，文本有界回退无目标模式，不能称AST PASS。日志及来源/输出SHA、精确报告见manifest。

没有改运行时请求helper、receipt算法、权限/来源、批准/事务/结果接收、数据库、UI文案/样式或旧测试。回退恢复API/调用类型和create分支源码，无数据库操作。完整各action payload/项目DTO、runtime unknown validation、SQL分页、V2/多方言迁移、live transport/真实浏览器/后台模型及业务QA未完成；正式46项AC仍NOT_RUN，required CI强制生效未验证；ADR-AQ-025仍Proposed。
