# AQ-05 售前查询 DTO 计划

HEAD ee2a6d259bccd0ecd1dc78c00124ffaf9808d8a3，工作树干净；origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。初始 dev 1hf7dt03 SCAN_PASS。

范围：PresalesDtos、PresalesService、PresalesController 三查询返回类型、新 HTTP/纯 DTO 合同及证据。用明确记录替换 capabilities Map 和 sources/trustedStatements ObjectNode 拼装。来源保留基础/启用图两种固定形状，启用图中的 null 字段保留；事实 revision 为 int，ID 为 string，evidenceIds 顺序/null 不变。先刻画旧 HTTP/Jackson 形状及权限，再替换类型，回归与 dev/精确提交门禁及独立审阅。

复用现有语义公开端口；授权、来源复核、200/500 上限、图去重、异常顺序与传播不变。不增依赖、SQL、迁移、事务或框架。仓库 Java 调用方仅 Controller，Java 返回类型会改变，HTTP wire 不变。项目聚合完整 DTO、SQL分页、V2和正式 QA 仍未完成。

测试使用真实 HTTP/JWT/Workspace/H2 项目与 Jackson，语义图/事实公开端口为 mock，不能代替真实图/浏览器/业务验收。回退恢复记录和三查询方法，无数据操作。

准备阶段 Python 命令意外调用 Python2，测试文件未生成；随后实际只执行既有 Integration8/8，不计作新合同已测。纠正为 python3 后新合同必须重新执行。
