# AQ-05 项目列表投影工程验收

起始HEAD `b7a40deb8a99e06765a85a381fbf93c97260412e`，工作树干净；独立worktree实施，原项目WIP未触碰。初始dev任务9cfmcrxc、最终dev任务jvrkz12x均SCAN_PASS，不是提交或正式验收许可。

## 实施与兼容

PresalesService由1401降至1344行；新增85行本域package-private PresalesProjectListing。服务保留viewer授权→分页校验→Workspace仓储读取→逐行decode，投影负责原Locale.ROOT筛选、顺序、筛选后total/内存分页、deepCopy摘要及stage。Criteria只用于内部参数；原Page/ObjectNode wire、string IDs、未知历史扩展、12类来源集合剔除、未回答数量及最后方案版本保持。命令stage和受限回读复用同一判定，无新增SQL、事务、依赖、迁移或授权政策。

初稿将decode收集为List；独立审核及实现复核发现会改变异常顺序并保留全部完整body，已改回Stream并补前行stage错误先于后行decode错误的刻画。调整期间五处新增测试仍传List导致编译失败，日志保留，修复仅涉及新增测试接线；最终58项通过，未删除原断言。没有把全量读取后的内存分页称为SQL分页。

## 实际验证

JDK21 Maven真实HTTP/JWT/H2/Spring：旧实现15项通过，初稿57项通过，最终58项通过（0失败/错误/跳过）。最终七类覆盖列表过滤/分页/总数/历史扩展/ID/存储不写/非法分页/授权先后/Workspace隔离，及既有CAS/回执/来源/事务/成果回归。纯投影覆盖阶段优先级及withArray副作用、深副本、Locale、长offset和惰性错误顺序。实际命令：

`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full -Dtest=PresalesProjectListingTest,PresalesProjectListingContractTest,PresalesIntegrationTest,PresalesProjectPersistenceContractTest,PresalesRuntimeTransactionIntegrationTest,PresalesArtifactPersistenceContractTest,ProjectAuthorityFenceDatabaseTest -Dsurefire.failIfNoSpecifiedTests=false test`

`python3 -B scripts/quality/verify.py --mode dev --base origin/dev`

`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -Dquality.base=HEAD spotless:check`

独立authority_review最终APPROVE_BOUNDED，零严重度发现；Java LSP Transport closed，使用实际编译/回归和人工diff核对。日志先脱敏JWT、JSON凭据和Spring开发密码，输入/输出SHA与替换数量见project-listing-test-results.json；原失败证据也保留。

## 范围、风险与回退

本片完成列表投影边界，不代表完整稳定项目DTO、命令拆分、SQL分页、V2迁移、MySQL/Kingbase、真实浏览器/角色/重启/业务QA或维护人签收。ADR-AQ-019仍Proposed，正式AC保持NOT_RUN；远端required CI未验证。列表仍全量读取，在大数据集有既有性能限制。回退恢复Service原内联投影及stage，删除新helper/新增合同即可，无数据库操作。提交/推送门禁与精确tree证据单独追加，不用本报告自签代替门禁。
