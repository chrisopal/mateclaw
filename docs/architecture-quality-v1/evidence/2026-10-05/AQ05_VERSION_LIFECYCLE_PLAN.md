# AQ05 版本递增边界修复计划

起点 HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`、base `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`，初始 dev `_k_maud1` SCAN_PASS、submission_ready=false。上轮精确修订修复属真实进展，本轮保留完整目标及WIP。

- 范围核对：Service命令、ProjectItems条目、Coordinator失败兜底与重启恢复四类递增，及GenerationController计算已接受版本。现有int数据库/DTO契约不在本片扩容，不改Flyway。
- 先做真实RED：HTTP/H2项目达到MAX、最后一次可写与原回放、坏JSON版本不能别名匹配、可变/不可变条目溢出回滚；实际H2兜底/恢复不能写负数且不阻断其他项目恢复；生成入口MAX及MAX-1无执行依赖调用，MAX-2合法排队/重试。
- 复用Items的精确正整数边界增加受检递增，非法现值409 VERSION_CONFLICT、耗尽409 VERSION_EXHAUSTED。Service保持授权/来源/回放/CAS/归档前置，递增能力检查置于命令副作用前。条目在移除旧项/设置previousId前检查。
- 生成保持回放/CAS优先，启动前检查启动及收尾各需的一次递增；这不提供并发版本配额预留。已有生成期间其他写入耗尽剩余空间时，最终持久化仍应明确拒绝，不能伪造完成或篡改版本。
- Coordinator直接写入复用同一受检递增，并在写入前核对body版本与物理列版本精确一致；补两条实际H2负例，不把合法SQL列当作静默修复坏JSON的许可。错误由现有日志路径记录，恢复继续其他项目。已耗尽项目的RUNNING历史可能保留，需独立数据/版本迁移后恢复；不得通过同版本覆盖、回绕、跳过CAS或假FAILED绕过。此项是有界int表示的明确运行限制，不宣称完整长期版本迁移已完成。
- 受影响生产文件：Items、Service、GenerationController、GenerationCoordinator。无新依赖/bean/权限/来源/全局JSON/hash/schema；保留旧合法wire、回放、取消和冻结读取。测试与证据随同，全部相关回归、Spotless、独立审阅与dev后报告真实身份。Maven/门禁运行期间不编辑。

回退恢复旧算术会重新引入溢出；无数据库回退操作。本批不提交/推送/部署，不关闭正式AC或远端CI要求。
