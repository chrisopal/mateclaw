# AQ-05 命令类型边界计划

起点53d12d43f3380b1aee438e17fa5b3bdea588870b，工作树干净；origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；dev0woji4h1 SCAN_PASS。

职责：16个现有action建立内部CommandKind，CommandAction同时保留raw字符串（未知为UNKNOWN+raw）；Command新增@JsonIgnore解析方法。Service权限分组、repair白名单和命令switch使用同一typed kind。HTTP仍为原Command四字段/string action/ObjectNode payload，原wire参与幂等哈希与record；未知action不在解析时提前拒绝，不改变授权/来源/replay/CAS/归档检查顺序。

范围：PresalesDtos、PresalesService、两个新合同文件、ADR与证据。不得迁移事务、SQL、权限、fence、员工结果验证或发布规则；不新增依赖/Bean/迁移/框架。未知不trim、不大写、不升级到已知命令。清理开放字符串分类与分派重复，保留原payload与错误语义；完整payload/项目DTO仍待后续，不以本片替代AQ05或V2。

步骤：先HTTP在旧实现保护unknown/error order/幂等/hash与repair撤权 → 类型替换 → pure类型和wire合同 → 定向及dev/格式/精确commit/push门禁 → 独立审阅/PR回读。回退恢复三处字符串分类和解析方法，无数据库操作。真实HTTP/JWT/H2/Workspace合同不是浏览器或业务签收。

默认分派拒绝保持原行为：即使将来新增枚举但未加handler，也不能无操作地更新version并成功。不得将typed分类当作命令执行授权。
