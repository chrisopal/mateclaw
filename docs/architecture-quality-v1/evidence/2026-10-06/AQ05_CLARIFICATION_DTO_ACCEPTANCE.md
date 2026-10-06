# AQ-05 澄清保存类型化工程证据

起点 HEAD f22aed6656b9dae0c788b672911d7e9dabdfb418，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。本片仅处理 SAVE_CLARIFICATION，完整聚合 DTO 和 V2 迁移仍未完成。

## 职责与兼容

- PresalesClarificationSave：Draft/Status/Decision 使用类型值表达输入、合法状态和保存决定，集中 question/status/项目需求引用/Workspace owner/answer/source 顺序；引用存在性和 owner 授权复用既有协作者。未新增 answerSourceId 来源政策，仍是原必填文本语义。
- PresalesClarificationCodec：仅解码旧 payload 及写回必要字段，不重新序列化完整记录。保留未知扩展、缺失/null、原字段顺序；OPEN 移除 answeredBy/answeredAt 而保留 answer/source。
- PresalesService：仅在原分支接线，保持来源授权、原请求哈希、回放、CAS、事务、旧异常类型及最后执行 saveItem 的顺序。已有 JSON 集合存储仍在 ProjectItems，未冒称整项目领域模型已类型化。

无新依赖/bean/数据库迁移/权限放宽。回退恢复该分支原逻辑并删除两个新类，无数据回退。

## 实际回归

所有 Maven 使用项目 JDK21，`-pl mateclaw-server -am -Dmaven.compiler.proc=full -Dsurefire.failIfNoSpecifiedTests=false test`：

- 修改前 Integration/CommandPayloadContract/ProjectItems：55/55。
- 先补合同并在旧实现运行 CommandPayloadContract：35/35。保护六组多错误优先级及 body/revision/receipt 均不写；旧字段、回放、原请求不变、整项替换及排序保持。
- 迁移后新增纯类型测试：四类合计61/61，含状态大小写、UTF-16长度、引用/owner调用顺序、OPEN不要求回答和伪造回答身份覆盖。
- 最终扩展 `Presales*,ProjectAuthorityFenceDatabaseTest,WorkbenchArchitectureTest,SemanticCoreArchitectureTest,SourceGovernanceReadServiceTest,BiddingHandoffTest,BiddingHandoffReceiptTest,BiddingMaterialsTest`：753项，752执行通过、0 failures/errors、1既有PPT环境skip。
- 授权/回放/CAS/archive和撤权测试从单例扩为 UPDATE_PROJECT,name 与 SAVE_CLARIFICATION,question 两组，保留旧断言。追加精确9字段顺序断言后 CommandPayloadContract37/37；生产源码在扩展回归后未变。
- 初始 dev q78mq05_，实现后7v56h6oj，最终o8h9et17，均SCAN_PASS/submission_ready=false。显式Spotless apply为开发动作；diff --check通过；提交仍须精确暂存门禁。

[证据归档](clarification-dto/clarification-dto-regression.tar.gz) SHA-256 `3e037387bf98c558058fe76a333d60481970cc6a9edc821fdc87b5fcbdcc3101`；10个原始日志/源码哈希记录，12处测试JWT/密码脱敏。manifest分别保存原始和归档摘要，已逐项读回校验。

独立只读审阅 `/root/clarification_review`：COMMENT，未发现生产或安全边界退化。提出的字段顺序覆盖缺口已补并复审关闭；未改旧断言。Java LSP 返回无tsconfig跳过，AST不可用，NOT_RUN；技术审阅不替代维护人批准。

真实 MySQL/Kingbase、浏览器、模型、客户/维护人签收、46项正式AC本片NOT_RUN。此前CI修复HEAD的远端run37369407148仍queued，本片没有远端通过证据。实际提交tree、完整commit/push任务及远端结果由PR #5后续准确记录。
