# 完整应用工具链预验收计划

此前生成/取消和基线批准切片均完成真实回归，整体目标仍开放。本次不继续添加局部生产改动，先验证累计工作树在完整工程工具链上的一致性。HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`；不修改主checkout、不stage/reset/stash其他工作。

遵循现有verify.py内的实际命令与clean_test_env/java21_env，不修改门禁。按base→当前工作树确定前端格式/lint输入，运行正式base Spotless、根reactor clean verify（临时独立skill目录）、vue-tsc、ID精度、完整Vitest和Node测试、enterprise/classic build、cost-tool测试与语法检查。阶段串行避免共享机器资源竞争，进程存活期间不编辑源码。运行前后核对snapshot涵盖的合格文本输入（不包含全部二进制或生成物），并核对日志Running类/XML/JSON/TAP证据。

这是未提交工作树的应用工具链预验收，手工编排未执行ensure_exact_worktree，因此不是精确暂存树的commit/ci门禁，不能报告commit/ci PASS或submission_ready=true。旧hash回执策略仍待决定，V2 writer/replay未集成；不因工具链通过而提交该未集成迁移。desktop/webchat等未映射模块、真实模型/浏览器/Office、方言/生产历史/全量正式AC与远端required CI分别保持未证实。

若失败，先保留原始日志/报告并定位真实原因，再按工程约束修复；不删除断言、增加skip、修改baseline或降阈值。全部实际输出归档脱敏回读后记录范围和剩余阻塞。检查脚本仅在/tmp编排复用既有只读工具，不改变项目控制面，也不作为新的门禁权威。
