# AQ05/AQ09 售前展示契约收尾计划

起点 HEAD `fdd5d372e886d7fbbf7ab01c374fe952ea926ab3`，tree `29401839d1330cee291daf39d35c27af04b48f0f`；dev base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。初始工作树干净，dev `1x21fx0z` 为 SCAN_PASS，非提交许可。上一批发布事务改动已提交推送，本片是独立的展示契约收尾，不修改该提交或其远端检查。

## 有限范围与退出条件

原规范要求有限状态区分历史未知值、产品文案使用宿主 i18n（ARCHITECTURE_SPEC 第 8/10 节）。独立只读审计确认，当前页面/composable、应用服务/策略/仓储的主职责边界已建立；不以文件行数、全部 Java JSON 清零或继续新增转发类作为退出条件。

1. 完成现有售前展示消费者的对象枚举边界。复用 shared/status，不再用全局翻译词表把其他对象的合法值判为本对象已知。覆盖项目 stage/status、资料 role/status、需求 priority/scope/origin/客户确认、方案 status 及其范围展示。按现有生产和历史读取契约核对有限值；不借显示逻辑新增流程、授权或存储准入。已有七组 domain 判断保留。
2. 将 employeeIssue 的八组中英文逐字移入现有 presalesMessages，通过 t 跟随宿主语言。请求失败保存原始错误信息，在展示边界翻译，以保证失败后语言切换也更新。未知错误码原样返回，不新增错误服务、组件、依赖或全局语言状态。

本片完成后关闭上述范围的代码结构欠账，保留明确的验收事项。AQ06 对象/修订单写迁移、生产历史修复、真实多方言/模型/Office、AQ10 性能、正式 QA 与远端强制不从这两项推导完成，也不夹带到本片。

## 验证顺序

- 先扩充现有 status 和 Workbench 测试：合法值、跨对象已知词、未知原文（含空白/特殊字符）、缺失值；验证真实页面/子组件消费域参数，而非仅测新增分类函数。
- 员工错误验证八码原文、英语到中文再切回、未知码回退；页面沿用当前作用域、权限和会话夹具。记录旧实现的真实行为 RED，不能把编译失败当业务红例。
- 最小实现只改现有模块和消费者；不重写历史 JSON/wire，不改发布字节、请求 hash、权限、事务、数据库或执行行为。保留既有明确的展示回退并记录，不把缺失历史字段写回。
- 售前回归、全 UI 类型/测试/精度、适用格式/lint和双模式构建；视图文字和语言切换补实际渲染证据，模拟夹具与正式业务验收分别报告。
- 独立技术审阅后执行 dev，精确暂存树正常 commit/push 门禁及当前远端 CI。任何未运行检查保持 NOT_RUN，不修改 baseline、规则、钩子或断言以规避失败。

## 回退

仅恢复本片前端源码/消息/测试，无数据库迁移；回退会重新暴露跨对象状态误识别和员工错误不随宿主语言更新的问题。没有部署、生产数据或仓库管理操作。

## 实施与独立复核结果

- 页面/子组件明确传入对象域；九个新增显示域只采用现有已实现合同。materialStatus 仅 WITHDRAWN，未把项目 ACTIVE 借给资料；solution 仅 DRAFT，保留缺失值 DRAFT 显示回退，未新增评审流程。
- 八组员工错误逐字迁入 messages；删除无引用 locale 模块及 executionSession 的翻译回调参数。会话保留原始错误，页面、台账、员工配置及生成弹窗在渲染时翻译；未知码及 constructor/__proto__/toString 原样显示。
- 独立复核发现请求失败时缓存译文的缺口。补真实页面 employees/generate/cancel 三条失败路径 en→zh→en 断言，修改前 3/3 因旧译文失败，修改后全部通过；原 scope、锁、冲突、回执和权限断言保留。
- 初始展示边界 RED：19 个选择案例中 10 失败、9 通过。最终售前回归 `pnpm exec vitest run src/features/presales --reporter=json`：563/563，0 failed/pending；类型检查 `pnpm exec vue-tsc --noEmit` exit 0。dev `_rg09hy5` SCAN_PASS。格式使用 `.quality/prettier.json`，已去除本轮误用默认格式导致的无关改动。
- 独立技术复核 `ui_contract_exit_review` 为 COMMENT，前两项问题已解决，无新的功能性发现；不是维护人/QA 正式批准。
- Chromium 加载真实 Workbench/子组件，API 为明确标识的模拟夹具：enterprise 桌面验证失败后语言切换、WITHDRAWN 禁用操作、priority=RUNNING 与 solution=ANSWERED 的未知原文；classic 390×844 验证错误切中文。生成 7 张截图。首次请求 favicon 404 与宿主 intlify experimental warning 已区分，未当作业务故障；没有真实模型/服务器/权限验收。
- 证据归档 `presales-ui-contract-exit`：27 文件，SHA-256 `642af7162f5ac21974ff547511280fdac27c4e03cecbe87d014ad84c5d92eda6`。保存在本机 `~/.codex/artifacts/mateclaw/aq-cumulative-20261006-fcdteqlz/`；不宣称仓库克隆即可取得本机归档。
- 提交/推送最终树门禁和本片远端 CI 证据将记录到 PR #5 及同目录交付记录；本段不是提交授权或通过证明。上述两项代码已实现，关闭结构整改仍需最终树门禁；其余 AQ 及正式验收按本计划边界分别追踪。
