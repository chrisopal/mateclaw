# T06 UI 验收台账

| 控件/状态 | 结果 | 证据 |
|---|---|---|
| 进入检查发布，运行/再次运行 | PASS | browser-check.cjs，三个真实模型 |
| 结果业务名称，无默认 IRI | PASS | 不可满足类型显示“传感器” |
| 检查详情展开/收起 | PASS | 三个真实模型 |
| 停止等待 | PASS（可控延迟响应） | browser-results.json |
| 超时/失败/不支持/草稿过期 | PASS（可控 HTTP） | browser-states-results.json |
| 390/768/2560 宽度 | PASS | 截图及 scrollWidth 断言 |
| 脏草稿/工作区或版本变化/迟到结果 | PASS（组件测试） | logicalConsistencyPanel.test.ts |
| viewer 禁止检查 | PASS（后端测试），浏览器 NOT_RUN | DraftReasoningIntegrationTest |
| 完整键盘导航 | NOT_RUN | 非本批完成声明 |

真实服务使用持久化 H2 库，未重置；所有模型位于独立 T06 草稿逻辑验收工作区。浏览器只操作独立上下文。因服务重启期间页面进入 forbidden，服务就绪后重新导航完成验收；未修改用户页面。
