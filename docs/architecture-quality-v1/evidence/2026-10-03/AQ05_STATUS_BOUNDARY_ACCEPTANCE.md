# AQ-05 历史状态展示边界工程验收

本片完成有限展示词表、独立 UnknownStatus {raw} 与缺失状态；不是完整领域 DTO、对象级状态机或正式业务验收。原47对状态/阶段/来源/优先级等文案保持，接宿主 i18n。明确行为变化：未知非空值增加未知状态前缀，状态文案跟随宿主语言；empty/undefined仍 —。known分类不成为批准/执行allowlist，历史wire、ID、原对象与冻结成果不改。

起点 b535c6d1970e93e491a3227a88abab5a052dafb0；base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。源码提交8cd4cddd978d0b4254b89c3070ef924b4f02c594，tree10855a6082a43f7d8963cc60a23491f4f25c80a3。

修改文件：shared/status.ts 39行纯分类/展示（KnownStatus/UnknownStatus/MissingStatus判别联合、Object.hasOwn、精确raw）；shared/messages.ts 集中47对标签及未知插值；Workbench只增加import并替换 stateLabel，删除每次调用构造的字典。新增status.test.ts和3项Workbench合同，旧62测试源前缀字节保持。计划同目录AQ05_STATUS_BOUNDARY_PLAN.md。没有新增依赖、SQL、迁移、后端事务、共享层或权限行为变化。

## 证据

- 原工作台62项通过；添加3项后的旧实现63通过/2失败，分别证明未知标记与locale状态更新缺口。
- 状态纯合同59项：两语言47标签原字节、精确未知值/空格/大小写/prototype属性、插值非消息执行、缺值、域UNKNOWN已知、有限类型约束。
- 新增3项真实Vue+ElementPlus挂载：已知兼容、未知HTML安全文本及无update/command、host locale与raw保留。它是组件工程证据，不是真浏览器/主题签收。
- 售前14文件321项通过；五文件ESLint零警告、vue-tsc、固定Prettier配置通过。完整门禁UI1018项、Node测试、enterprise/classic构建通过。
- dev初始goo3vjve/中间jgrlg090/最终m3551k64 SCAN_PASS；实际源码精确staged5cdh62d0 PASS/submission_ready=true/tree10855a6082a43f7d8963cc60a23491f4f25c80a3；正常提交钩子1vibwo9w PASS/submission_ready=true，匹配同一源码tree。。
- Java/cost-tool按frontend-only固定影响规则NOT_APPLICABLE；没有把以前Java结果当成本片已测。
- 独立native只读五文件审阅COMMENT/无发现，限定59+3项及lint通过；五文件LSP Transport closed，没有LSP/AST PASS，实际vue-tsc通过供类型证据。维护人/QA未签收。

所有原始输入日志SHA和归档副本SHA、source文件SHA及精确report见status-boundary-test-results.json与status-boundary-tests。归档只清理显示副本行尾空格/末尾空行，原输入SHA保留；JWT/Bearer/生成密码敏感值遮蔽计数见manifest。

开发操作记录：最初计划写入用了UI工作目录相对root路径，未写文件；补绝对root后继续。初次显式format漏固定配置导致噪声，固定配置及HEAD只读原测试前缀恢复后重跑check，不改旧断言。根目录pnpm命令曾因Corepack12.6.0/项目12.4.2拒绝执行format，改为已安装本地Prettier CLI并明确固定配置；正常门禁依旧使用原pnpm并实际通过。完整Vitest日志包含socket hang up/ECONNRESET诊断，输出来源尚未定位；该次实际exit0、1018通过且门禁PASS，未抑制异常输出/调整测试或门禁。此诊断不据窄检查声称已修复。

## 风险与回退

未抽样真实存量数据；未执行真实角色/两主题窄屏浏览器、运行中撤权/重启/live model、多方言迁移/备份恢复、客户验收。稳定Requirement/Clarification/SolutionRevision/GenerationTask/Artifact/Handoff及typed action payload、SQL分页、V2单写迁移、AQ07控制面独立维护者审核和远端requiredCI仍待完成；正式46项AC全部NOT_RUN。将混合展示词表作为领域status批准规则是错误用法；未来新增枚举需单独领域契约验证。回退恢复本批源码；无持久化操作，wire/幂等输入不变。PR继续draft，不合并/部署。
