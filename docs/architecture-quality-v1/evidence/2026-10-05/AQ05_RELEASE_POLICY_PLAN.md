# AQ05 发布规则收敛计划

- 起点：HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `wg_92rit` SCAN_PASS，submission_ready=false，既有 WIP 保留。
- 问题：Service 的 releaseGate 混合纯项目规则与外部证据检查，createRelease 再遍历一次评审列表选 reviewId，重复表达同一资格判断。已有 PresalesSolutionPolicy 是草稿/覆盖规则归属，应复用而非再造管理器。
- 范围：Service、SolutionPolicy、既有 ReleaseSnapshotContractTest 的增补及本批证据。先在未改生产代码上刻画基线/覆盖/评审及错误顺序，再提取。
- 设计：SolutionPolicy.releaseBaseline 负责 provisional、coverage、当前 baseline 和条目数量；Service 原位保留每条需求版本→绑定图→ontology→trusted statement→evidence 校验，不能把所有版本验证提前而改变失败顺序。SolutionPolicy.releaseReviewId 在实时证据检查后选最后一个符合资格的 HUMAN_REVIEW，判断 BLOCKER，并返回同一 reviewId 供创建候选记录使用。
- 兼容：同作者、不同 solution、UNTRUSTED_DRAFT 和非人工评审仍不合格；缺 authority 的旧 HUMAN_REVIEW 保持旧语义；BLOCKER 的 ACCEPTED 不等于 RESOLVED。保留原 HTTP status/code/message、审批/发布/候选预览/历史读取调用顺序及 schema。无授权放宽、数据库写法、事务、bean、表、依赖或 hash replay 改动。
- 删除：Service 中仅供 releaseGate 使用的 coverage 转发方法、创建候选时第二次评审遍历。使用现有 Rejected 映射边界。
- 验证：新增刻画在原实现先通过；提取后原测试逐字节保持，运行 Presales/权限围栏/架构/来源及投标消费回归；正式 base Spotless、dev、独立只读审阅。归档实际 Running 类对应 XML。
- 回退及限制：只撤回本片三个 Java 文件差异，无迁移回退。测试/本地扫描不替代正式 AC、完整 commit 门禁、维护人批准或远端 required CI；不重报旧方言测试为本片实测。
