# 检查器、规则覆盖与执行契约

## 1. 四层约束

| 层 | 已提供的文件 | 功能 | 边界 |
|---|---|---|---|
| Agent 指令 | AGENTS fragment + Skill + RULES | 开始/开发中/提交前必须执行统一命令 | 指令可被忽视，不是强制安全机制 |
| 快速扫描 | gate.py / policy.json | Git 基线 ratchet、暂存区内容、基础架构和源码增量规则 | 词法扫描；不是完整 AST、权限证明或全仓漏洞扫描 |
| 工程校验 | verify.py / hooks | 真格式工具、lint、类型、测试、构建、退出码与证据 | 需先安装审定依赖；缺失会 BLOCKED |
| 远端合并 | workflow + required check + CODEOWNERS | 每 PR/merge-group 重跑；保护 dev 不合入失败版本 | 分支保护必须由维护人配置；本包没有替你开通 |

本地 pre-commit 可以被 `--no-verify` 绕过，钩子还依赖正确安装和 executable bit；不能宣传成本地绝对强制。[E1] 保护分支需要 required checks，代码所有者审查也需相应设置。[E2,E3]

## 2. 已实现的自动规则

| 规则 | 当前实现 | 策略 |
|---|---|---|
| AR-001 | 通用 runtime/auth/workspace/common/tool/skill 对 presales/bidding/delivery 的显式 Java 依赖 | 基线 ratchet；整改路径随后零容忍 |
| AR-002 | Controller 的 JDBC、DataSource、EntityManager、Mapper/Repository 类型访问 | 基线 ratchet；复杂类型用 ArchUnit 补充 |
| AR-003 | 前端 alias/相对路径/字面量动态 import 的跨 feature 内部依赖，shared/api→feature | public 契约例外；不允许公共 helper 借业务模块 |
| AR-004 | 工作台直接依赖 semantic.security / semantic.web 的基础设施类 | ratchet；目标为宿主通用服务 |
| AR-005 | 业务层直接 SQL 读取外域 Wiki/Agent/Workspace/Semantic 表的明显模式 | ratchet；移动到适配器不代表授权已经正确 |
| TS-001 | 目标工作台稳定源码中新 any | ratchet；unknown/具体 DTO 替代 |
| UI-001 | 新增 `l(中文,English)` 内联界面译文 | ratchet；使用 i18n key |
| TEST-001 | 测试新增 @Disabled/@Ignore 或 it/test/describe 的 skip/only | ratchet；不能据此证明全部断言未被弱化 |
| STYLE-001 | 目标生产 Java/UI 新增 >200 字符的密集源码行 | 快速可读性提示；完整格式另跑 formatter |
| STYLE-002 | 新增/修改非 Markdown 行的行尾空格 | 硬失败；Markdown 硬换行保留 |
| DB-001 | 基线中已存在 V*.sql / V*.java 的修改/删除/重命名 | 硬失败，不以旧问题放行 |
| DB-002 | 新 V*.sql / V*.java 未覆盖 h2/mysql/kingbase 同名同后缀文件 | 硬失败；不是跨数据库实测 |
| DB-003 | Java 入口未登记冻结清单，或清单格式/重复键/路径/摘要无效 | 硬失败；每个入口的共享算法闭包须人工核对 |
| DB-004 | 冻结源码缺失/摘要不匹配，基线清单项被移除/改写，首次冻结已有源码时同时修改 | 硬失败；基线声明和候选声明同时检查，不允许 rehash 绕过 |

`.quality/frozen-migrations.json` 是版本 1 的显式源码清单：`files` 映射仓库内 Java 源码路径到 SHA-256。所有 Java Flyway 入口必须登记，共享算法/工厂及嵌套类型随其源码一起冻结。清单只能追加新版本源码，不能删除或改写基线项；已有源码首次登记也必须保持基线字节。初次安装需独立核对摘要等于已发布源码。未来新增依赖须补闭包并独立审阅；此检查不自动分析 Java 依赖，也不检测 JDK/compiler/Jackson 二进制变化或代替 Flyway 实际 checksum/validate。详见 [AQ07 证据](evidence/2026-10-03/AQ07_JAVA_MIGRATION_GUARD_ACCEPTANCE.md)。

扫描忽略注释及常见字符串中的假 Java 引用，避免把原文当代码；但不是 Java/TS parser。反射、别名重导出、计算型 import、已导入类型的新用法、动态 SQL、运行时授权和实际回调仍需 ESLint/编译/ArchUnit/测试/审查。不得声称全部架构违规必然被这一个脚本发现。

`integration/WorkbenchArchitectureTest.java` 是 P0 整改后必须安装的字节码规则模板。当前未编译、未执行，不把提供模板说成仓库已有检查。[E4]

## 3. 存量策略：不可用新 baseline 洗白

比较基线必须是现存 Git 提交。旧问题按 **规则 + 路径 + 规范化代码指纹 + 次数**保留；同一路径新问题、复制同问题、改名搬运都会作为新增。删除同问题只减少债务，不为其他依赖提供名额。格式引发的等价空白不会单独改变依赖指纹。

`.quality/policy.json` 初始 `zero_tolerance={}` 只允许存量不增加；不是“所有模块合规”。整改结束后追加，例如：

