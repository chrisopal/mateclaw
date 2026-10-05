# AQ-08 真实远端 CI 工程证据

GitHub Actions [run37376295073](https://github.com/chrisopal/mateclaw/actions/runs/37376295073) 于 2026-10-05 21:47:29 UTC 完成 success。Engineering verification 与 Engineering Gate Required 均 success，真实执行了 checkout、trusted-base bootstrap 核对、锁定工具安装、base-owned runner 与证据上传；不是本地报告替代远端结果。

## 身份与实际检查

- PR #5 source HEAD：08acb836ee6ad3ec2713410d90b4e23c52ea219c。
- base：68d8fc536b6d3a4b9c9064e7322060bd78869faf（codex/aq03-task-query）。
- 被检查的 merge commit：7d2ed26f0bc305563f981cdfb8295caa94f9b60a；GitHub Git API 回读两个 parent 为上述 base/source，tree 为 da79a9e395c4d8dfe926ee13180a1c2baa12a2fb，与该本地提交 tree 一致。
- 下载的 report.json：mode=ci、PASS、submission_ready=true；target_identity 为上述 merge commit，检查时间 21:31:00–21:47:18 UTC。
- Java 共 6723 项，执行通过6652、跳过71、0 failures/errors。与本地6653/70区别按远端实际记录报告，不冒称所有环境均运行；环境条件跳过包含 MySQL、PPT、OWL、PDF 与路径环境等测试。
- UI Vitest1195、Node5、cost16；适用Java/UI格式、lint、类型、ID精度、enterprise/classic构建及成本工具语法检查均通过。
- trusted base 的 guard 自测72，candidate guard自测93；两者分别记录，不能把candidate测试数冒称已安装到trusted base。架构新增0、存量2438，未改基线。

此前 run37369407148、37373046648 因 hosted Runner 分配失败，验证 job 零步骤后取消，Required 聚合失败；历史失败没有删除或改写。本次另一个正常触发的 run 已真实取得 Runner 并通过，不是人工覆盖状态。

## 归档和边界

[22项脱敏归档](remote-ci/remote-ci-37376295073.tar.gz) SHA-256 `f3860d3173954d5701e79b7f96df5dc88831b503b09db5a816392e291daf2967`，包括下载报告/日志、GitHub run/job/merge/分支状态回读；32处测试凭据脱敏。manifest分别记录原始和归档摘要，逐文件验证通过。

目标分支状态回读仍 protected=false；本任务未改分支保护、required checks、绕过者或审批设置。Job名称包含Required及单次运行success，不等于仓库已禁止无检查合并。新push后需验证新候选；本结果不覆盖后续项目台账组件变更。

AC-40–44 的真实违规PR/直推/审批失效/控制面破坏流程、独立维护人和QA签收仍NOT_RUN，全部46项正式AC不因这一CI结果自动关闭。AQ-08仅补齐真实执行与回读证据，强制生效和业务验收继续开放。
