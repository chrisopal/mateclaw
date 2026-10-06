# AC46 原始公共 CI 产物来源核验

独立只读审阅 ac46_ci_provenance_audit：工程 PASS，正式签收 NOT_RUN。核验原条款“不使用生产数据/密钥；公共CI产物无客户敏感原文”。没有修改日志、测试、门禁或部署配置；没有把本地脱敏归档当作公共原始产物。

## 确切对象

Run37418776854，source608d525a5f6ad6f966cc3ebc604c62408be56070，merge4fa839f2d59766e41318ef5746a45327a863a7b2，tree6c0b42246bb85b980f3ca22fb6aabd6a1cf762ff。原始下载 /tmp/aq-ci-37418776854 中18个产物文件，另外两个identity JSON只是辅助身份记录。15日志SHA全部与report匹配。原始java-tests.log SHA25621bb7dbb9cdf7c6b394a1f9ba7a88d4337eebe27826faf4a03f9ba55c3a026d7。

## 来源与环境

.github/workflows/engineering-gate.yml:6-31,59-75：Ubuntu托管runner、contents只读、checkout不保留凭据，没有生产secret/config/environment/service输入或生产数据库缓存，仅上传工程报告目录。实际base68d8fc53的scripts/quality/verify.py:67-76过滤凭据及Spring/MateClaw/JVM环境注入，253-254使用临时skills目录。

原始日志245条JDBC均H2（232mem、13file），无生产数据库地址。默认文件库来自application.yml:19-27，仓库无受跟踪的持久化数据库/dump输入。ModuleStartupMatrixTest:34,112-126使用临时目录、HOME/tmp与清空子进程环境，ModuleStartupProbe:73-85固定隔离配置。OrdinaryChatProbe:56-57,233-242使用loopback合成模型；Semantic/Bidding HTTP夹具显式H2，投标golden注明SYNTHETIC_NOT_HUMAN_REVIEWED，Wiki模型读fixtures/llm-responses.json。

## 原始内容核验

- 32条密码提示全部来自测试启动的UserDetailsServiceAutoConfiguration，不是生产凭据；原始公共日志仍保留这些测试生成值，本记录不展示其值。
- JWT、私钥头、AWS access-key/OpenAI长密钥模式均0。
- URL主机为依赖仓库、框架文档、localhost及BaseAgentMultimodalSkipNoticeTest:104的合成example域名。
- 135种连续四字以上中文片段全部匹配确切HEAD源码/资源；一项通过拼接DefaultToolDisclosureService:176-177字面量确认。含客户/预算等提示的17行均为源码诊断常量。
- 26571行自由文本赋值日志：26566行来自AvailableProviderPoolTest:119-145的failover并发夹具，5行逐值匹配ILinkClientUploadUrlTest:34-118的本地Weixin mock响应。

工程范围结论：该次原始CI产物未发现生产数据、生产密钥或客户敏感原文，有限补证满足，可转工程证据齐备待正式签收。该结论不扩展为未来CI或任意运行日志的安全保证，不要求新增通用DLP。原始产物已经按身份/内容核验，后续代码批次保留来源边界并记录其自身CI身份。
