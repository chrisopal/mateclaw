# AQ-06 列表 SQL 投影工程证据（正式验收待完成）

源码提交 b69e62fd4d03c3f6e1f5a880b6fcd323c8c94e63，精确树 a9ccad01fa74eb437908bc68897735efbc8dcef8；开始 HEAD 200aaaa07a1d1fd4e1df78124f2a709ec91e3c5b，固定 origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。在隔离工作树完成，未触碰原项目 WIP。计划见 AQ06_LISTING_PROJECTION_PLAN.md；设计 ADR-AQ-034 保持 Proposed。

售前项目列表原先读取同 Workspace 全部正文、构造摘要后再分页；实际红例读取120条 JDBC正文，即使请求空页。现在现有 project表保存可重建的版本化派生键/摘要/故障，Repository一条 SQL返回 count、首个合格故障和有序当前页，正常仅物化当前页，空页一条哨兵；不读正文、历史或来源数组。Service保留权限先于分页校验和现有Page/wire。查询仍扫描派生键，未测时延或声称索引加速。

V217是新增三方言DDL；V218是三方言Java迁移入口，共享冻结V1工厂和有实际闭包字节码checksum的批量回填。Flyway拥有连接/事务；100条keyset批次只限内存，整次迁移事务/锁可能很长。只写派生列，CAS/重读保护版本；解码、阶段、摘要故障保留可筛选事实；缺失/过期派生事实明确fail-closed，无全正文降级。创建、手工/员工更新、失败兜底和重启恢复同一SQL原子维护正文与派生事实。name/status数据库排序、Workspace隔离、授权、取消/终态、历史与成果权威保留。

独立技术审阅先发现 MySQL TEXT容量问题，六列改为LONGTEXT。真实MySQL又发现孤立UTF-16在JDBC变成?：28测试2失败，原始Surefire记录已保留。修复只转义新JSON存储中的孤立代理项，正常Unicode/JSON字节不变；新正文/revision/receipt/摘要使用相同边界，旧字节和请求hash不重写。一处新增纯测试夹具缺参数编译失败已修复，未记为通过。影子初测10失败来自IntNode/LongNode内部差异，改为原有JSON响应完整字节比较，不删业务断言。

- 最终真实MySQL8.0.46：29测试通过，无失败/错误/跳过；每次空的独立loopback schema基线216后真实发现V217/V218（SQL夹具授权为mock，非真实角色验收），覆盖Unicode精确筛选、顺序、空页、跨scope、坏数据故障顺序、缺失/过期、>64KiB扩展/大键，以及真实Service创建/回放/手工更新和CAS后revision失败的事务回滚。
- MySQL真实mysqldump备份→独立恢复：两个schema各205条、多批回填、重复migrate/validate、摘要分页；正文、版本/name/status、回执、revision及冻结base64/digest快照升级前后完全一致。只用合成文件字节，不能当实际Office成果验收。日志、probe源码与快照/备份SHA见manifest。
- H2定向122项通过（最终新增两个测试后以精确全量门禁为准）；7项迁移测试覆盖真实发现/闭包checksum、205条批次、映射器、旧字节和isolated restore。正常/失败/恢复writer及真实Spring事务回读覆盖保留原断言和重试。
- 精确staged l2c835j5、正常commit hook opmadhgv 均实际exit0/PASS/submission_ready=true且目标树一致；Java 6171实际执行、既有环境条件跳过70项，UI 1093及其余适用检查见原始报告；新增MySQL/SQL/迁移测试未跳过。dev gyp3_ul8仅SCAN_PASS。独立12文件技术review先REQUEST_CHANGES，修正后的delta COMMENT无新增阻断；Java LSP不可用，以实际编译为工程证据；不是维护人批准。

当前门禁 scripts/quality/gate.py:is_migration 仅识别.sql，真实反例确认Java入口变更/缺方言不会触发DB-001/DB-002；冻结工厂闭包也未被该规则覆盖。Flywaychecksum能反映编译字节变化，但现有validate=false方言profile不强制拒绝。下一检查切片须在独立控制面审阅下补Java入口/闭包正反例与约束；本轮SCAN/PASS不是迁移不可变已强制生效，合并/部署仍待审核。

尚未完成：真实Kingbase/生产升级与新写入后反向回退、完整V2单写切换、真实角色/并发/browser/model、利益相关方/QA及required远端CI。原有UTF-8请求hash可能使孤立代理项与?同hash，已有29项中的独立刻画明确此限制，不能当同键异请求拒绝证明；下一阶段需要版本化契约/历史回执政策。缺少权威Presales V2.0.0/Delivery V1.0.0文档，不虚构对象表。46正式AC全部NOT_RUN。

回退：停旧writers进行升级；不能混跑旧writers让派生事实失效。新增写入后直接切回旧JSON流程/恢复旧备份可能丢失新业务写入，当前没有经过实证的反向生产迁移，因此明确阻断该动作。开发patch可回退；生产回退另行设计、验证和授权，不删除新列/冻结成果。本轮没有部署或生产数据操作。
