# AQ-05 人工评审保存类型边界工程证据

起点 HEAD 8462e24dc89e5a917f018a3bf100695ecdc1fbd7，base origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，初始工作树 clean。本片仅覆盖 SAVE_REVIEW，不表示完整领域 DTO 或正式业务验收完成。

## 职责与兼容

PresalesReviewSave 以 Draft/IssueDraft、Severity/Status 与不可变 Issue 列表表达业务输入和合法结果；按照 solution、summary、issues、逐项 severity/status 顺序拒绝。JSON 字段访问与默认值、人工标记写回集中 PresalesReviewCodec，不重建整个历史对象。Service 保留角色、来源、原始请求哈希、回放、CAS、archive、事务及最后执行的不可变 saveItem。

旧 authority 删除后再写入 HUMAN_REVIEW，kind 原位置更新，authorId 由原 saveItem 覆盖；未把人工评审记录升级成发布批准。未知字段、缺失/null、字段顺序、原 payload 和旧修订均保留。没有新依赖、bean、表、权限政策或迁移；没有新增公共 API。Service 减少 1 行、两个包内类增加 95 行，收益是消除该分支的字符串状态规则而非减少总行数。未提取通用规则框架。

## 已执行检查

Maven 全部使用 JDK21；定向命令为 `mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<classes> test`。

- 先在旧 Service 运行新增 HTTP/H2 刻画及原 CommandPayloadContract：41/41，0 failures/errors/skips。六组多错误顺序；失败 body/revision/receipt 不写；默认值、未知扩展、精确字段顺序、客户端伪造标记覆盖、输入不变/同请求回放、不可变追加及回读。
- 替换后 `PresalesReviewSaveTest,PresalesCommandPayloadContractTest,PresalesIntegrationTest,PresalesProjectItemsTest`：67/67，0 failures/errors/skips。纯规则另覆盖精确字符串 ID、所有枚举、错误短路、大小写、UTF-16 长度和不可变决定列表。
- 原授权/CAS/archive/历史回放及撤销来源用例新增 SAVE_REVIEW,summary 参数，保留 UPDATE_PROJECT 与 SAVE_CLARIFICATION 全部断言。没有删除回归或改期望掩盖差异。
- dev 初始 70xtdhc3、实现后 smwxv80w：SCAN_PASS，submission_ready=false；架构增量及 guard 自测通过。显式 `mvn -B -Dquality.base=HEAD spotless:apply` 为开发动作，`git diff --check` 通过。提交仍须验证精确暂存树。

[归档](review-dto/review-dto-regression.tar.gz) SHA-256 `add3a42a8c4e4e5e103b5b1b5257210a65cb6bdfba5911f77e548e1e52624eac`，7 项日志/源码哈希记录，2 处测试凭据脱敏。manifest 区分原始/归档摘要，已逐项读回验证。

独立只读审核 /root/review_save_boundary：COMMENT，无待修复发现，已核对旧/新回归与 dev 日志，未删除或削弱原断言。5 个 Java 文件的 LSP 实际返回 tsc skipped: no tsconfig found，ast-grep 未安装；这些诊断均 NOT_RUN，不是静态检查通过。技术审核不替代维护人/QA 签收。实际提交 tree、完整 commit/push 门禁及远端 CI 以 PR #5 后续记录为准。

真实数据库 MySQL/Kingbase、真实模型、浏览器业务链路、正式维护人/QA 签收及 46 AC 本片 NOT_RUN。当前远端 run37373046648 queued、未执行检查步骤；远端 required CI 强制生效仍未完成。历史坏版本任务恢复与 V2 单写迁移继续开放。

回退只恢复旧分支逻辑并删除两个包内类，无数据库回退或生产数据操作。
