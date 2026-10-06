# AQ05 成果读取的真实 MySQL 组件验证

HEAD `6ce0cd21592b661b6f79dd1726cf68a9b334e1e2`，固定 origin/dev `ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93`。本轮验证未提交候选中的成果读取修复，不修改产品源码、迁移、权限或请求 hash 策略。计划见 [AQ05_ARTIFACT_MYSQL_PLAN.md](AQ05_ARTIFACT_MYSQL_PLAN.md)，实际结果和源码、编译产物、日志、文件摘要见 [artifact-mysql-results.json](artifact-mysql-results.json)。上一轮六个修复源码指纹与当前一致。

## 实际运行与隔离

执行 `python3 /tmp/mateclaw-artifact-mysql-probe.py`，exit 0。runner 用 JDK21 javac 编译当前 Reader、Repository、ProjectItems、Renderer 和探针，11 个 class 摘要已归档；本轮编译目录放在实际 Java classpath 首位，避免 target 中旧类替代当前源码。探针调用实际组件与 JdbcTemplate，使用独立 MessageDigest SHA-256 计算期待值。

自行创建的 MySQL 容器 `mateclaw-aq-artifact-efd2691be8`，ID `8a5d0822a18dd98e83a32ac2a6db8ac39d66749166f7636de81ecb9fd0df709a`，实际服务版本 8.0.46。使用已缓存固定 image `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`、loopback 动态端口 59335 和空 schema `mateclaw_aq_acceptance_8d49818511`。URL 和 schema 名有显式限制；建表前检查四张 fixture 表不存在，核对数据库产品与 catalog。

仅执行已发布 MySQL V211 建立最小存储 fixture，未修改该迁移；这不是完整 Flyway 初装、V219 迁移或生产升级验收。密码通过临时受限 env-file 和子进程环境传递，不归档凭据；env-file 已删除。结束按自己的容器 ID 执行 `docker rm -f -v`，exit 0，清理自建容器及匿名卷，未操作既有 MateClaw 容器或用户数据。

## 24 个实际组件用例

Java 实际执行 24 个唯一用例，全部 PASS，没有跳过。记录成功发生在断言或预期异常验证之后，异常不转换为成功；用例明细见结果 JSON。

| 范围 | 数量 | 实际证明 |
|---|---:|---|
| 有效二进制、候选清单、PPT 原字节 | 3 | 精确三键查询、双摘要及空 expectedDigest 时存储摘要仍被检查 |
| 同时替换内容与行 digest、错误行/冻结/空摘要、未声明文件、重复 manifest | 6 | 当前读取拒绝缺乏冻结清单证明的内容 |
| 发布、候选、PPT 缺失行及错误 project/release 键 | 5 | 不回退到其他键，保持相应 404/409 领域错误 |
| 空 expectedDigest 下损坏 PPT、三条读取路径非法 Base64 | 4 | 存储摘要仍有效，严格解码失败不被吞掉 |
| 真实 MySQL 主键重复与事务回滚 | 2 | 物理唯一约束生效，调用方回滚后不残留 artifact |
| 合成 DOCX、PPTX、完整候选清单与重复读取 | 4 | 写入后回读全部原字节，POI 可解析，读取不改业务列事实 |

失败读取前后也比较 artifact 业务列快照，没有修复存储或重新渲染；这是行事实不变证明，不是数据库物理页字节证明。HTTP、角色和来源继续对应上一轮真实 H2/MockMvc 证据，本轮组件结果不能冒充完整 MySQL HTTP 链路。

## 文件与证据回读

一次生成并存储合成 DOCX/PPTX/Markdown，再经当前 Reader 从真实 MySQL 回读；原字节逐一相等。DOCX 3146 字节、PPTX 26420 字节、Markdown 91 字节，其 SHA-256 在结果 JSON 中。POI 打开 DOCX 与 PPTX；归档后再解压并解析 XML，DOCX 保留合成标题，PPTX 有 2 个有效 slide XML。

这些文件是专门构造的测试材料。它们证明字节存取与有限结构可读，不证明真实历史文件、人工 Office 打开、视觉版式、客户交付或正式 AC 完成。26 个 gzip 归档的压缩与解压摘要已经回读核对，包含 runner、探针、四个当前源码、11 个编译产物、日志和三个合成文件。

独立 `artifact_read_review` 对环境隔离、24 个断言、来源摘要、全部编译产物摘要及清理范围给出 COMMENT，0 个实质缺陷；未独立重跑 Docker/Maven/gate，不是维护人批准。初始 dev `paviibxn` exit 0 / SCAN_PASS / submission_ready=false；最终 dev 报告单独归档为 `artifact-mysql-tests/final-dev-report.json.gz`，以实际报告中的源快照身份为准。

## 未完成与兼容风险

当前状态为 ENGINEERING_COMPONENT_VERIFIED_UNSUBMITTED。完整 MySQL HTTP 角色/来源链路、Kingbase、全库 Flyway/升级/回退、真实历史发布文件、Office 视觉与客户验收仍 NOT_RUN；46 正式 AC 不由此关闭。请求 hash 的旧回执策略待确定，维护人批准和远端 required CI 未验证，完整 commit 门禁 NOT_RUN，submission_ready=false，未提交或推送。

读取修复没有数据写入或模式修改，但缺少、歧义或不可验证冻结 manifest 的历史成果会失败关闭。不得用补造摘要、重渲染或自动改存储来掩盖该风险。源码回退必须正常审阅并重跑门禁；回退会重新暴露原始完整性缺口。本轮本身仅新增证据文档和归档，没有进一步产品行为修改。

后续阶段见 [AQ05_ARTIFACT_MYSQL_HTTP_ACCEPTANCE.md](AQ05_ARTIFACT_MYSQL_HTTP_ACCEPTANCE.md)：真实 MySQL 的 MockMvc/角色/历史来源路径 17 项通过，并有该候选空库迁移到 V219 与 validate 证据。该记录不改写本文的 V211 组件阶段快照，完整生产 HTTP、升级恢复、Kingbase 与真实历史 Office 仍待验收。
