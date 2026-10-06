# AQ-05 历史状态展示边界计划

起点 b535c6d1970e93e491a3227a88abab5a052dafb0，tree f99b1972d230edd68ff91781b93f86eb583d3950，隔离工作树干净。固定 origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，初始 dev goo3vjve SCAN_PASS/exit0。前一轮实际提交/推送并核对远端，分类为 progress；完整目标继续 active。

问题：工作台 stateLabel 每次调用重建混合字典，使用 Record<string,...> 掩盖有限集合；历史未知值没有独立分类，prototype 属性还可能被误判为标签。ARCHITECTURE_SPEC 第8节要求 UnknownStatus {raw}，避免 Known|string。既有字典同时用于阶段/状态/来源/范围/优先级等标签，必须保留这些已知字面量，不借展示重构改变领域批准规则。

范围：工作台原 stateLabel、同域 shared/status.ts、已有 shared/messages.ts 与专项纯函数/真实 Vue 工作台测试。使用有限 known 字面量及 KnownStatus/UnknownStatus/MissingStatus 判别联合；分类不改变 wire，不写回对象，不替代后端验证或授权。已知文案保持原字节并接宿主 i18n；未知非空原文单独标为未知状态，空/undefined保持 —。禁止 trim/大小写归一化、将未知变成 DRAFT/ACTIVE、使用分类批准/执行/过滤、宽化 API enum 或重写历史及冻结快照。不引入框架/依赖/共享层/SQL/迁移/事务。

顺序：先用旧工作台锁已知文案、缺值、未知字符串安全渲染/原对象、locale切换；记录新增要求的旧实现失败。再提取分类及 typed labels，所有原组件通过原 stateLabel 回调接入，保持执行/会话边界。补纯分类判别/类型约束/prototype键/空值/精确raw与双语合同，执行售前回归、类型、修改文件lint/format、dev和精确 staged门禁；独立限定审阅、正常提交/推送及PR回读。检查过程只读，必要显式format作为开发动作。所有正式AC状态保持NOT_RUN，不宣称真实浏览器或业务QA完成。

回退仅还原本批展示源码，无数据操作；风险是未知状态显示文案明确改变，文本使用Vue/i18n渲染不可执行HTML，旧请求/receipt输入保持。后续稳定领域DTO、payload/SQL/V2/多方言/浏览器/维护人签收及远端CI仍未完成。

实施记录：旧工作台62项通过；添加3项后旧实现63通过/2失败，失败为未知状态标记和host locale状态切换。迁移后售前14文件321项、五文件lint、vue-tsc通过。47对旧标签在修改前从原函数捕获，独立 fixture 检查两语言原字节。初次计划写入因UI cwd路径错误未改文件，随后使用绝对root；显式format漏固定config造成格式扩散，使用固定config后恢复原测试源前缀和唯一无关声明格式，最终固定formatcheck通过。既有62项测试源前缀字节保持，未改测试期望/门禁/依赖。开发操作失败如实记录，不作为PASS。最终源码由精确staged门禁重新检查。
