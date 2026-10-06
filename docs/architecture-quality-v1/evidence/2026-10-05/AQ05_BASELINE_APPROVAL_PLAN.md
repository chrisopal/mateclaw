# AQ05 基线批准职责拆分计划

起点 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `2yqgwtm_` SCAN_PASS，submission_ready=false，工作树 `3a548477b4d184f8b21fb7eb22f01a4bbe7b38cd766bb2bdd1ac5c6072ea6c65`。上轮生成边界是已验证进展，整体目标和既有WIP保持。

- 拆分整个基线批准准备职责：reason/需求/澄清/范围/图绑定/ontology/可信声明/精确版本/来源摘要及引用快照；不把它拆成多组单函数工具。采用包内 PresalesBaselineApproval，复用现有公共语义服务与来源授权，不加Spring bean、表、依赖或迁移。
- PresalesService保留admin授权、模块可用性、幂等/CAS、事务与saveItem/回执/revision等持久化。批准准备器只读取公共事实并在全部条件通过后构造批准载荷，不拥有提交权限；领域拒绝及来源拒绝仍由现有应用边界映射原错误码。
- 先在原生产实现跑扩展刻画：多缺陷拒绝顺序、澄清三要素、重复图绑定与ontology、第二需求失败不残留部分批准、引用/证据顺序及内部批准仍UNCONFIRMED；实际HTTP/H2已有admin/来源撤回/摘要漂移/批准发布回归继续运行，并加强失败前后三表一致断言。
- 保留JSON字段插入顺序、旧整数字符串兼容、原异常状态/码/消息；无客户确认升级，无来源静默降级。现有getIfAvailable模块检查仍先于reason与领域判断。
- 范围：Service、新BaselineApproval、ReleaseSnapshotContractTest、IntegrationTest及本片文档证据。先回归原实现再搬移，随后实际Java/ArchUnit/Spotless/dev与独立技术审阅。运行检查期间不编辑。

选择有具体职责的包内协作者，避免无界继续增加总服务。暂不改其余读写用例和未确定V2 schema；Stable DTO、V2及正式AC仍待完成。回退仅恢复原基线方法，不涉及数据回退；不能回退前批精确修订/来源/冻结安全修复。无完整commit门禁通过前不提交。
