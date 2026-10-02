# AQ-02 来源撤权后的修复读取契约与实施计划

起始HEAD 21c5a68413bafcc3bcbf74b3f1844f292aff6c9d，独立工作树干净。目标是撤权后刷新仍可修复绑定，完整GET/历史/生成/导出继续拒绝撤权来源。无需数据库、依赖或门禁变更。

## 决策与替代

1. 保留完整GET的403；新增GET /projects/{id}/repair-context，仅Workspace member可读，使用现有access与带Workspace条件的load。采用字段白名单构造项目元数据（id/workspaceId/version/name/customer/ownerId/industry/goal/status/agentId/agentName等明确项目配置），不复制任意顶层扩展。全部SOURCE_COLLECTIONS为空；sourceAccessRestricted=true。repairBindings仅id/role，无KB/graph/raw ID、标题或sourceSnapshot。未授权Workspace/actor/跨项目拒绝。
2. 仅BIND_MATERIAL、UNBIND_MATERIAL和仅含agentId的UPDATE_PROJECT可在旧来源撤权下修复；保留member/CAS/幂等/归档检查，绑定仍验证新目标且禁止deleted KB；不放宽一般编辑、AI、审批、发布、导出。修复响应仍按当前授权脱敏。历史不可变来源可能使解绑后仍受限，UI明确展示真实状态。
3. UI只在完整GET实际403且canWrite时请求repair-context；拒绝或404保持原错误，不伪造成功。重新读取受Workspace/路由/Abort保护。显示匿名绑定ID与解绑操作，复用既有命令。生成禁用；普通GET/任务轮询、来源表面清理规则不削弱。

替代A：完整GET静默脱敏，拒绝（改变既有拒绝契约）。替代B：修复命令跳过全部授权，拒绝（越权）。选择独立最小读取与明确操作白名单，复用宿主授权和领域事务。

## 实施与验收

先补Java/UI失败回归：受限刷新修复、member与viewer/非成员/跨Workspace、未知字段与全部来源数组不泄漏、旧来源撤权后安全解绑/员工更换/合法绑定、非法新来源/archived/版本冲突/幂等、普通业务命令仍拒绝。Java边界独立实现审阅，UI根代理负责；不并行编辑同一文件。

逐片执行dev、定向Java/UI与Vue类型；独立权限审阅后运行正常精确tree commit/push门禁。记录浏览器模拟与真实服务端/角色证据差别。设计为当前工程实施决定，正式维护人/业务QA签收继续待验收；不声称远端required CI生效。回退为revert本片，无迁移。
