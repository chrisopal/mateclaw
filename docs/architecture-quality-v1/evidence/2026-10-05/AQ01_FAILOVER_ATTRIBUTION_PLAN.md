# AQ-01：成功响应的模型归属修复计划

基于当前 HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2、origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93 和累计 dirty 工作树继续；初始 dev vee5jwlm 为 SCAN_PASS/submission_ready=false。上一批普通聊天已证明无 usage 回复归属，但备用身份仍错误，不能沿用旧通过覆盖该缺陷。

职责：现有 NodeStreamingChatHelper 为每次真正返回内容/工具的调用结果携带不可变 RuntimeIdentity；供应商来自正在尝试的配置，模型优先采用响应 metadata.model，缺失时使用该调用的已解析配置 modelName。FallbackEntry 保留模型配置名，旧两参数构造兼容；AgentGraphBuilder 两种图装配传入主模型配置，拒绝以 Java client 类名伪装模型。身份只属于本次响应，不保存在 helper 单例/ThreadLocal 上，不从失败或空响应创建身份。

复用现有 StreamResult 与两个 state accessor 的 usage 合并入口，写回 RUNTIME_MODEL_NAME/RUNTIME_PROVIDER_ID；ReAct 全节点共用该入口。Plan 的 step 内循环保留最后真正响应的身份，并在各现有 usage 出口写回；Plan 流在本轮有明确响应身份但零 usage 时同样保留归属。每轮显式清空响应身份，避免已配置模型被当作已执行模型。无新增业务域依赖/表/权限/事务/依赖，无迁移或规则调整。

先补现有 API 可执行刻画：真实 helper 主调用、多个失败备用后成功、usage 缺失/存在、失败不覆盖前一响应；使用实际两个状态构建器断言归属。先在旧实现得到 RED。再修复，新增兼容构造/缺模型配置/响应别名/停止或 partial 等反例。原认证/退避/健康/池/工具/取消回归不删除。

全应用探针仍执行真 main、登录、工具桥、落库和 HTTP 回读；增加本地主 provider 故意 401，再由实际备用链提供时间工具与答案，消息必须保存备用身份，主失败请求单独计数。保留既有八组合、SQL正例/零项目查询、已知项目工具负例和有界观察。外部服务是合成 loopback，不声称真实供应商/方言/正式QA。

每轮 Maven 前归档实际 XML/log/source，开发修改与验证串行；Spotless 首次格式化差异独立记录。运行 dev、适用测试、Spotless、实际生产 ArchUnit、独立技术审查，并更新证据与台账。控制面/测试审查不替代维护人批准，正式 AC 与完整提交/远端 CI 保持真实未完成状态。回退本片响应身份传递会重现备用归属缺陷，不动历史迁移或其他切片。

真入口备用链夹具首轮暴露额外行为缺陷：主模型401后备用模型完成工具和答案、ReAct最终状态正常，但每个HTTP流仍先收到主模型的终态error SSE事件，错误卡片误报。保持先行失败集成证据；在helper回归增加“备用成功零error”和“全链失败恰一终态error”两项刻画，再把终态错误广播移至failover/retry路由收敛处。各失败尝试仍记日志；切换warning仍保留；只有最终失败才给前端错误事件。
