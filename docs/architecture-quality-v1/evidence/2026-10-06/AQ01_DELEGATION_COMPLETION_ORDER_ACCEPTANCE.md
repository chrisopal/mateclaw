# AQ01 并行委派完成事件工程记录

## 问题与修复范围

基线 HEAD `42b18b8959d64cced5e59218b2d8a4d330e2506f`、tree `98a7a9e138f097c223761c4ef4f61558b64140db`，工作树初始干净，增量扫描 base `origin/dev`=`ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。对应此前委派夹具审阅登记的生产事件顺序风险，关联 AQ01 运行时终止边界；不代表正式 AC 全部完成。

并行委派原来等待原始 future，未等待其完成通知；模型已返回时，慢 SSE 通知仍可能晚于 delegation_end。现在批次局部 CompletionEvents 在同一 monitor 内去重、隔离通知异常并关闭通知边界。保留即时子任务通知；结果收集循环先发出所有 requestStop/cancel，随后 finish 补发未通知的已完成结果、关闭，再清理 relay/registry 并发送 end。取消回调在争锁前跳过 CancellationException，避免第一个兄弟的 cancel 阻塞后续停止请求。

没有新增依赖、线程池或通用事件框架。运行时依赖方向、身份/Workspace/来源/审批、单任务和异步委派、模型结果/使用量、DTO 字段及超时/required/optional 分类不变；无数据库或迁移修改。异常 future 补发保留原 CompletionException，与普通回调一致。

## 验证与独立审核

- 原实现确定性 RED：A 完成通知阻塞，B 已完成时 end 提前；A 通知阻塞、B/C 悬挂超时时 end 同样提前。2 项均因预期 TimeoutException 未发生而失败；不是编译/配置失败。
- 首轮 GREEN：2/2。最终 Delegate*Test 7 类共60/60，0 failures/errors/skips；新增5次执行覆盖正常顺序、timeout与failfast下两个兄弟及时停止、optional失败即时通知、通知 RuntimeException 不重试且不改变模型结果。保留全部既有55项断言。
- 新测试检查成功、timeout/failfast、optional及通知失败返回后的注册表为空与 relay stopper 总调用次数。共用 stopper mock 证明总次数，不证明独立 stopper 身份。取消后释放模型并等到 child finally 调用；这不是全局虚拟线程退出证明。
- 初始 dev `5_fj89vq` SCAN_PASS。中间 dev `roxqpmly` FAIL：Spotless 让存量注解行触发 STYLE-001；等值拆分字符串后最终 dev `b652f2p2` SCAN_PASS/submission_ready=false。原失败报告保留，未改规则/基线。
- Spotless 将既有 DelegateAgentTool 全文格式化，源码 diff 包含格式变化。JDK21 JavacTask 对基线与候选解析 AST 比较：唯一改变成员为 delegateParallel；imports、其他方法、字段及注解 AST 完全相同。最终 git diff --check 通过。
- 独立原生子代理 `/root/delegation_terminal_boundary_review` 只读审阅设计、生产边界和测试；提出的异常表达与清理断言意见已修改并回读，建议继续最终门禁，无新增阻断。角色最初固定模型调用不可用，随后用继承模型完成实际审阅；失败调用不作为审核证据。此结论不是维护人/QA批准。

实际命令：

```sh
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -B -pl mateclaw-server -am -Dmaven.compiler.proc=full '-Dtest=Delegate*Test' -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home mvn -B -Dquality.base=HEAD spotless:apply
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

正常 commit/push 的全量结果及精确 tree 在完成后写入 PR/外部交付记录，不把本文件尚未执行的门禁写成 PASS。当前工程证据未覆盖真实浏览器、真实 SSE 客户端、外部模型或业务验收。远端CI及 required enforcement 分别核验。

## 风险与回退

永久阻塞的同步 SSE 仍会拖延 end 和 relay/registry cleanup，但本片测试覆盖在已注册回调执行期间的慢通知不会阻止 deadline/failfast 后的兄弟停止请求。回调注册时可能内联执行的既有行为、收集时完成/cancel竞争均未改动。finish 备用扫描和异常 raw future 分支未被强制调度测试独立命中，不能称为完整分支覆盖。

本片可回退生产通知边界与新增测试，恢复原事件乱序风险；没有数据迁移或新持久化格式。不要回退累计 PR 中已用于新数据的历史/版本迁移。此前 OpenAPI HTTP EOF 根因仍未建立，不能因本片绿色视为已修复。正式 AC、维护人验收、Kingbase/生产切换和远端强制策略继续开放。

## 可复核证据

[归档](delegation-completion-order/delegation-completion-order.tar.gz) / [SHA 清单](delegation-completion-order/delegation-completion-order-manifest.json)。36文件，SHA-256 `ef675b34dbe63e334ae69c5921731d0d9c5f7c57d4c0baccfb1c50cfa491ba48`，无凭据匹配；逐文件归档回读校验通过。包含 RED/首轮GREEN/最终回归、最终源文件、JUnit、失败/最终dev、AST比较脚本与结论、独立审核记录。RED日志对应最初两例版本，归档源码为最终补强版本。
