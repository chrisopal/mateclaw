# AQ-05 方案草稿政策职责实施计划

起始HEAD ad0853831a4af313a67674f1cfa62b403694574a，工作树干净；独立worktree，原项目WIP不触碰。初始dev62gg9kaf SCAN_PASS。

边界问题：Service同时拥有用例事务/授权/CAS/回执、草稿输入/来源/精确基线规则、方案覆盖计算及通用项目项修订。先新增真实JWT/HTTP/H2刻画方案完整wire、覆盖顺序/计数/默认值、来源和baseline错误先后、不可变方案版本/previousId、失败不写body，再改生产。提取本域纯PresalesSolutionPolicy（prepare/coverage），供人工命令、已复核员工结果及releaseGate共用；不转移批准和来源读取权。项目项find/save与原text/enum规则放同域PresalesProjectItems，Service保留公共find兼容。无新DI/接口回调框架、数据库、依赖、配置或控制面。

所有规则保持原ObjectNode coercion/未知字段/withArray副作用、校验顺序、UTC/UUID、不可变追加及原404/409/422/400错误。policy无HTTP/SQL/事务/公开授权；原服务只在授权/锁/CAS检查后调用。releaseGate仍先requireSemantic/provisional/baseline，再coverage，并复核来源/独立人工审核。此片推进命令边界而不是宣称完整DTO、SQL分页或V2完成。

完成每个职责切片执行dev和适用回归，最终Spotless只读check、独立审阅、精确暂存/正常commit与push。拒绝只将整Service搬到god helper、重复coverage实现或通过重写旧断言变绿。回退恢复原内联方法无数据库操作；多方言、浏览器/模型/重启/迁移/正式QA仍待完成。

实施修正：新项目项模块直接依赖SemanticApiException被AR-004拒绝（njw75fcz）；未改规则，改用内部Rejected(status/code/message)，Service捕获并恢复旧Java异常契约。原GenerationCoordinator等仍按SemanticApiException处理find错误，因此这些适配是兼容边界，不移交授权。1tmbl58x/x395flpo及最终dev另见manifest。旧28项/迁移59项/最终67项通过；新增测试转义/错误envelope/来源列名与withArray错误类型的失败均保留。无原测试断言或生产规则放宽。
