# AQ-05 人工评审保存类型边界计划

起点：HEAD 8462e24dc89e5a917f018a3bf100695ecdc1fbd7，tree 51317264e574614a57fb64a0bf70e52ba4308ac1，工作树 clean；base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。初始 dev 70xtdhc3 SCAN_PASS，submission_ready=false。远端 37373046648 尚 queued，不能记为通过。

范围：SAVE_REVIEW 的规则从 PresalesService 分支迁至包内纯类型保存规则，旧 JSON codec 保留存储兼容；不新增 bean、依赖、表或公共 API。Service 保留事务、角色/来源授权、请求原文 hash、回放、CAS、archive、shape 与版本容量检查。评审规则按 solution 查找 → summary → issues 存在 → 按数组顺序 severity/status → 人工标记 → 最后不可变 saveItem 执行。复用 ProjectItems 文本校验、查找、不可变修订；不引入第二套权限与版本规则。

先在旧实现运行 HTTP/H2 刻画：多错误顺序、失败无 body/revision/receipt 写入；缺省/null 等级与状态；未知字段、字段顺序、输入不变与请求回放；伪造 authority/kind/author 的覆盖；评审更新追加新 ID/previousId 且原修订不变。扩展既有权限/CAS/archive/来源撤销回归到 SAVE_REVIEW，不改变旧断言。再实现类型化 Draft、Issue、不可变决定列表和 codec，只对旧 JSON 必要字段赋值，不以 record 序列化重建整个历史对象。

验收：类型规则单测、HTTP 刻画、既有售前与架构回归、dev、显式格式化、独立审核及真实 staged-tree commit/pre-push 门禁。检查命令只读，禁用跳测或修改基线。业务发布许可与人工评审记录区分；本片不把评审保存等同发布批准。

回退：恢复原 SAVE_REVIEW 分支并删除新增两个包内类，无数据迁移。JSON 聚合存储、其他 DTO、V2 迁移、46 项正式 AC 与远端 required CI 生效仍未完成；不据本切片宣称全部完成。正式 V2/Delivery 规格输入仍待确认。
