# AQ-05/09 售前编辑业务规则工程验收

起始HEAD `36a46900c4e41a099f5d87e5382469150972ea8b`、base origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；独立工作树起始干净，原项目WIP未触碰。计划见 AQ09_EDITOR_RULES_PLAN.md。此片推进整体售前工作台职责结构，不宣称完整AQ-09/P0或业务QA完成。

## 职责与保持行为

页面此前同时管理11类表单默认值、JSON历史副本、基线需求对齐、必填与来源校验、数据转换/动作映射，以及Vue界面、授权和异步提交。现将完整编辑业务规则放入域内shared/editorSubmission.ts：初始化、基线过滤/响应对齐、校验、project metadata、employee与九类命令。返回invalid/project/command的判别类型；无Vue、store、HTTP、router、i18n或通用框架依赖。

保留JSON副本语义及unknown历史扩展、所有字符串ID、最新baseline默认值、方案编辑删旧id但保留修订内容；已存在空response数组不擅自初始化，baseline变更复用匹配response并补UNHANDLED。已知baseline缺references仍为空，未知baseline仍回退全部需求。必填先于ANSWERED校验；校验用trim但存储原空白字符；ANSWERED仍需answer与source。

fitgap仅转换脱离表单的payload，按逗号分割/trim/剔空，保留重复ID、未知扩展及顺序，删除副本evidenceText；原草稿不动。employee仍只发送agentId，可解绑为空；受限material只kbId/graphId/role，常规material保留原payload。project metadata维持原六字段与agentId空字符串默认，原完整data仍用于operation receipt。动作与ANSWERED继续员工标志在纯模块确定，页面只有成功且当前scope/session仍有效才继续。

页面保留canWrite、sourceAccessRestricted修复allowlist、scope/session、expectedVersion、receipt、409/conflict、dirty/discard、options/route/Workspace及create/update/navigation。纯映射不授予权限、不决定CAS/事务/批准；HTTP/wire/依赖/后端/迁移和门禁配置不变。既有template/CSS及i18n文本未改；页面3237→3108行（减少129），模块192行包含明确类型与规则。没有通过搬整个聚合或新增框架掩盖剩余职责。

## 实际证据与独立审核

生产修改前新增三项真实Vue/ElementPlus刻画：空必填不调用create、项目metadata原空白值/默认ID/receipt、材料原默认role与精确来源ID/命令version。旧实现49/49通过（既有46项全保留）；规则接入后49/49仍通过。

纯模块37项验证全部11初始化、独立嵌套副本、历史扩展、employee最小字段、方案新identity/空响应、baseline过滤/匹配对象复用、全部必填、项目metadata/原数据、受限材料、ANSWERED顺序/来源、fitgap证据及六类其余动作。最终售前七文件119/119通过，零失败/skip；包含既有轮询/来源查看/确认/修复/版本/409/员工和澄清合同，组件API仍为受控替身。日志/源码hash见editor-rules-test-results.json。

最终vue-tsc、四文件nonfix ESLint --max-warnings=0、固定Prettier check退出0；dev en9opzre SCAN_PASS只证明扫描与门禁自测。首轮类型问题来自readonly test tuple、project ref的undefined和返回PresalesRecord过宽丢失required id；分别用typed satisfies、接受原可空输入、精确project.requirements返回契约修复，没有改配置/DTO/断言以凑通过。失败日志保留。提交及推送需真实完整精确树工程门禁，最终报告/树在PR #5记录。

独立workbench_confirmation_audit审阅四份代码/测试，无阻断，确认原defaults/history/validation/whitelist/mapping/receipt保持和页面控制边界。文件诊断新模块/测试/页面零错误；plain tsc对旧组件测试Vue import有限制，实际vue-tsc通过。原断言不改，新增三项装配内用例和37纯合同，不改runner/依赖/门禁。该工程审阅不替代正式维护人与业务QA。

## 剩余与回退

真实角色/服务端权限、真实浏览器enterprise/classic/窄屏、真实409并发、异步重启、多方言/生产、模型与独立QA、远端required CI仍NOT_RUN。组件与pure mapper不能证明后端批准/来源权限或生产事务。本片只搬移完整编辑业务规则；页面仍3108行，显示组件、编辑选项/会话、用例协调、PresalesService聚合及V2单写迁移仍待推进。

revert本片恢复页面内规则，无数据库变化。后续调整规则需同时复核mapper合同与真实页面/后端权限、CAS/receipt和来源修复，不能以typed intent替代执行授权。
