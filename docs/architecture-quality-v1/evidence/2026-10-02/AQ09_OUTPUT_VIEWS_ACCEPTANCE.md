# AQ-05/09 方案与评审成果展示工程验收

起始 HEAD cc9596b91932029145b80b0f433b135f23f58351；原项目工作树未触碰，本批仅在独立 architecture-quality 工作树实施。计划见 AQ09_OUTPUT_VIEWS_PLAN.md。

## 完成的职责边界

PresalesSolutions 负责版本倒序、需求响应/章节覆盖、适配方式、基线说明、方案正文与两个比较选择；PresalesOutputs 负责独立评审、成果版本/文件/状态及操作入口。输入使用领域 DTO 和既有显示标签，输出具名 typed 意图，不含 API/store/router/确认/事务逻辑。页面保留写入/来源修复/生成/审批政策、确认、scope/session、CAS、幂等回执、HTTP 和迟到结果拒收，分别接回原 S5/S7、编辑、证据、下载、批准、发布及 handoff 用例。展示状态不替代服务器授权。

两个比较值仍由页面持有，通过 required named models 绑定，保留原 load 只清 compareId 的行为。覆盖与对比计算从页面原样归属方案展示。原安全文本插值、排序、状态禁用、下载 draft/preview/files、findings/旧issues回退、版本字符串、所有 i18n key 保留。页面3108→2642行；两个组件236/132行，不以搬整个页面聚合代替职责设计。

父 scoped CSS 无法作为多根子组件的可靠样式来源，因此共享原 section/toolbar/help/text/action 样式源供页面与两个组件 scoped 引用；方案专有样式归方案组件。output-views-css-contract.mjs 对起始提交逐项核对：98项 selector/declaration/media合同一致，三组件 scoped CSS 编译通过。主题 token、max-width768/600 媒体条件及原更高优先级页面规则保留。该源结构证明不等于浏览器视觉验收。

## 实际检查与审核

旧实现新增4个真实 Vue/ElementPlus刻画，连同49个既有用例53/53通过：空方案/生成禁用、版本/覆盖/安全文本/精确字符串ID draft意图、两种审批能力下三种成果状态的下载/预览/批准/发布矩阵。首次新增下载断言误把 API 第六参数当 signal；核对原 download 实现五参数后修正新增刻画，保留首次FAIL日志。生产代码未为该断言修改。

迁移后全售前七文件123/123通过。独立评阅提出比较模型/子组件locale需直接交互验证，补真实ElementPlus两选择操作和方案/成果中文切换；最终组件53/53通过，原49项断言无改动。最终vue-tsc、nonfix lint、固定Prettier check及dev结果/命令摘要见output-views-test-results.json和原始证据。完整 staged tree/正常commit/push的实际结果与tree在PR #5记录。

最初corepack pnpm12.6与项目12.4.2不匹配，使用已安装Node直接执行既有包入口完成定向验证，不更改依赖或配置；该首次命令日志已被后续格式命令覆盖，只有工具输出观察，不把重建文字当原始日志。完整门禁继续由正式runner执行。一次根目录直接ESLint未找到UI配置，改在mateclaw-ui执行原配置通过；失败日志保留。所有实际工具配置/runner/依赖/权限/迁移不变。

独立workbench_confirmation_audit对模板、事件、比较状态、计算、CSS范围和回归审阅，APPROVE bounded display extraction，无阻断。其诊断未见新增错误；plain tsc不能解析旧Vue import的局限仍在，实际vue-tsc和正式树门禁为提交证据。原UI和工程审核不升级 AC-30/正式QA。

## 剩余与回退

编辑选项/会话、任务/来源及其他展示职责、用例协调仍需拆分；PresalesService聚合、V2对象/单写/迁移、角色/API/真实浏览器两主题和窄屏、并发409/重启、真实模型、多方言恢复、维护人/QA签收和remote required CI尚未闭合。回退这两个组件/样式的页面接入可恢复原结构，无数据迁移。本片不宣称整体AQ09或架构整改完成。
