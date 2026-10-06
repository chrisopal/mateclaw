# AQ05 / AC17–20 精确修订比较修复计划

起点 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；初始 dev `48ikrwbk` SCAN_PASS、submission_ready=false。上轮为实际进展；保留完整目标和既有 WIP。

1. 原始要求是精确基线/语义修订及明确类型边界。核对发现 SAVE_SOLUTION 的 baselineVersion、基线批准的 statementRevision、发布 gate 的 requirementVersion/statementRevision 都使用 asInt；公开 payload 的安全整数上限大于 int 范围，可能发生低32位别名。
2. 先补行为回归：真实 HTTP/H2 的 SAVE_SOLUTION 超范围基线版本拒绝且项目/修订/回执不变；Service 实际基线批准及发布 gate 的数值别名、小数和无效节点拒绝，阻止渲染/存储；原合法数字与历史十进制整数字符串保持。记录真实 RED，不以源码猜测代替复现。
3. 复用 PresalesProjectItems 既有修订边界增加精确正整数读取/匹配，不建通用版本框架或新 bean。只接受 int 范围的正整数；历史文本允许 Integer.parseInt 可精确解析的十进制整数（保留 trim、前导零和加号），拒绝小数/指数文本、容器、bool、missing/null和溢出。草稿仍允许暂存未知 statementRevision，信任提升时拒绝不能匹配的引用。
4. PresalesSolutionPolicy 和 Service 的上述比较使用同一规则；方案写入和基线引用捕获不再把异常当前版本截断成有效值。维持合法 wire/hash、错误码、校验相对顺序、授权、来源、事务与已发布只读历史；不整合 UTF16 hash 策略，不变更 schema/迁移/全局 JSON 配置。
5. 不扩展到项目/条目版本耗尽、完整对象单写迁移或 schemaVersion；它们仍是独立未完成项。本片只修复精确修订引用的比较与捕获，不能宣称整个版本生命周期完成。
6. 定向 RED 后修复，运行全部 Presales/权限/架构/来源/投标消费者回归、正式 base Spotless、独立审阅、最终 dev；Maven运行期间不编辑。按实际 Running 类归档日志/XML与源码摘要。无维护人批准不关闭正式 AC；本批不提交、推送或部署。

回退恢复旧比较代码会重新允许数值别名；无数据库回退操作。历史坏引用的信任提升将明确失败，需要重新绑定实际修订；不重写存储中的旧数据或发布字节。