```json
{
  "version": 1,
  "source_limit_bytes": 5000000,
  "zero_tolerance": {
    "AR-001": ["mateclaw-server/src/main/java/vip/mate/agent/"],
    "AR-002": ["mateclaw-server/src/main/java/vip/mate/bidding/"],
    "AR-003": ["mateclaw-ui/src/features/presales/", "mateclaw-ui/src/features/bidding/"]
  }
}
```

增加零容忍是受保护控制面变更；移除封口必须单独审查，不得与逃避违规的功能修改一起合并。规则变更自身需正反例测试；没有自动 waiver 或 baseline regenerate。

CI 从 **base checkout** 加载 runner/policy，不执行 PR 修改的 runner 来决定是否放行同一个 PR。Candidate 的检查器自测额外执行，但不取代 base runner。workflow/CODEOWNERS 本身仍必须受保护，防止在 PR 中改掉整套执行链。

## 4. 命令、范围和退出码

```bash
# 开发中的快速检查，包含未跟踪源码
python3 -B scripts/quality/verify.py --mode dev --base origin/dev

# 直接检查实际 index，可用于排查部分暂存
python3 -B scripts/quality/gate.py --mode staged --base HEAD --policy .quality/policy.json

# 全量提交检查：要求 tracked worktree = index，且无未跟踪输入
python3 -B scripts/quality/verify.py --mode commit

# CI：必须从另一个固定 BASE 的 checkout 执行，不使用 PR 自改 runner
python3 trusted/scripts/quality/verify.py --repo /workspace/candidate \
  --mode ci --base BASE_COMMIT_SHA --report-dir /tmp/mateclaw-report
```

| 返回 | 含义 | 能否提交 |
|---|---|---|
| 0 + SCAN_PASS | 快速检查无新增已识别问题；实际应用校验未跑 | 否 |
| 0 + PASS + submission_ready=true | commit/ci 的适用工程命令通过 | 本地允许继续；合并仍需远端及审批 |
| 1 / FAIL | 确定性违规或命令失败 | 否 |
| 2 / BLOCKED | 依赖/基线/环境/报告缺失、部分暂存等 | 否 |
| NOT_APPLICABLE | 按固定影响映射没有对应代码变更 | 仅限该子检查；原因写报告 |
| NOT_RUN | 没执行 | 不是通过 |

提交模式刻意不支持自动藏起部分修改。任何 tracked 未暂存差异、未跟踪输入都会阻断；请在独立工作树组织一次完整提交。不会 stash、reset、自动 git add 或改配置迁就检查。检查后 index/worktree 改变必须重跑。

pre-push 检查 stdin 中实际推送 refs：只接受当前 HEAD 的分支快进推送；新分支与 origin/dev 的 merge-base 比较；远端 commit 不在本地时要求显式 fetch。不支持的多头/标签/删除/非快进推送需单独维护流程，不是跳过检查自动放行。

## 5. 真实工具链

后端或控制面修改：JDK21 + Maven 根 reactor `clean verify`，使用独立临时 Skill 路径；解析 Surefire/Failsafe 报告，零执行、全跳过或失败不为成功。POM 的既有 `archunit.version=1.3.0` 可复用，不重复引入版本。[R6]

前端、后端或控制面修改：保持现有构建与 Snowflake 脚本；运行修改文件的非自动修复 ESLint、全量 vue-tsc、Vitest、历史 Node 测试、enterprise/classic 构建。删除历史 Node 测试目录不会被安静视作“没有测试”。[R7,R8]

格式工具采用明确版本提案：Prettier 3.6.2；Spotless Maven 2.43.0 / google-java-format 1.22.0 AOSP。属于本规格工具版本选择，不是宣称仓库已有。首次 bootstrap 需确认组织安全政策、安装和生成锁文件；没有它们，`verify` 返回 TOOLING_BOOTSTRAP_REQUIRED。格式检查不能用自写简单空白扫描冒充完整 formatter。

本包 CLI 不自动下载依赖。CI 使用 frozen lock；工具升级单独 PR。现有 Node 示例使用 22.16.0、pnpm 10.10.0 作为可重现候选，需首次 bootstrap 验证与仓库 lock/环境匹配，不宣称是最新版本。

环境中明显的密钥、生产 datasource 覆盖与跳过测试的注入变量不传给测试命令。仍须在隔离开发/CI 环境运行，没有哪个本地测试包装器可以把任意恶意 build 脚本变成安全代码。禁止用有生产数据库写权限的 runner 运行 PR。

## 6. 检查报告

默认输出到系统临时目录，不产生需再次提交的报告文件，也不将客户原文写入标准输出。`report.json` 包含 base、target tree/commit/content hash、runner/policy 摘要、命令、状态、耗时、日志摘要、执行测试数与未测边界。`architecture.json` 列出规则、路径、行号和代码指纹，不输出原始客户材料。

旧报告不复用；hook 每次实际执行，不接受 Agent 手工填写 PASS。CI 独立重跑，不信任本地 receipt。上传日志仍需遵守仓库可见性，公共仓库不能包含真实客户敏感资料。

## 7. 本包已测与未测

提供的 Python 脚本离线自测和合成 Git 仓库验证见 `validation/`。这不等于在完整 MateClaw 上跑过 Maven、formatter、Vue、数据库、真实模型、浏览器或 Office。交付时仍需记录 NOT_RUN；不以脚本行数或用例数推导可靠率。
