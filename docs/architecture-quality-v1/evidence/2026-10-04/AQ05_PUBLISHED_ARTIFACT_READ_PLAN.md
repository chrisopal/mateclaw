# AQ05 / AQ02 已发布成果读取门禁分离计划

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；保留此前 WIP。开始前 dev wza5r_ew SCAN_PASS / submission_ready=false。本片不处理尚未回答的 hash 旧回执政策。

观察：artifact 与 preview 在 PUBLISHED 上仍调用实时 releaseGate。该函数检查当前 semantic enabled、最新需求/基线、可信语义修订与独立评审；对当前新候选正确，但对拥有完整冻结来源的历史发布，会因新需求/新基线/关闭新发布而拒绝已批准原字节，与上片冻结 handoff 行为不一致。先补实际失败证明，不把观察直接当修复完成。

重要旧路径约束：authorizeReleaseSources 对缺少对象 handoffSnapshot 的记录明确保留旧读取门禁。故禁止无条件删除 live gate，禁止仅凭 status=PUBLISHED 或任意空 object 放行。拟将具有可识别冻结来源/身份的已发布读取与待发布及遗留读取分开；候选、APPROVED/PENDING preview、没有或不完整冻结 provenance 的历史记录保留旧 releaseGate；current actor/Workspace/员工/current及frozen来源授权仍先由 get 执行；冻结manifest/精确成果字节校验仍保留，不重新渲染/回填。最小合法冻结 shape 先做独立风险审阅，与已有生成字段及 reader 合同相符；不接受一个新布尔标志作为权威。

范围：PresalesService 的 artifact/preview 两条读取及一个复用判定；现 PresalesArtifactPersistenceContractTest 追加冻结读取、新候选与legacy阻断、角色与坏SHA不绕过合同；现 Integration g1 实际创建/批准/发布后追加关闭semantic时读取原字节，而 CREATE_RELEASE 仍拒绝，并 finally 恢复属性。原断言保留，RED 后再改产品。无新增依赖/bean/表/迁移，不改来源授权/发布/批准/writer/receipt/hash。

验收：H2真实HTTP RED/GREEN、Presales/授权/ArchUnit/投标消费者回归；scoped显式格式化与只读check分开；切片dev；独立源码与安全边界评审。独立空MySQL复跑同一新增合同、实际g1流程、既有成果来源撤权矩阵，旧结果不冒充当前源码证明。记录源码/class/XML/日志的精确身份，留存遗留兼容限制与外部夹具适配；46正式AC、Kingbase、真实旧数据/Office、浏览器、维护人批准和远端CI保持未验收。

独立设计审阅 COMMENT：没有现成完整 snapshot 认定函数，任意对象会静默跳过坏来源集合。明确在既有 PresalesSourceAuthorization 增加只读结构认定：schema1/Workspace/project/caseRef、release/solution/baseline 身份关联、冻结文件清单、materials/sourceRefs/baseline.references 数组及内部支持的来源结构。合法空数组可识别；不满足就保留原 gate，不补造。该认定仅决定是否沿用历史或候选校验，不替代 get 的当前权限、来源重新授权和 ArtifactReader 摘要验证。暂不改变 SourceAuthorization 原有遍历/拒绝政策。

实际 RED：15 项成果合同中 3 失败（旧基线读取、semantic 关闭读取、摘要错误被 semantic 先遮挡），现有实际发布流程 1 项/1 失败；另外12合同通过。原始源码/XML/日志已归档，产品仍是切片前源码；先定位真实性，再开始修复。

中间 GREEN（390 项与 MySQL 24 项）后独立审阅发现 P1：正常发布可包含 baseline/sourceRefs 未覆盖的 fit-only evidence，跳过 live gate 会漏掉唯一撤权检查。这些中间结果必须保留为未覆盖风险的版本，不能称最终安全通过。补 actual g1 真实 fit-only 原资料→import→evidence→SAVE_FIT_GAP→发布：最初两次夹具分别缺 reason/productVersion，归档后补齐，不当产品漏洞；随后真实 RED：withdrawn files 期待404，实际200。

不缩小为 fit 非UNKNOWN全退回实时gate。改由既有 SourceAuthorization.authorizeReleaseSources 在公共 get 上复核冻结 fit evidence：既有公开 SemanticQueryService 排除撤回/删除/exclusion，接着复核当前员工与source Workspace，限定冻结materials的graph。ObjectProvider 缺bean失败关闭；运行时mutation flag关闭仍保留已创建querybean，启动即关闭的组合不能宣称可读。无fit的合法旧记录保留已有行为；冻结结构认定追加fitGaps与solution.fitGapRefs精确唯一匹配。新增Query provider是已有内部端口依赖，不新增包依赖/控制面开关。现 SourceAuthorizationTest 仅更新显式构造参数，原断言保留。

后续实际回归发现上述 Query provider 方案不满足历史读取：runtime semantic.enabled=false 时 SemanticAccessService 抛404 SEMANTIC_DISABLED，g1期待200失败；真实日志/XML归档为 fit-enable-failure。不会关闭或绕过其开关。复用既有无条件 SourceGovernanceReadService/Repository 增加持久化 availableEvidenceSource(scope,graph,kb,evidenceId) Optional 读取：graph/KB Workspace及绑定一致、evidence/snapshot、排除撤回/exclusion、raw同KB未删除。该端口仅提供事实，caller先get角色/current与frozen材料权限、后既有来源/员工授权；不公开HTTP或返回文件。撤销临时6参数构造/provider及其3处测试适配；原5参数边界复用。新增持久化事实合同覆盖scope/KB/graph/证据/撤权/删除/exclusion和开关关闭bean读取；实际发布合同先已有真RED。没有新bean、schema、依赖、迁移；新候选 gate不变。开始 dev v2rq2zzg SCAN_PASS，ready=false。

持久化port首轮400项：实际g1及三类撤权已通过，唯一失败为编辑脚本误将既有 malformed sourceRefs 期望403改成404。对照HEAD原断言恢复403/SOURCE_UNAVAILABLE，不改产品拒绝政策、不削弱断言；此轮FAIL/XML留存为fit-port-first-run。首次显式formatter glob参数非法未执行格式；用既有regex参数重新执行后5files成功。
