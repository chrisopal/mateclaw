# AQ-05/09 项目概览与任务活动职责计划

起始HEAD545ad446aa1fd5634dd471145daa8f7ac7471ba4，独立工作树干净，origin/dev基线ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；dev b_f7g7tb SCAN_PASS。原项目WIP不触碰。

## 范围与设计

项目概览中的context/baseline状态及员工任务活动仍混合展示和router/取消/采纳/证据/预览执行。提取PresalesOverview，以原PresalesProject/Task/Record/Presentation DTO和窄typed intent连接页面；页面保留生成、取消、采纳、导航和来源预览全部scope/权限/CAS/lifecycle守卫，不新增请求/store/依赖或批准权。沿用原i18n/label/安全插值与打印策略，空态/表格原fallback/任务状态/queue/truncated/S1/S5/S6采纳条件不变。

任务CSS归组件scoped样式，复用common和原solution样式；页面保留focus-visible全局deep与实际预览CSS。原selector/declaration/media合同必须保持，编译不代替视觉验收。限制在页面、一个组件、相关CSS、组件刻画和只读证据/ADR/台账；不改门禁配置/旧迁移/业务接口。

## 测试先行和失败模式

生产前新增overview刻画：最新context/baseline、安全文本、truncation/queued提示、输入快照fallback、允许skill采纳与草稿、只读禁用/locale、精确字符串task取消和版本接受。运行原59项和新增用例，再编辑。迁移后全售前、vue-tsc/nonfix lint/固定format、CSS合同、dev、独立边界审核、精确完整commit与正常push/PR回读。迟到取消/预览/范围守卫已有回归保持。组件只发意图，不能绕过父级授权；多根CSS不能依赖隐式穿透。

## 剩余与回退

完整P0、PresalesService职责及V2迁移/正式QA/远端强制CI仍未完成。该片按持续批准的售前结构整改推进，不替代它们。回退page接入和组件样式，无数据库/生产操作；真实浏览器主题/窄屏/角色/并发仍独立验收。
