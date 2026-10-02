# AQ-05 列表摘要类型与概览文案工程核验

源码提交45c3fb7dd8dd7d226e4293b7c0030fa436869c50，tree5177e8a8e3c5f5fa537a5607d1e63c50039c0262；起点617a6c19，原项目WIP未修改。计划先于源码，原稀疏列表HTTP adapter/汇总7项通过。

PresalesProjectSummary承载原共享元数据，完整Project继承它并要求业务集合；ProjectPage、portfolio、dashboard和工作台列表只消费摘要。后端原列表移除12个集合，前端不补造空明细，未知扩展/string ID/可选计数与原请求wire保留。仓库投标移交列表消费者只取id/name，兼容。完整JSON runtime validation仍未完成，本片不声称历史unknown已验证。

触及概览压缩模板格式化产生UI-001：原20对中英文逐字移入既有presalesMessages/useI18n，跟随宿主locale；CSS/事件/加载/失败状态保持。新增组件实际挂载合同覆盖两语言及切换、字符串ID点击、刷新/阶段事件、loading隐藏旧数值、错误隐藏汇总。组件挂载不是浏览器视觉验收。

最终定向10文件155项、vue-tsc exit0；正式暂存mateclaw-quality-bakdbli0与正常commit hook mateclaw-quality-sarqtu69均PASS/submission_ready=true，对应相同source tree。UI852、lint、固定Prettier check、typecheck、ID精度、Node、enterprise/classic构建均PASS；本片无后端/控制面变更，Java按固定规则NOT_APPLICABLE，不能沿用历史6007冒称本批Java已跑。dev最终mo0gptqp SCAN_PASS，独立代码审阅及删冗余接口增量复审均0缺陷。

失败未掩盖：首个根目录pnpm版本不符、首个formatter遗漏固定config、初次dev内联i18n指纹FAIL、完整门禁_1zpyw3r空继承interface lint FAIL。分别改为UI目录pin版本/显式config/原文案入命名空间/删除Metadata中间层；未改依赖、baseline、门禁或旧断言，全部候选重新门禁。日志、来源/输出SHA和报告见manifest；LSP plain tsc不解析Vue导致TS2307，正式vue-tsc通过；AST工具未安装，不宣称其通过。

回退恢复Summary/Project/Page及三个消费点类型与原概览文案，无数据库操作。完整payload/项目DTO、SQL分页、V2/多方言迁移、真实浏览器两主题/窄屏、后台/模型和业务QA仍待完成；正式46项AC保持NOT_RUN，required CI强制生效未验证。设计ADR-AQ-024仍Proposed；本地审阅不是业务签收或合并授权。
