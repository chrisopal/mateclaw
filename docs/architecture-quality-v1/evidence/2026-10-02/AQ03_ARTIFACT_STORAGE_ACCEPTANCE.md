# AQ-03/05 成果持久化边界工程验收

起始HEAD5cbc7a41926daec6be9d5e1e8b8fe6527c1479ea，独立工作树干净，原项目WIP未触碰；dev qq0nsw1x。实施计划AQ03_ARTIFACT_STORAGE_PLAN.md。

## 职责与兼容

领域PresalesArtifactRepository拥有原INSERT、release重绑定、digest/content双投影与preview单content投影四类SQL，三个key均string，StoredArtifact为不可变记录；不解析Base64、渲染、授权、摘要判断或新开事务。共享仓储安装方式与原无条件PresentationService一致，不因presales开关移除PPT Bean的依赖。

PresalesService移除所有JdbcTemplate/SQL；1431→1401行。保留成员/source/release gate、manifest/handoff冻结、原cardinality、404/409文本、Base64/摘要算法、生成release→saveItem→原release ID重绑定顺序以及public事务。PresentationService的插入也接同一仓储，原TransactionTemplate仍包住所有page写入。仓储58行，presentation388行；没有新依赖、schema/Flyway、批准政策或控制面修改。查询摘要/业务命令仍在服务，列表仍未SQL分页；不宣称V2迁移完成。

六份既有fixture移除新增DI/反向替换后原文件逐字节保持；CompilerTest因原密集源码触发固定formatter重新排版、imports重排，方法token在反向替换DI后完全相同，原断言/EnabledIfEnvironmentVariable未修改。

## 行为保护与检查

生产改造前六类33/33通过，包括新增三项真实JWT/MockMvc/H2：发布/preview精确binary、viewer下载/member拒绝preview/owner预览、另一Workspace404与pending403；presentation存储精确bytes及digest409/missing404；CREATE_RELEASE写入后真实revision CHECK故障导致project/artifact/receipt全部回滚。测试手工持久化最小已审核夹具只刻画存储路径，不证明真实批准全流程；原Integration的真实发布/来源回归同时执行。

新增夹具初次编译失败因辅助方法与基类request撞名；首次替换又错误改到MockMvc API。随后请求缺expectedVersion/customer被真实400拒绝。均只修新增夹具并保留失败日志，旧断言/生产校验/config不变。原33通过后才编辑生产。

生产后9类61/61通过。新仓储五项直接加载原V211 H2迁移，核验三key隔离/大string IDs/raw digest+base64/缺失空list、原release重绑定范围和原字节、重复文件故障整体rollback、caller重绑定rollback、售前关闭时presentation/storage装配；最终10类66/66，零fail/error/skip。额外配置已安装ppt-master-plus和Python3.13实际执行CompilerTest 1/1，核对制稿入库摘要与POI一页可编辑文本，不是条件跳过或模型QA。

dev 8daqxktq SCAN_PASS；Spotless check exit0；source diff-check通过。独立authority_review核查原SQL/params/projection/事务/冻结/错误/装配和fixture，APPROVE bounded，零问题；其OMX LSP/AST因Transport closed不可用，实际Maven JDK21 compile/tests和手工diff为可用诊断，不假称LSP通过。精确暂存与正常commit/push门禁须绑定最终树，实际报告继续补记。

十二份日志先脱敏JWT、JSON password/token及Spring自动生成开发密码行，保留输入/发布SHA256与数量，见artifact-storage-test-results.json。补充复核前批project-storage六份日志移除11条自动生成测试开发密码；之前JWT/JSON过滤遗漏此类启动行，当前证据及hash更新并保留原候选commit/tree身份，不重写已推送历史。不含生产密码；原始本地日志不发布。

## 剩余与回退

H2、真实HTTP与单页编译器不证明MySQL/Kingbase、生产重启、角色/API完整矩阵、真实模型/浏览器或迁移前后黄金样本。AC23/31正式状态仍NOT_RUN，required CI未验证；无新增生产操作。下一步继续查询摘要/命令类型边界及独立对象/精确依赖/SQL分页/备份恢复单写迁移。回退仅还原两个服务SQL与DI，无数据迁移；必须保留caller事务、source/release授权和已发布字节，不把DAO事实变成批准权。

最终源码提交45a97c98e9f6134d7bb025ec29d65db669c2110b，实际树d77525cb8298d05437a1c212d3c30fea9b18e455；um6o82r2精确暂存与s54u4y57正常提交钩子均PASS/submission_ready=true，同树。Java6040总数/5970执行/既有70skip、零failure/error；UI846/846、type/ID/Node/两主题构建通过。原件报告及hash见manifest与artifact-storage-tests。完整推送范围和远端读回继续在PR5补记。
