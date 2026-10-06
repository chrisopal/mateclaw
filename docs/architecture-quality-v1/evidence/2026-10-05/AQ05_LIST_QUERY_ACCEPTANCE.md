# AQ05 项目列表查询用例拆分：工程验证

## 边界与修改

PresalesService从1067→1024行，原list用例移入76行PresalesProjectQueryService；Controller明确返回R<Page>并直接注入查询服务。查询服务仅依赖PresalesAccess、PresalesProjectRepository、ObjectMapper，不依赖大型写服务、Controller或跨域私有helper；写服务不保留转发方法，也不反向依赖查询服务。新增1个与原售前开关一致的Spring服务，没有新库、表、SQL、迁移、缓存或事务。

保留原viewer授权→分页校验→过滤键编码→单语句投影读取→故障分类→解码→Page顺序。SQL在Repository；原STAGE/SUMMARY/DECODE/未知projection故障映射、JSON解析异常包装、long分页offset、null/blank过滤含义与UTF16处理保持。写路径、回执、命令和迁移闭包不变。新增私有decode保留原6行逻辑，避免为了少量异常包装建立共享框架。

列表仍保留旧投影中的未知扩展、null与值类型，不将其包装成伪固定DTO。Page仍使用List<ObjectNode>，完整项目/命令DTO尚未完成。原PresalesListingProjectionV1及V218未改动，不能通过修改冻结算法丢失历史数据。Java Service.list调用位置改变，仓库调用者全部迁移；仓库外Java调用者未验证。HTTP路径、参数、状态、JSON字段及数组顺序不变。

## 行为保护和实测

- 迁移前原生产实现，4类104/104通过：SQL影子比对/实际行数、HTTP权限与分页、terminal写后投影、运行时事务集成。新增2项SQL台账断言证明拒绝viewer先于非法分页且不读SQL、三种非法分页在viewer核验后不读SQL。
- 迁移后52类598项，597通过、0失败/错误、1既有PPT环境条件跳过。含真实H2/Flyway、登录与工作区HTTP角色夹具、原子writer/read、事务可见性、WorkbenchArchitectureTest及SemanticCoreArchitectureTest。原断言未删，14个测试接线文件只调整新bean导入/列表调用/fixture构造，另加上述2项刻画及ErrorContract的7项新HTTP拒绝合同；具体逐文件摘要见list-query-results.json。
- SQL定向测试使用真实Repository/H2及台账JdbcTemplate，权限部分为Mockito；HTTP合同和运行时事务夹具提供更广授权/事务证据，仍不等于真实客户环境。
- 正式base Spotless、git diff --check通过。初始dev es6839uz为SCAN_PASS；最终实际任务/文本输入身份见final-report.json.gz及final-manifest.json.gz。独立技术审阅见independent-review.txt.gz；COMMENT不替代维护人批准。

工作区 /Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw。HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，base ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。本轮修改4份生产源、15份测试文件、计划/结果/README/ADR及证据；其他累计WIP和主checkout保留。

## 门禁发现与修复

首次最终dev zs3tnns3因AR-004失败：新查询服务不能复制旧写服务对semantic.web.SemanticApiException的存量依赖。该失败报告和原审阅后源码/测试证据保留；未修改门禁或基线。新服务分页错误改用既有PresalesRejected，在PresalesExceptionHandler新增明确适配，调用原error方法保留HTTP状态、业务码和消息；原SemanticApiException处理不变，Access拒绝仍保留原异常与优先级。

先增加7个HTTP状态的领域拒绝合同，实际RED为12项中的7项未处理异常；补充适配后完整598项回归通过（1既有skip）。直接Java查询非法分页现在是本域PresalesRejected，SQL测试明确校验新类型/原状态与消息，权限拒绝仍校验同一SemanticApiException实例和零SQL。旧记录/SQL/wire不变；这是明确的Java异常类型边界变更，不宣称仓库外Java调用者无变化。

## 命令与归档

Maven显式使用本机Java21。日志、每轮实际Running类对应XML、旧/新源码均保存在list-query-tests，XML归档先于下一次Maven。日志脱敏；压缩/解压摘要回读及19份源码SHA核对后形成最终manifest。执行Maven/formatter/dev期间未编辑仓库。

```sh
mvn -B -pl mateclaw-server -am -Dtest=PresalesSqlListingTest,PresalesProjectListingContractTest,PresalesTerminalReceptionTest,PresalesRuntimeTransactionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -am '-Dtest=Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

## 未完成与回退

本轮未跑MySQL/Kingbase、全量应用/UI/浏览器/真实模型/Office；前批证据不计入本轮。未修改前端，没有本轮全UI或构建通过声明。V2 hash writer/replay、历史回执策略、完整对象/修订/依赖/attempt迁移、真实历史恢复与稳定领域DTO继续开放。46正式AC仍NOT_RUN；完整commit门禁、独立维护人批准和远端requiredCI未完成；未提交或推送。

这是职责拆分，不是总行数减少：增加独立查询bean与构造、保留小型解码逻辑，换取读用例不依赖写服务。回退恢复Service.list和Controller原构造/调用并移除新query bean，恢复测试接线，不操作数据库，不撤销前批来源/版本/冻结成果修复。
