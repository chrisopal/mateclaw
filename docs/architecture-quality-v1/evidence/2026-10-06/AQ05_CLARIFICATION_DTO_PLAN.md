# AQ-05 澄清保存类型化计划

HEAD f22aed6656b9dae0c788b672911d7e9dabdfb418，工作树 clean；初始 dev q78mq05_ SCAN_PASS / submission_ready=false。当前远端 37369407148 queued，不作为通过证据。

目标：SAVE_CLARIFICATION 的完整领域规则不再依赖 JSON path 判断。新增包内纯类型保存类（Draft / Status / Decision）与明确的旧 JSON codec，Service 只接线。复用已有 ProjectItems.find/saveItem、access.owner；不新增依赖、bean、表、权限服务或通用框架。

保持顺序：既有角色/operation/加载/来源授权/原始请求hash与回放/CAS/archive/shape/版本容量检查原位；澄清规则 question -> status -> requirement -> owner -> answer/source -> 最后 saveItem 的 id/version 检查。已回答覆盖伪造回答人/时间，OPEN 删除两项元数据但保留 answer/source；不升级客户确认。原 payload 深拷贝只做必要字段修改，保留未知字段、显式null、缺失字段、属性顺序及整项替换后移至末尾的语义。

生产范围：PresalesService、PresalesClarificationSave、PresalesClarificationCodec。测试范围：既有 PresalesCommandPayloadContractTest 补 HTTP/H2 行为刻画、新纯类型测试及既有 Integration/ProjectItems/事务回归。先在旧实现运行新增合同，再移动规则。SQL/receipt/原请求字节和来源权限拒绝需有断言，不能通过改旧期望变绿。

验收：纯类型状态/长度/调用顺序和短路；HTTP新增、回答、重开、回读、扩展/缺失/null保留；多错误顺序与数据库不写；完整旧回归、dev、格式、独立审核及提交门禁。JSON聚合存储、其他领域DTO、V2迁移和正式QA仍是剩余项；本片不能冒称全部领域类型化。

回退只恢复该分支旧实现及删除两个新增类，无数据迁移；无部署或生产数据操作。正式 V2/Delivery 规格路径仍待澄清，不凭空确定迁移表结构。
