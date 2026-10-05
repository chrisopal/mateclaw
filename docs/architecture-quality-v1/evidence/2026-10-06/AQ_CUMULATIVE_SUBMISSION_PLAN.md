# 累计架构整改提交前收口计划

沿用用户此前明确的提交/推送授权，仅architecture-quality工作树，不动原checkout，不合并/部署或修改仓库管理。HEAD 6ce0cd21592b661b6f79dd1726cf68a9b334e1e2，origin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93；已有PR5为本分支的草稿审阅入口，正式AC和remote required CI分别报告。

先记录所有tracked/untracked输入、字节摘要及范围；检查累计控制面和运行时/售前交互的独立审阅，再执行真实精确暂存树commit门禁。测试通过不关闭尚缺业务/迁移/环境证据的46项AC。若有真实失败，修复对应问题后重新暂存和检查，不降低规则。

提交前发现4份未跟踪的旧artifact-read日志/XML归档包含测试JWT（只报告计数，不输出令牌）。仅对匹配JWT脱敏，保存原压缩/解压摘要及修正说明、更新受影响归档清单；不改变测试断言/结果、源码或历史Git对象。原日志可在工作树外保留本地恢复副本，不推送明文。

生成物限当前任务的.playwright-cli/*.yml、.omx/artifacts/claude-ordinary-chat-20261005.md、.omx/state/discovery-panels/ralph-progress.json和output/playwright/discovery-panels文件。先核对用途、已有归档/引用和摘要，逐文件备份到工作树外并回读后移走，保存迁移清单；不删除未识别文件、不stash/reset其他修改、不调整ignore或门禁以隐藏测试输入。已有浏览器/审阅证据保留在正式evidence目录，并补充旧临时位置的说明。

暂存经范围核对的全部累计源码、测试、控制面及脱敏证据；核对git write-tree和实际输入，无部分暂存/未跟踪输入。门禁exit0且submission_ready=true后才按Lore协议提交，正常pre-commit/pre-push不绕过；使用push-checked.sh保活推送。读回远端SHA/PR状态，不能把本地通过当作远端CI强制生效。

回退：源码和规则可按提交revert；移出的临时文件按工作树外清单恢复。日志脱敏不应恢复明文到Git。业务数据、旧迁移和正式AC状态不在本片更改。
