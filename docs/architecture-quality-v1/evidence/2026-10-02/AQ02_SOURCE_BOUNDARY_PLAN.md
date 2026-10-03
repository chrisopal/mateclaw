# AQ-02 来源授权职责拆分计划

起始 HEAD 38cedb589c7c9700c4ac1afa52bfeafbd4486528，独立工作树干净，origin/dev 增量 dev 为 SCAN_PASS（lqbpe024）。本片处理 PresalesService 的材料、历史、冻结发布遍历及来源摘要复核；不改变业务权限、事务、回执、ID、发布字节、数据库结构或模块开关。

## 边界与决定

- PresalesSourceAuthorization 负责领域遍历、员工交集、冻结绑定规则和准确失败。保留检查顺序、human-only旧项目行为、baseline空graph仍查询/task空graph跳过、治理撤回不限定source_kind。
- Wiki 拥有只读 current source 端口与 repository；复用原始单次 JOIN：Workspace、raw/KB deleted=0、COALESCE(NULLIF(extracted_text,''),original_content)。只把SQL null转换空文本，不trim、不提取或改写来源。
- Semantic 拥有不受功能开关移除的只读治理端口与 repository；读取持久WITHDRAWN事实，无写入、图锁或授权升级。调用者仍先复核Workspace/raw权限。两端口均明确不是actor/member授权凭据。
- PresalesService 保留成员入口、命令事务/CAS/回执、结果权威锁与旧HTTP错误适配。来源策略使用领域拒绝类型，服务边界转换为原SemanticApiException以保持已有调用者/handler错误类型、状态/代码/文本；不向新类复制语义web依赖。
- 复用ProjectSourceAccess员工KB/真实raw归属能力，不把内容/摘要塞进基础权限服务。Service不再含此切片跨域SQL。

拒绝：仅搬SQL进业务integration目录（隐藏依赖）；复用SourceContentPort（会捕获快照、修改图版本）；直接用getTextContent（会提取/外发且空白语义不同）；调用conditional治理写服务（关闭semantic后历史行为改变）；扩大迁移或一次重写工作台（缺少独立验收）。公共service/repository各负责契约与SQL，只有这两项读取，无通用可配置查询层。

## 实施顺序与所有权

1. 锁定既有26项HTTP/runtime/source回归与原atomic/fence测试；添加精确读端口及领域边界刻画测试后再搬代码。
2. 公共读取端口由bounded executor负责，限定wiki/semantic新文件及其新测试；根代理负责PresalesService、新来源策略/刻画测试、旧构造器和Spring imports。双方不改POM/门禁/迁移，不相互覆盖。
3. 保持检查顺序，显式域错误转换；dev、定向Java回归、固定formatter、独立架构/权限审核，再精确树commit和正常push。

## 验证与风险

读端口覆盖正文null/空/空白/Unicode、跨Workspace、deleted及不存在、WITHDRAWN跨source_kind/空graph与feature关闭。领域测试覆盖旧human-only、员工授权/真归属、历史忽略旧digest/当前批准仍409、冻结候选、不可变输入、首次拒绝顺序。既有HTTP/JWT/事务/回放/权威竞争测试保留。全量Java与门禁执行；本片无UI变更，固定规则可判NOT_APPLICABLE。多方言/真实浏览器/异步重启/正式QA仍待验证，不将本片作为完整AQ-02或P0签收。
