# AQ-06 请求摘要兼容设计独立审核

只读代理/root/hash_compatibility_review核对实际Service、V2 helper、Generation、DTO、Repository、客户端错误边界及本片设计/探针；未运行测试/构建、未编辑、未派生代理。认为新写V2、按stored格式双读、legacy同摘要但有编码歧义时409可作为下一片实施方案。不是工程通过或生产迁移批准。

主任务已将以下发现并入设计：

1. versionOf未知格式抛IllegalArgumentException，需窄范围转业务409而非500/legacy fallback。
2. 只扫描实际同一份encodedRequest；正确跳过合法代理对，保留emoji、字面量转义、U+FFFD及全角问号，补相邻高代理/高低低/属性名反例。不能在旧算法前加storageJson。
3. Repository只存hash+response，create的null/owner归一、UPDATE_PROJECT过滤字段和未知extension参与hash说明无法从当前body/receipt/revision通用恢复原始请求。拒绝部分真实相同legacy请求是兼容取舍，不用猜测补齐历史事实。
4. 旧回执存储不改写不等于HTTP直接返回原敏感正文；维持Service原授权→来源→replay→commandResponse裁剪位置。
5. 摘要对象是既有DTO envelope，不是HTTP原始字节；Create固定record与Command开放payload分别描述。
6. 新错误码同步客户端conflict集合与防盲提测试。滚动发布需所有reader先兼容，回退版本仍须双读；补V2写后兼容reader回放。Generation的byte-writer摘要保持，补含问号历史任务重试零enqueue正例。

依据：PresalesRequestHashV2.java:38、PresalesExceptionHandler.java:13；PresalesService.java:190/401/789/812；presales/repository/PresalesProjectRepository.java:274；PresalesDtos.java:12/41；PresalesGenerationService.java:59；前端shared/state.ts:16。行号对应本次读取的累计工作树，后续变更应按符号重新定位。当前缺陷仍待真实Service/HTTP RED、实施、H2/MySQL回归及独立代码复审，不能因该设计审核而关闭AQ-06。
