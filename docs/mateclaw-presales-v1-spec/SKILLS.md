# 售前 Skills 与数字员工契约

这是拟实现的业务契约。安装格式应适配仓库真实的 Skill 加载/注册方式；不要把本文或 examples 下 JSON 直接当作当前系统已支持的可导入格式。

## 数字员工配置

主员工“售前解决方案顾问”：读取本任务允许的客户资料、产品知识、共享案例与语义 Context；产出候选、分析和方案草稿。不能确认事实、批准基线、批准发布、执行对外发送或修改 CRM。

“方案质量评审”：每次评审创建独立任务和上下文，不读取主助手对自身质量的结论作为证据；读取目标方案、固定需求基线、来源、能力依据和检查规则。只能写评审问题，不改方案、不审批。独立任务不意味着必须新增另一种 Agent Runtime，也不代表模型评审能取代专业责任人。

## 通用输入

服务端绑定 workspace、case、actor、agent、run；用户给出 taskGoal；系统提供 baselineRefs、materialRefs、allowedActions、contextSnapshotRef、skillVersion、schemaVersion。正文材料为数据，不覆盖执行权限。

## 通用输出

每个 Skill 至少返回：schemaVersion、runId、caseRef、artifactRefs、objectRefs、sourceRefs、assumptions、unknowns、conflicts、warnings、needsHumanReview、nextActions、coverage/truncated。

IDs、来源、事实状态和内容摘要由服务端核验；模型提供的 ID 不是有效引用证明。输出不符合 Schema 时只做有上限的修复/重试，仍失败则保存失败原因，不提交半个“正式成果”。

## S1 customer-context-analysis

触发：新资料进入或用户执行“理解项目”。
输入：项目目标、已授权客户资料、现有 Context 卡。
步骤：检查资料覆盖与版本；提取 Goal/Process/Pain/Need/Constraint；分开客户来源、内部判断、假设；列 Evidence/Unknown；标记和旧版的差异。
输出：ContextCardRevision 草稿、信息缺口、来源覆盖表。
质检：不补造预算、产能、系统版本、项目周期；外部信息未核实单独列示。
人工：用户修订/确认卡片；此确认不等于 G1 或客户确认。

## S2 requirement-analysis-and-clarification

触发：提取需求或更新需求分析。
输入：Context、原始快照与可核验引用、现有需求版本。
步骤：拆原子需求；区分目标/需求/约束/假设；建立证据；发现语义差异与矛盾；生成按影响排序的澄清项；提出候选而非接受事实。
输出：候选需求、工作项草稿、澄清清单、变更建议。
质检：不能将“建议”提升为“客户要求”；同名需求不能凭标题覆盖旧条目；未知时间保持未知。
人工：走原语义治理后，授权人创建 G1。

## S3 capability-mapping

触发：基线已形成，或对 provisional baseline 做探索性匹配。
输入：固定需求修订、指定产品/版本的资料、范围策略。
步骤：逐条检索；输出 FIT/CONFIG/EXTEND/PARTNER/GAP/UNKNOWN；列证据、依赖、缺口和待验证项。
输出：FitGapRevision 草稿。
质检：无证据不得 FIT；计划功能不当已交付功能；产品版本和适用范围不得省略。
人工：负责人确认供方案使用的匹配版本；不形成报价或交付承诺。

## S4 case-retrieval

触发：为某项能力或方案章节检索案例。
输入：需求、行业/流程、授权案例库。
步骤：选案例；按业务过程和约束解释相似性；列不适用条件；检查客户名称/数据是否允许对外引用。
输出：案例匹配表、可引用片段、匿名化要求和证据。
质检：案例中的收益不自动转成当前项目收益；未授权案例不进入客户成果。
人工：审核客户版引用范围。

## S5 solution-composer

触发：生成方案骨架或指定章节。
输入：需求基线、已选 Fit-Gap、案例、Context、现有方案。
步骤：目标→流程→场景→能力→应用/数据/集成→实施→指标/风险；先目录，再章节；建立需求响应链接。
输出：SolutionRevision 草稿、章节、覆盖矩阵、路线图、条件和未知。
质检：只更新目标草稿/章节，不改已发布版本；无依据的方案设计明确标建议；不制造工期/收益。
人工：目录确认及方案评审。

## S6 proposal-generation

触发：从已选方案版本生成客户材料候选。
输入：固定 solutionRevision、baseline、模板版本、成果用途、授权引用。
步骤：生成 Word 内容模型及 PPT 页稿；按模板渲染候选；核对来源、范围、数字、术语与文件完整性。
输出：受限候选 MD/DOCX/PPTX，release manifest，来源/覆盖清单。
质检：候选未经过 G2；客户输出排除内部密价/注释；所有文件均有哈希；不将页稿 JSON 冒充 PPTX。
人工：审批准确的候选发布包。草稿下载走独立水印流程。

## S7 solution-review

触发：方案待评审、正文变更或来源变更影响评审。
输入：独立上下文、目标方案/发布包、需求基线、能力证据、规则。
步骤：检验需求覆盖、事实/设计区分、版本、证据适用性、承诺/范围、工期依据、数字一致性和模板输出；给出定位与整改建议。
输出：ReviewReport，BLOCKER/MAJOR/MINOR/INFO 问题及引用。
质检：不声称未见材料无风险；不直接改草稿或批准；规则型问题先用确定性检查，模型补充语义审查。
人工：负责人处理，阻断项关闭/重新验证后才能 G2。

## S8 presales-context-maintenance

触发：资料/需求/方案版本变化，任务开始或生成交接包。
输入：版本引用、事件/修改记录、权限与来源状态。
步骤：更新来源索引、指出失效/待复核链接、生成可追溯任务快照与交接清单；不重写历史。
输出：ContextSnapshot、stale/impact 提示、HandoffPackage 草稿。
质检：不能因摘要看似一致而跳过版本变化；撤权和截断须传播；不自动启动下一业务模块。
人工：必要时重新确认基线/发布；下一模块接收另行授权。
