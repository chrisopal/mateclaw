# AQ05 / AQ06 最新 handoff 冻结读取修复计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，固定 origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；保留现有 WIP。初始 dev 22sodt3d SCAN_PASS / submission_ready=false。沿用工程门禁与 ai-slop-cleaner 的先测试、再消除重复流程。

发现：GET /projects/{id}/handoff 使用当前 baseline/solution/clarifications 重组交付，并重新运行当前 releaseGate；GET /projects/{id}/releases/{releaseId}/handoff 已读取冻结 snapshot。前者可能把发布后澄清加入旧发布或因后来基线变化拒绝已发布版本，违反成果/handoff 冻结不变量。

先在真实 CREATE_RELEASE→APPROVE_RELEASE→PUBLISH_RELEASE 的现有 Integration 流程添加“发布后澄清不得进入最新 handoff”的 RED 断言；另加真实 HTTP/H2 的最新与确切版本一致、最后 PUBLISHED 选择、不重建缺失 snapshot、缺少发布/坏成果、授权拒绝和冻结行不变。保留所有原断言，先观察失败再改产品。

限定产品修改为 PresalesService 的两个 handoff 读取方法及一个内部复用方法：两条路线均先 get(scope,id)，保留当前 actor/Workspace/当前和冻结来源授权，沿用最新 route 的最后 PUBLISHED 选择和无发布错误、确切 route 的找不到/未发布错误。复用既有 verifyArtifacts、snapshot 类型检查、deepCopy、releaseId；删除最新 route 的当前项目重组和历史读取时的当前 releaseGate。发布/批准时 releaseGate、artifact 下载/预览、事务/receipt/hash、发布 writer、旧迁移均不修改。不新建通用框架/依赖/表。

这是明确的行为修复：最新交付改为冻结发布事实，包含既有确切发布 schema1 的来源/历史标记和 releaseId；UI decodeHandoff 的 schema/Workspace/project/record/扩展契约须复核。缺 snapshot 的历史 latest route 将与 exact route 一样 409 HISTORICAL_SNAPSHOT_UNAVAILABLE，不自动 backfill 或从现状重建。旧明确版本回读保持原字节和错误优先级。验证不自动关闭正式 AC。

验收：真实 RED 与刻画记录；相同用例 GREEN，全部 Presales/授权/ArchUnit、既有 Bidding handoff 消费者适用回归； scoped 格式化与只读检查分开；每片 dev；独立只读评审授权/兼容影响；归档 exact source/XML/log/tree。可在独立空 MySQL 上复跑实际发布流，不能把此前 17 项或旧源码摘要冒充本片证明。Kingbase、真实历史样本/Office、UI浏览器、正式46AC/维护人/远端CI保持未验证。

首次 RED：7 个读取合同 5 failure、真实发布流 1 failure。合同中的跨 Workspace 请求使用 viewer，它不是另一 Workspace 成员，真实状态是 403；原 404 期待属于新增夹具错误。归档辅助脚本先出现 SyntaxError，未执行修正，随后原样重复 RED；两次日志均保留，XML 对应后一次原样重复，不混作首次 XML。现在只纠正新增夹具期待为真实 403，不修改产品授权；其他冻结读取失败仍待修复，先以纠正后的相同用例重复 RED。

补充 feature 开关合同：两条历史读取在 semantic 变更关闭时应一致；新 CREATE_RELEASE 仍 409 SEMANTIC_DISABLED。该行为变更来自复用已有 exact-release 读取，而非放宽发布门禁；测试以 finally 恢复共享属性。正式模块组合验收不由单例测试替代。

MySQL 后续验证使用独立空库与缓存 mysql:8.0 镜像：外部拷贝仅更改两份测试的类名，并添加隔离 DynamicPropertySource；现有实际 g1 创建/批准/发布流程逐字保留，只选择这个方法；8 项读取合同全部运行，另增加真实 MySQL/Flyway 身份、pending/validate/列宽断言。空库保护在外部 launcher 执行一次，两个 Spring context 不重复要求已迁移库为空。保留既有 Wiki/PAT/I18n mocks；不写当前运行项目数据库、不改 POM/旧迁移，不把 MockMvc 当真实浏览器/网络 HTTP。记录 source/class SHA 前后、完整迁移历史、每项 JUnit 状态、隔离容器与凭据删除结果。
