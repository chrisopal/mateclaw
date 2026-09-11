# T07：统一检查与发布门禁

已完成代码、相关回归和本地持久化运行验收。基线 `35b06cdc`。不包含 T08 知识库应用及 T09 实体确认。

## 本批行为

- 统一模型结构、模型逻辑、业务规则定义、参考资料四项检查，持久化报告 ID、输入摘要、时间和状态。GET draft/validation 支持刷新读回。
- 报告固定草稿版本、OWL 文档、导入锁、策略、推理配置及资料实际内容/审核状态。检查期间不持有长事务，保存报告和发布时重新授权、加锁并核对输入。
- 未检查、未完成、失败或输入变化均不能发布。来源必须 CURRENT + REVIEWED + ACKNOWLEDGE，且审核对应准确资料摘要。保留历史不能绕过当前资料检查；无绑定不强制要求补齐来源。
- 发布成功的同操作重试先读取已完成结果，不因草稿已消费而失败。
- UI 合并独立逻辑检查入口为统一报告，业务对象名称优先，问题详情折叠，超时提示改为业务语言。服务端 Tool 采用同一检查，仍保留人工发布。
- 新增 H2/MySQL/Kingbase V209 报告表。报告只关联本体，丢弃草稿不删除历史报告。

主要文件：OntologyApplicationService、OntologySourceReviewService、OntologyDtos、OntologyController、DraftReasoningService、OntologyAuthoringTool、三份 V209；前端 ontologyApi/types、useOntologyDraft、validationReport、ValidationPanel、PublishDialog、OntologyEditor 及对应测试。

简化：删除编辑器重复的独立逻辑检查区，统一准备状态判断；复用现有推理 worker 和来源审核读取，不新增依赖或第二套本体存储。

## 验证

- 后端相关 56 项最终通过（按测试类去重；审查修复后重跑来源/门禁/报告共 18 项）。覆盖精确输入、检查中变化、256 草稿版本、权限、历史来源、部分审核状态、丢弃、并发和幂等重试。
- 审查发现仅 ACKNOWLEDGE 不足：新增 PENDING/STALE + ACKNOWLEDGE 红灯回归，修复为同时要求 REVIEWED 后转绿。
- 前端 27 文件、139 项通过；构建含类型检查、修改文件 ESLint、git diff --check 通过。构建仍有已有大 chunk 提示。
- 本地 18109 后端已更新；5189 Vite 使用当前前端。持久化库停服备份、迁移、重启后报告回读成功。
- 真实 HermiT 验证一致、不可满足类别、整体矛盾和个体断言矛盾。真实浏览器完成来源待确认阻断、填写理由确认、检查、修改失效、再检查、人工发布；发布版本独立 API 回读成功。
- 390—2560 视口和深色检查；可控浏览器网络超时与恢复。详细控件结果和复跑方法见 output/ui-acceptance/2026-09-11-t07/coverage.md。

## 边界及下一步

业务规则检查定义合法性，不代表实际业务覆盖或实例样例均满足。原生 MySQL/Kingbase、生产部署、全站可访问性未验证。本批不宣称新的完整真实文档→智能体建模→发布链路已重跑。

下一任务 T08：发布后知识库应用与抽取入口，绑定不可变模型版本，并复用现有知识库/抽取链路；实体正式确认属于 T09。
