# AQ-05/09 售前编辑业务规则拆分计划

起始HEAD 36a46900c4e41a099f5d87e5382469150972ea8b，独立工作树干净、base origin/dev；dev 82y0omee SCAN_PASS，工具链NOT_RUN。原项目WIP不动。此前公共请求边界已提交/推送，本片继续完整工作台结构目标，不称P0/QA完成。

范围：PresalesWorkbench.vue、域内shared/editorSubmission.ts、编辑合同/现有工作台测试、设计/AC工程证据。不改HTTP契约/权限批准/迁移/依赖/runner/门禁。现有页面已保护77项生命周期行为；先新增真实Vue/ElementPlus的创建/编辑/必填/材料命令刻画，再移动规则，新增11种编辑初始化、基线响应同步、白名单、校验、精确命令和副本不变合同。

拆分职责：纯模块拥有Editor种类、默认表单/历史副本、方案需求对齐、必填与ANSWERED来源校验、fitgap证据分割、员工/受限材料最小payload、project元数据与领域action映射；返回discriminated submission（invalid/project/command）及稳定校验码，不依赖Vue/store/http/i18n/router。页面保留翻译、输入展示、dirty/confirmation/options生命周期、canWrite/来源修复allowlist、当前scope/session、CAS/operation receipt、create/update路由与answered后员工继续。公共边界不升级角色，命令执行再次复核原授权。

兼容：JSON副本及未知历史字段保持，方案编辑不复用旧id，baseline过滤与匹配response保持；所有ID字符串；原原始data仍用于project receipt，metadata白名单单独组装wire；仅fitgap副本删evidenceText，受限材料仅三字段，员工只agentId。保留校验顺序/无修剪存储值/已有409草稿及重复提交回执，不新增业务限制。不得将过滤表单升级为后端授权或把只读mapper引成框架。

验证：原组件刻画PASS后搬移，纯模块与全售前组件回归、dev；独立审阅命令/来源/幂等与测试装配增量，完整精确暂存树门禁和正常commit/push、PR/远端读回。未通过就修复，原断言/门禁不降级。正式角色/浏览器/多方言/并发/重启/模型/QA仍NOT_RUN。后续仍需表单显示组件/用例协调、PresalesService聚合和V2单写迁移，不能以本片替代整体目标。
