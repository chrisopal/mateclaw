# AQ-09 工作台提交与下载独立审核

审阅者：Codex原生子代理`/root/workbench_mutations_review`。实施者为`/root/workbench_mutations`，主任务实现下载边界测试并处理审核修正。审核只读，未运行构建、修改代码或继续委派；对比本片before快照，不混入累计HEAD差异。

最终结论：**COMMENT；6文件无剩余可操作缺陷。** 需求符合性通过；技术审核不代替维护人批准、正式QA或远端门禁。

1. command/save/approve/archive与下载完整迁入两个职责模块；receipt、版本/来源修复、旧scope/编辑会话隔离、409草稿及员工继续执行保持。
2. 模板/style与before摘要完全相同；没有本片依赖、门禁、policy、迁移更改。
3. 审核发现HIGH测试夹具缺陷：在spyOn之后才绑定document.createElement，mock实现递归调用自身。主任务移除该全局DOM构造mock，用带this类型的anchor.click观察数组，保留原Blob/文件名/revoke断言；首次完整UI的4项失败全部留档。
4. 审核发现MEDIUM验证缺口：原新增模块测试未覆盖project提交分支的“先关编辑器/解锁再等待重载或跳转”。主任务增加create/update两项deferred合同，同时验证新scope的saving锁不被旧finally释放。复核115/115定向结果通过，两项发现均关闭。
5. 修正后的最终快照逐文件重新审阅；页面0112272e、mutation c7adbc97、downloads 6dcace80、页面测试f52f605a、mutation测试f4b5e0ee、downloads测试dab09506。完整SHA见主任务final-checks归档，前缀仅作审阅交接定位。

诊断局限：早期逐文件LSP除普通tsc无法解析页面测试.vue import的TS2307外无诊断；最终改动后的LSP重跑因code-intel transport closed不可用，不宣称最终LSP全绿。实际vue-tsc由主任务另执行通过。ast-grep不可用，人工及有界rg未见新增日志、密钥、any或抑制模式，但AST记NOT_RUN。

真实浏览器、主题/窄屏与AC-30仍未验收。最终工具链结果及剩余风险见[AQ09_WORKBENCH_MUTATIONS_ACCEPTANCE.md](AQ09_WORKBENCH_MUTATIONS_ACCEPTANCE.md)。
