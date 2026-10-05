# AQ-09 工作台提交与下载职责拆分计划

状态：实施中。沿既有AQ-09页面职责方向推进，不改变业务协议；正式架构/业务签收仍开放。

HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93，codex/aq01b-execution；103项dirty。仅修改architecture-quality worktree，保留全部累计WIP。初始dev s_qhirgn为SCAN_PASS/submission_ready=false。

## 问题及设计

PresalesWorkbench仍在页面中执行command/save、批准/归档确认、handoff/file下载，混合路由、模板和异步业务。两类责任各自有明确生命周期，不合并为整页god composable。

1. 域内usePresalesMutationSession负责command、editor save和确认后的approve/archive提交。复用既有presalesApi、prepareEditorSubmission、presalesError；页面提供原scope、receipt、唯一saving锁、权限getter、acceptMutation以及reload/navigation/UI确认回调。编辑会话仍由原editor拥有，响应只能关闭捕获的编辑会话。编辑后继续员工执行通过原执行会话回调；不复制scope、项目状态、receipt缓存或引入store。
2. 域内usePresalesDownloads负责已发布/预览/草稿与handoff下载。保留请求前来源限制、返回时scope和项目对象身份、Workspace/project精确判断、错误隔离、文件名及1000ms URL释放。收敛两处Blob下载DOM代码，不能合并二进制/JSON请求或改变内容。
3. 页面仅装配两者，仍拥有路由/Workspace/布局、项目接纳/轮询和显示策略。模板/style保持原字节，无视觉设计改动；如类型推导循环，显式返回类型解决，不用any/assertion掩盖。

取舍：模块会增加少量显式依赖，但归拢完整用例并删除页面实现，不添加pass-through wrapper。拒绝新通用提交框架、通用文件服务、跨feature私有helper、新依赖和多份权限规则。服务端仍是权限/事务/发布权威；前端检查不授予权限。ID string、精确expectedVersion、operationId重试稳定及409输入保留不变。新摘要歧义错误仍为conflict。

## 顺序和验证

- 保存本片before源码及测试，运行原售前回归。确认当前页面对保存/重试/角色/来源修复/确认失效/下载晚到已有保护；不足则在旧页面先加刻画并跑通。
- 提取提交责任，小步运行相关回归和dev；再提取下载责任，跑同样检查。修改后补模块级边界合同，包括晚到success/error、取消确认、并发锁、来源限制、编辑会话和URL释放。
- 检查本片diff、模板/style字节、无旧函数残留/重复策略；使用项目Prettier配置独立格式化，随后非修复ESLint/Prettier、vue-tsc、全部UI/Node/精度及enterprise/classic构建。
- 独立只读代码及测试审核，归档真实日志/JSON/source SHA；最后dev与工作树身份比对。不是commit门禁，不提交/推送。

## 边界和回退

主修改范围：页面、两composable、页面及两模块测试；文档/证据由主任务维护。不得编辑Java/迁移/依赖/门禁/主题/语言包或重置其他WIP。回退恢复本片before页面/测试，删除本片两模块和独立测试；保留已有查询、编辑、执行拆分与摘要修复。

真实浏览器/双主题窄屏、后端角色/模型、正式QA、完整V2迁移及远端CI不由本片单测替代，全部正式AC保持NOT_RUN。
