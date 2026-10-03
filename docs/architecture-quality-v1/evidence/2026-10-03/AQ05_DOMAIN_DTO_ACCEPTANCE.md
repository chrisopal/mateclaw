# AQ-05 领域响应DTO与handoff工程验收

项目业务集合按Requirement、Clarification、SolutionRevision、GenerationTask等命名DTO区分，Artifact描述发布manifest而非二进制内容；Handoff v1经过捕获Workspace/项目身份及结构检查。返回原对象/原JSON，冻结与不可信字段不重写。它是当前wire的客户端原始响应合同，不是服务器领域批准、完整状态机或V2迁移验收。

起点ce293693fcca7a3250e5950fda02083a7e0adbfb，固定origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；源码8fdb3d3652b6f7464c9997269c126d28f28741bb，tree 3c7af1d5b2f5008c45b5667b1b65337bb9ba96a8。计划AQ05_DOMAIN_DTO_PLAN.md，manifest domain-dto-test-results.json；源文件SHA、日志原输入SHA/归档SHA与遮蔽计数已逐项核对，显示副本仅规范行尾/末尾空行。

## 修改职责与兼容

| 源码 | 变化 |
|---|---|
| api/presalesDomainTypes.ts | 六类命名响应DTO与材料/基线/fit/review/release、章节/响应、coverage、presentation；从API搬出历史Record/EditorForm及兼容导出。集合id/task.status保持原必需，其余历史字段保留optional。 |
| api/presalesApi.ts | 项目集合关联各自DTO，PresalesTask兼容别名；handoff request未知JSON经过decodeHandoff。现有公共类型名从纯类型模块再导出，无新增依赖。 |
| api/presalesResponse.ts | 检查新增已声明元数据、版本、manifest与各skill结果分支；handoff v1 exact workspace/engagement/caseRef/核心实体/数组校验；迭代reference语境与两个WeakSet保留深层遍历及普通记录严格检查。 |
| components/PresalesSolutions.vue | 仅删掉baseline find回调不准确的Record注解，推导真实nullable引用；模板/计算行为不改。 |
| __tests__/domainDto.test.ts | 56个新增合同：实际输出形状/JSON身份、已声明字段反例、七类模型分支、handoff身份/结构、混合冻结来源、引用nullable隔离及命名类型编译关联。 |
| __tests__/presalesWorkbench.test.ts | 只补原pending handoff mock的真实必需信封，保留原Private handoff标记与所有取消/撤权断言。 |

saveItem实际生成authorId/previousId，澄清回答actor/date，方案baselineVersion/provisional/fitGapRefs和nullable coverage.percentage，任务pin/queue/terminal及发布/presentation manifest字段均按源码盘点；历史读取不补齐，不能把新生成保证直接变成所有历史对象必填。服务端title/question等用asText检查，不保证原JSON节点类型；本片仅描述客户端经过准入的值。日期和status等raw文本原样，不用展示词表重新定义批准规则。

模型信封schemaVersion/needsHumanReview/assumptions/unknowns/warnings仍unknown；服务端coercive校验不证明原节点规范化。items/capabilityMaps/cases/solution/solutionDraft/review/reviewDraft只检查已声明读取结构/字段，不升级AI权威。context复制集合/operational_record、sourceSnapshot/assertion/rejectedOutput、handoffSnapshot保持opaque。

BaselineReference的ontologyRevisionId/evidenceIds保留语义事实nullable；只在references语境允许，普通记录保持原string/string[]限制。WeakSet分别记录普通/引用验证，共享对象不能借nullable引用语境洗白材料。Handoff.sourceRefs则是发布时基线对象与澄清原节点的混合数组，保持unknown[]并只检查数组，不能当string[]或纯引用对象数组；普通record.sourceRefs仍string[]。这描述客户端解析，不证明真实null事实完成服务器基线批准流程。

## 正反例及检查

旧4文件181项通过。旧准入34失败/3通过（37），证明非法已声明字段和错项目handoff原来被接收；旧vue-tsc实际exit2六个字段/返回形状错误。首轮实现38合同绿但类型检查揭示旧mock缺信封/漏字段，补正后376通过。引用nullable正例先1失败/39通过；实现漏插formatter展开后的entryReference引发两条TS2304及16运行失败，回调Record注解也不兼容，修正后378通过。没有删除旧断言或跳过测试。

独立MEDIUM发现冻结sourceRefs纯对象约束不符发布合并行为；独立decoder混合夹具拒绝，新正例真实1失败/40通过后改为unknown[]。最终七类模型分支正反例及命名类型关联加入，56新增、全售前16文件394项通过。独立初次3文件142/六文件lint/vue-tsc通过，最终窄复核56/type/三文件lint exit0，MEDIUM关闭无新增发现。LSP Transport closed，无LSP/AST门禁PASS，也不是维护者控制面批准或业务QA签收。

- 初始dev afzycwss、实现中dev fyd8ff44与最终dev-last实际报告见归档，均SCAN_PASS，不替代提交授权。
- 最终vue-tsc、六文件零警告eslint及固定Prettier exit0。
- 精确源码门禁：staged lyidtaak PASS/submission_ready=true、hook 4sgxbvmw PASS/submission_ready=true，target_identity均等于源码tree；完整UI1093、Node5（含18项HTTP/分页子进程）、固定格式/lint/type/ID精度、enterprise/classic两构建通过，全Vitest无socket hang up/ECONNRESET。
- Java/cost-tool按固定前端影响规则NOT_APPLICABLE；不以旧后端绿灯冒称本批已测。报告、逐步骤日志、编译与运行红例均在domain-dto-tests。

## 未完成与回退

新增声明字段错误类型准入收紧，需要生产历史数据抽样；未运行真实服务器/浏览器handoff端到端，不证明来源、checksum、角色或并发正确。客户端原始领域DTO与有限展示Known/Unknown/Missing仍不能替代完整领域状态政策、稳定错误和server schema验证；后端ObjectNode用例、SQL分页/V2单写/三方言迁移/备份恢复/旧消费者黄金字节、异步/模型/两主题窄屏与独立QA继续待办。远端requiredCI NOT_VERIFIED，46正式AC全部NOT_RUN，ADR Proposed，PR draft。无部署/合并/仓库管理或生产数据操作。

回退恢复本批六源码及对应计划/证据，只恢复宽泛客户端类型/准入，存储和文件字节不操作；原项目WIP未触碰。无新依赖/旧Flyway修改，服务器授权/事务/模型批准保持原权威。
