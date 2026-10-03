# WebChat 附件测试存储隔离验收

正常提交钩子 b981u6v9 对 staged tree `8d8899ffc1782d89ef7e210cc46c17f666f08150` 返回 FAIL，未创建提交。4 个 HTTP 上传用例受到默认目录已积累 50 文件的污染；旧清理路径未匹配实际整段 conversation ID 的目录。单方法复现 1/1 失败（HTTP 400 max 50），见机器证据和失败报告。

按事先计划仅调整 WebChatAttachmentE2ETest：JUnit 私有 TempDir 通过 DynamicPropertySource 注入现有配置，每例断言实际绑定及空目录；清理只删除自身临时根内子项，IO 失败直接失败。实际上传路径增加归属断言，原 token、foreign attachment、类型、内容字节与 HTTP 断言全部保留。生产配额、存储及旧文件未修改。

修复后两个独立 Maven 进程分别执行 WebChatAttachmentE2ETest 与 WebChatFileServiceTest：每轮 7 HTTP + 6 服务 = 13/13，0 失败/错误/跳过，BUILD SUCCESS。服务测试仍保护限额拒绝与 traversal。固定 Spotless 格式后第二轮通过；默认四个测试目录仍各 50 文件，未清理。命令为 `JAVA_HOME=<Temurin21> mvn -pl mateclaw-server -am -Dtest=WebChatAttachmentE2ETest,WebChatFileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。dev 检查 SCAN_PASS lwi2npyg，应用工具链 NOT_RUN。

独立只读 source_boundary_review 对本次测试配置控制面增量审核，未发现阻断，确认没有門禁、生产权限、配额或断言弱化。审核人未自行运行 Maven；主代理读取真实两轮日志。这是工程审核，不是正式 QA。当前同类测试顺序执行，若未来启用并发须调整每例根目录隔离。全量提交门禁必须对新 tree 重跑，旧 PASS 不沿用。

日志和 SHA256、旧目录计数见 [机器证据](webchat-isolation-test-results.json)。回退只需 revert 测试变更；不涉及生产数据、数据库迁移或依赖。
