# AQ-03/05 成果持久化边界实施计划

起始HEAD5cbc7a41926daec6be9d5e1e8b8fe6527c1479ea，base origin/dev；独立工作树干净。dev qq0nsw1x SCAN_PASS，应用工具链未执行。

问题：PresalesService仍直接读写成果表，PresalesPresentationService重复插入同表。目标是同一领域仓储拥有原INSERT、release重绑定、digest/content读取及preview原content投影；消除两个服务的JdbcTemplate依赖。保持调用者授权、source/release gate、Base64/digest校验、原404/409、冻结文件/manifest/handoff及实际事务。仓储只接受string IDs和raw digest/base64，不渲染或授予权限；不能把列表读取事实误当Workspace授权。仓储按现有无条件presentation Bean安装，不新增模块开关/依赖/schema/迁移。

先在旧实现添加真实JWT/HTTP/H2刻画：冻结发布/preview原字节和状态/范围；存储presentation精确字节及digest/missing错误；CREATE_RELEASE写入后revision失败全部rollback。运行原发布/来源/事务/PPT pin/renderer回归后才编辑生产。仓储新增真实V211 H2的复合key隔离、原投影、重绑定及调用方事务rollback合同；PPT写入仍使用原TransactionTemplate。

只改上述两个服务、新领域仓储、新合同测试和必要测试DI接入，现有断言不删改。每个职责片dev，格式显式写入后check；独立审阅SQL/事务/模块关闭装配；最终精确暂存及正常提交/推送门禁。发布证据先扫描并脱敏测试凭据，保留hash/数量。没有实际编译器/多方言/真实浏览器/正式QA证据时保留NOT_RUN。本片不等于V2单写迁移、SQL分页或全部AQ完成。
