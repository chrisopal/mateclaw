# AQ-04 公共 Workspace 请求整改计划

起始 HEAD 5d56b1db0c41919279005edb7948b1ed3ec28e60，base origin/dev，独立工作树干净；先执行dev，完整证据按实际报告。范围 src/api/workspaceRequest.ts、semantic/presales/bidding 请求适配及请求合同测试/台账；不改依赖、权限、全局拦截器、门禁/runner、后端或迁移。

已核实：scopedConfig 位于语义内部、售前和投标横向借用；三个包装器以spread覆盖调用者transformRequest；投标上传又复制默认transform与pin。先锁现有请求/页面行为，使用真实Axios实例与实际拦截器、受控adapter补红例：自定义function/array序列化被覆盖、transform最后改Workspace、config.signal被覆盖。再提取公共完整config组合入口，选调用者transform（含空数组）或默认转换，最后pin捕获的Workspace；保留this/headers/params/data/responseType/transformResponse及signal、Blob/ArrayBuffer和错误。公共入口不解领域envelope、不构造权限、不改IDs；领域保持各自错误适配和wire契约。

三域接公共入口；binary读取保留原类型，上传直接使用FormData默认转换，不手工设boundary，不重复pin/serialization。删除跨域私有helper调用；语义内现有调用统一适配，若有外部旧引用需逐项迁移并复核，不以兼容re-export维持横向依赖。不得删除原断言或改存量baseline以变绿。

验收包括真实transform/interceptor顺序、JSON、function/array/empty/custom返回字符串、multipart字段、Blob/ArrayBuffer字节、AbortSignal（预取消与在途）、409/语义错误及Workspace中途切换；受控transport不等于生产服务端/角色QA。必要时用独立本地HTTP服务证明multipart和binary真实收发。独立审核配置组合、header终值、所有调用路径和测试控制面增量。

定向/全量UI、type、nonfix lint、固定formatter、dev与完整精确staged tree门禁、正常commit/push钩子、远端/PR读回；AC-13–16记录工程证据而保留正式QA状态。规则封口若需改policy属于控制面，另片独立审查，不夹带或弱化门禁。

全量门禁定位既有biddingDownload夹具只mock http.get，而公共入口改用http.request；纳入同域测试装配迁移，保留原字节/路径/mode断言，并经独立增量审核，不修改生产实现或runner以适配mock。
