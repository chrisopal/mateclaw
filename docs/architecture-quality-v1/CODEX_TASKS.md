# 优先整改任务与验收

以下是实施任务，不是本次已经修改仓库。每个任务需要独立或可审阅的小 PR，报告真实检查结果。

| ID | 优先级/前置 | 工作与主要文件 | 完成条件 |
|---|---|---|---|
| AQ-00 | P0，首先 | 实际 HEAD/AGENTS/依赖核验；安装 rules/guard/hooks；整合 formatter/POM/pnpm lock；独立审查 bootstrap | 67 个门禁自测通过；实际开发环境工具就绪；required CI 首次 bootstrap 流程可解释；无已存在文件被盲覆盖 |
| AQ-01 | P0，AQ-00 | `ToolExecutionExecutor`、Agent 装配、PresalesToolPolicy/Runtime → 通用 ProjectExecutionOptions/Policy/Revalidator | 普通聊天/售前/投标行为刻画通过；底座不再依赖具体工作台；关闭模块互不拖垮 |
| AQ-02 | P0，AQ-01 | auth/workspace/source-access 公共入口；PresalesAccess、BiddingAccess、ContextProvider、工具读取适配 | 权限矩阵及 Context/工具/历史/导出交集测试通过；保持不同批准政策，不新增假 ACL |
| AQ-03 | P0，可与 AQ-02 分支协作 | BiddingController.task → TaskQueryService/Repository；其他目标 Controller DAL 清理 | controller无SQL/DAO；审查人解绑后历史任务访问仍遵循原政策；错误不泄露资料 |
| AQ-04 | P0，AQ-00 | ontologyApi.scopedConfig → 公共 workspaceRequest；presalesApi/biddingApi 适配 | 工作区切换、JSON/multipart/Blob/ArrayBuffer/AbortSignal/409 全部回归；不覆盖自定义 transform |
| AQ-05 | P0，AQ-03/04 | 明确 DTO 和稳定错误；内部 ObjectNode 到类型边界；保留旧 API wire shape | 类型检查、错误行为、旧消费者快照测试通过；只读历史未改写 |
| AQ-06 | P0 后半，AQ-01/02/05 | 与 Presales V2 的单写迁移任务合并：对象/修订/任务/输入依赖、SQL分页 | 备份/隔离恢复与映射核验；两人并发互不影响；相关依赖变化拒收；旧字节和handoff摘要一致 |
| AQ-07 | P0，AQ-01/03 已清零后 | 安装 `WorkbenchArchitectureTest.java`；审定 ESLint/依赖规则；封口 policy 路径 | 真正编译运行的架构测试有非零类；清零范围不能再引入依赖；不能 freeze 新违例 |
| AQ-08 | P0，AQ-00–07 | 关键回归、真实 CI、CODEOWNERS/分支保护、禁止绕过验收 | required job 非success不能合并；新push使旧审批失效；QA批准P0完成 |
| AQ-09 | P1，P0通过 | 密集源码格式化独立PR；页面/composable 拆解；i18n/token/公共交互 | 非目标功能不改变；字符内容不改；两主题/窄屏/工作区切换视觉与功能回归 |
| AQ-10 | P1，AQ-06/09 | 性能、索引、依赖影响图、日志可观测性、异常路径整理 | 保存测量条件/数据规模/SQL计划；不报告未测提速 |
| AQ-11 | 持续 | 后续售前/投标/Delivery 功能受已封口规则约束 | 每个业务PR带任务与验证证据，不复刻旧聚合/专属runtime/伪审批 |

## 1. 第一次交给 Code Agent 的范围

**第一轮只做 AQ-00、AQ-01 的实施准备与最小职责改造，不启动 Delivery 和 Presales 的新增业务功能。**

AQ-00 要补齐工具版本与 lock、安装规则并建立基线报告；AQ-01 应先补测试，再替换策略接入。不要先搬整个目录树、重命名数据库和全仓格式化。

## 2. 每个任务的固定交付

说明该任务触及哪些职责、哪些旧行为不得变化；列出实际变更文件、依赖方向和新增测试；给出 dev/commit/CI 证据；有未运行数据库/浏览器/模型/Office 时明确标记。无条件失败不得以“其他模块历史问题”自动跳过。

发现历史失败时：记录 base 与 candidate 的同环境对比，用独立问题单处理；只有原有且获授权的隔离策略可以继续存在。Agent 不能通过下调阈值、删断言或重写快照维持绿灯。

## 3. 与已有规格的衔接

| 本规格 | 售前升级/交付规格的关系 |
|---|---|
| AQ-01/02/04 | 公共底座先建立；之后各工作台只适配，不新造同类服务 |
| AQ-05 | 售前类型化保持旧消费者契约，V2新增API仍按其版本方案 |
| AQ-06 | 直接复用售前 V2 的迁移设计、任务映射与黄金样本；不重复建立另一套表 |
| AQ-07/08 | 成为两份业务 Spec 的共用完成前提 |
| AQ-11 | Delivery 必须接已通过的公共接口，不复制旧 Presales 的整项目JSON |

多 Code Agent 并行时登记任务和文件所有权；公共 POM、路由、权限接口、Flyway 版本号和配置由指定整合者负责。同一时间不要分别创建名字相近但语义不同的公共服务。
