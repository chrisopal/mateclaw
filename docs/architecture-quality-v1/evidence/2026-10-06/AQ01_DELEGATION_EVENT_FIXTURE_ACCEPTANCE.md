# AQ01 委派事件测试夹具工程记录

项目版本扩容提交 `6f85271c7ece1812800a4a14696b9c69788df757` 的 commit 门禁 cyxm2o18 通过，精确tree `320bcb46114ba0ed14d99c93101f685d6676cc80`；其正常 push 门禁 awvoz5n2 失败，未发生远端更新。失败是既有 DelegateEventSequenceTest 的两条 chat stub 未被调用，原日志明确记录0秒超时。失败12文件归档SHA `30e1f7adbc7c4b01e921d6f637320a2da966d0800f0f25ff0aa2c31d91a6de0f` 保存于本地工程材料。

Mockito @InjectMocks 不处理 Spring @Value，测试未设置 parallelTimeoutSeconds，默认0；生产配置默认300秒，其他委派测试已显式设置3秒。原测试仅检查代理名称，超时结果也满足。先增加实际ResultA/B及成功汇总断言，配合临时200ms子任务调度延迟，隔离RED真实返回total2/success0/timeout2并失败。随后设置3秒测试预算，删除临时延迟；最终6类Delegate*Test共55/55，无skip。原事件顺序/Mockito严格模式及超时、取消测试保留，未改生产配置或代码。Spotless对既有测试全文规范化，功能修改限setup和三条成功断言。

初始dev bbdexfqh、最终dev oalyi_xd均SCAN_PASS/submission_ready=false；格式和git diff --check通过。独立delegation_fixture_review确认本片修复，LSP工具两次transport closed，因此类型/编译证据来自实际Maven。独立技术审核不替代维护者批准；最终完整commit/push门禁按精确候选tree另行记录。

## 仍待处理的独立发现

审阅指出 DelegateAgentTool 的whenComplete阶段没有纳入allOf等待，子任务完成事件可能晚于delegation_end；是既有生产事件顺序风险，也可能影响测试最后事件断言。本片不宣称并发事件顺序已完全修复，不删该断言、不用lenient隐藏；下一片须用可控延迟/同步点复现，再设计终态前事件收束与超时/取消边界。此发现与Mockito夹具0秒预算不同。

回退仅恢复测试源码，会恢复错误夹具；无数据库或部署操作。正式业务验收、事件并发风险和项目容量的未测方言仍开放。

[证据包](delegation-event-fixture/delegation-event-fixture.tar.gz) / [摘要清单](delegation-event-fixture/delegation-event-fixture-manifest.json)，SHA-256 `eff2f38706637817f14fa2272c789b158f6a08115bb9b244bc7a6c187e245c60`；源码、RED/GREEN日志、JUnit和审阅记录已归档回读。

## 后续生产修复

本文件登记的完成事件乱序风险已由下一片复现并实现修复，详见 [完成事件工程记录](AQ01_DELEGATION_COMPLETION_ORDER_ACCEPTANCE.md)。本文件原有夹具验证范围保持不变；新片的提交/远端结果须按其精确树单独核验。
