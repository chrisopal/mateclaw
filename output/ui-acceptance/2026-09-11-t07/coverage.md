# T07 控件验收

使用本地持久化 H2 的独立 T07 工作区。两次停服离线备份，V209 迁移成功；未重置数据。

| ID | 控件/流程 | 结果与证据 |
|---|---|---|
| C01 | 检查发布导航、统一运行检查 | PASS：真实浏览器执行四项检查 |
| C02 | 四层状态、失败详情展开收起、业务对象名称 | PASS：无法成立的对象显示“传感器”；逻辑矛盾及来源待确认正确提示，定位进入参考资料 |
| C03 | 刷新报告、修改失效、重新检查 | PASS：修改名称输入失焦，旧报告失效；保存再检查通过；重启后报告回读见 restart-report.json |
| C04 | 发布说明输入失焦、取消再开、提交 | PASS：空说明禁用，填入可提交；取消后再开，真实发布并进入版本页；见 browser-publication-readback.json |
| C05 | 未检查/失败禁止发布，API 拒绝 | PASS：来源 CURRENT/PENDING 失败，填写理由并确认后通过；无报告/过期/矛盾直接 API 拒绝见 runtime-results.json |
| C06 | 成功发布重试 | PASS：API 同操作返回同一版本，见 runtime-results.json |
| C07 | 超时、错误、忙碌 | PASS：浏览器可控网络超时，两个入口显示忙碌，失败清除旧报告并锁定发布；重试实际 worker 成功。真实 worker 超时用后端受控测试覆盖 |
| C08 | 响应式、深色 | PASS：390/768/1366/1440/1920/2560 实际视口均无页面横溢；移动端重新加载检查；深色报告与弹窗截图人工查看 |
| C09 | viewer/Agent 不可绕过 | PASS（后端自动化）：viewer 不可运行检查、member 不可发布，prepare_publish 保留人工发布；非多角色浏览器复测 |

截图：checks-*.png、unsatisfiable.png、transport-error.png、publish-dark.png、published.png。

复跑 API：先运行 `python3 prepare_fixture.py --fresh` 创建独立合成测试工作区，再运行 verify_runtime.py。`python3 prepare_source.py --fresh` 是可选浏览器来源场景，创建后需先在界面填写理由并确认资料，再执行 verify_runtime.py。浏览器发布会消费草稿，之后需重新 prepare_fixture，不能继续复用已发布的草稿。

工具纠正：首次截图捕获了断点切换/弹窗动画，重新加载移动端并关闭截图动画后重拍；发布跳转断言需包含 ?revision 参数。来源确认按钮在理由为空时禁用属于正确行为。上述均不计为产品失败。

边界：没有做全站键盘、全站对比度仪器审计或每个角色的浏览器复测；没有原生 MySQL/Kingbase 运行；本批使用合成设备模型，未重跑新的完整自然语言智能体建模链路。
