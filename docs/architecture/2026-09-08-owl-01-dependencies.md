# OWL-01 依赖决策报告

日期：2026-09-08。状态：**设计候选，待后续依赖引入授权与验证；不是运行通过记录。**

## 1. 决策

推荐以 **OWLAPI 5.5.1 + OWLAPI 团队发行的 HermiT 1.4.5.519** 作为完整 OWL 2 DL 新模型的首选验证组合，放入独立 OWL adapter。版本均由 Maven Central 元数据及发布 POM 核实，并非根据版本名称猜测。**组合兼容性尚未证实**：HermiT 的发布 POM 实际依赖 OWLAPI **5.1.9**，不是 5.1.19，更不是 5.5.1。因此该推荐是面向宿主 SLF4J 2 / 现代依赖线的明确试验目标，不能直接批准生产。

选择依据是 OWLAPI 的完整结构模型、解析与序列化能力，以及 HermiT 的 OWL 2 DL Direct Semantics 定位。OWLAPI 自身不是完整逻辑推理器。新模型不降级为旧字段模型、EL/RL 子集或仅 RDF 三元组 CRUD。[OWLAPI 官方 README](https://github.com/owlcs/owlapi/blob/owlapi-parent-5.5.1/README.md)、[HermiT 发布 POM](https://repo.maven.apache.org/maven2/net/sourceforge/owlapi/org.semanticweb.hermit/1.4.5.519/org.semanticweb.hermit-1.4.5.519.pom)。

### 具体待批准清单

| 依赖 / 边界 | 固定版本 | 决定及条件 |
|---|---|---|
| `net.sourceforge.owlapi:owlapi-distribution` | `5.5.1` | 首选解析、结构模型、序列化、profile validation 与 reasoner SPI；限定 adapter，禁止类型泄漏到 core/application |
| `net.sourceforge.owlapi:org.semanticweb.hermit` | `1.4.5.519` | 首选 DL reasoner；显式排除其传递的 `owlapi-distribution:5.1.9`，全部 OWLAPI 组件统一 5.5.1；必须先通过二进制和语义测试 |
| OWLAPI `5.1.9` + HermiT `1.4.5.519` | 原始发布配对 | 仅用于后续隔离诊断对照，不作为生产回退或旧模型兼容方案；现代宿主依赖冲突及陈旧依赖问题更大 |
| `com.github.galigator.openllet:openllet-owlapi` | `2.6.5` | 备选评估，**不纳入首选引入清单**；AGPL、版本范围、旧日志依赖及维护发布节奏都需另行评估 |
| Jena / ELK / Openllet Jena 插件 | 无 | 不引入；当前范围不需要第二 RDF 栈、SPARQL 引擎或另一 profile reasoner |
| Protégé、CLI、explanation 插件 | 无 | 不引入；基本 reasoner API 不等于已经拥有最小解释集生成能力 |

以上仅批准对象的精确定义，**本文未执行下载 JAR、Maven resolve/install、POM 编辑或数据库访问**。宿主 Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、SLF4J 2、Jackson 2.18.3 为本任务给定约束，未在本报告中重新审计宿主。

## 2. 版本、维护与许可证证据

| 项目 | 已核实发布 / 活动 | 许可证与重要限制 |
|---|---|---|
| OWLAPI | Central release `5.5.1`，元数据更新时间 2024-09-07；tag commit `f760b3a4c107c6d5f90f856e5680c39b18af646f` 日期 2024-09-07；仓库 pushed_at 2026-09-06（推送时间不是新版本发布时间） | parent POM 列 Apache-2.0 与 LGPL-3.0；官方 README 同时列这两种开源许可证。发行时需连同嵌入与传递组件逐项核对，不能把整个依赖闭包都标成 Apache |
| HermiT OWLAPI fork | Central release `1.4.5.519`，元数据更新时间 2020-02-18；owlcs fork HEAD `65d3890580da9b32d2a5afb5b197a5fc96c68746` 日期 2021-03-08 | POM 标 LGPL，链接 LGPL-3.0；明确称此 Maven fork 不受原 HermiT 开发者正式支持。发行包含 Jautomata 代码/依赖，许可证核查不能止于顶层 POM |
| Openllet | Central `2.6.5`；GitHub release 2019-09-27。默认分支最新 commit `b31613c77f65ddc9fb0c10a84fe7959ac97b721a` 日期 2025-08-04，修正 transitive property chain | parent POM 标 AGPL-3.0。源码活跃提交并不意味着该修复已进入 2019 发布包；不能假设商业替代授权已经取得 |
| ELK | GitHub release `v0.6.0`，2024-05-25 | 官方项目定位 OWL 2 EL；即使许可证宽松，也不能满足完整 DL 需求 |

来源：[OWLAPI 元数据](https://repo.maven.apache.org/maven2/net/sourceforge/owlapi/owlapi-distribution/maven-metadata.xml)、[OWLAPI parent POM](https://repo.maven.apache.org/maven2/net/sourceforge/owlapi/owlapi-parent/5.5.1/owlapi-parent-5.5.1.pom)、[OWLAPI tag commit](https://github.com/owlcs/owlapi/commit/f760b3a4c107c6d5f90f856e5680c39b18af646f)、[HermiT 元数据](https://repo.maven.apache.org/maven2/net/sourceforge/owlapi/org.semanticweb.hermit/maven-metadata.xml)、[HermiT fork commit](https://github.com/owlcs/hermit-reasoner/commit/65d3890580da9b32d2a5afb5b197a5fc96c68746)、[Openllet release](https://github.com/Galigator/openllet/releases/tag/2.6.5)、[Openllet parent POM](https://repo.maven.apache.org/maven2/com/github/galigator/openllet/openllet-parent/2.6.5/openllet-parent-2.6.5.pom)、[Openllet commit](https://github.com/Galigator/openllet/commit/b31613c77f65ddc9fb0c10a84fe7959ac97b721a)、[ELK release](https://github.com/liveontologies/elk-reasoner/releases/tag/v0.6.0)。

**许可证决策**：HermiT 的 LGPL 与 Openllet 的 AGPL 都不能作为平台根目录 Apache-2.0 的普通同许可证依赖处理。独立 Maven module 只隔离代码依赖方向，不自动消除分发/组合许可义务。商业交付前应对具体打包方式、修改情况、嵌入组件和源码/通知提供方式作许可核对；本次不作整个产品可闭源分发的结论。

## 3. 能力边界与完整性

HermiT 官方定位为 conformant OWL 2 DL Direct Semantics reasoner，官方站点声明通过 Direct Semantics conformance tests；其发布 POM直接描述一致性和类包含判定能力。[HermiT 官方站](https://www.hermit-reasoner.com/)、[原项目 README](https://github.com/phillord/hermit-reasoner)。这是选择完整 DL 引擎的依据，**不是本项目对当前 fork + 新依赖组合的无缺陷证明**。W3C 明确指出测试集本身不完整，全部通过不能证明实现完全符合规范。[W3C conformance §1](https://www.w3.org/TR/owl2-conformance/#Introduction)。

建议产品首批明确支持并分别验收的推理任务：

1. 本体及固定 imports closure 一致性；不能把不一致本体的查询结果当作普通真值。
2. 类表达式可满足性、不可满足命名类查询；区分类不可满足和本体整体不一致。
3. 命名类分类、等价类、直接/间接父子类；对象属性/数据属性层次与等价关系分别覆盖。
4. 个体 realization、类型、实例查询；显式测试 sameAs、differentFrom、无唯一名假设。
5. 指定 axiom 类型的 entailment；在 adapter 中核验引擎 `isEntailmentCheckingSupported`，不承诺任意 API 操作都有完整查询算法。
6. 合法 OWL 2 DL 的复杂表达式：交并补、存在/全称、qualified cardinality、nominals、inverse、property chains、keys 与 datatype restrictions 的组合测试。

不能从这些能力推出：任意 SPARQL 查询完整回答、全部推导三元组有限枚举、任意规则/SWRL 的可判定完整推理、数据库闭世界验证、所有约束违规解释、自动给出最小解释集。外部业务必填项仍需独立校验；OWL 开放世界下缺少属性不自动表示违规。

### datatype 的严格结论

“完整 DL”指满足结构与全局限制的 OWL 2 DL、本任务选定 Direct Semantics 及标准 datatype map，不是全部 XML Schema 类型、任意自定义 datatype IRI 或任意计算函数。使用 OWLAPI 成功解析一个 literal，不证明 reasoner 支持其值空间/约束语义。数据范围必须遵循 [W3C datatype maps](https://www.w3.org/TR/owl2-syntax/#Datatype_Maps) 和 [OWL 2 DL global restrictions](https://www.w3.org/TR/owl2-syntax/#Global_Restrictions_on_Axioms_in_OWL_2_DL)。

HermiT 配置源码说明 `ignoreUnsupportedDatatypes=true` 会忽略包含不支持 datatype 的公理，默认 false。**必须保持 false，绝不以忽略公理实现“兼容”**。该开关行为目前核实于仓库源码，后续要在固定发行 JAR 上复核。[Configuration 源码](https://github.com/owlcs/hermit-reasoner/blob/65d3890580da9b32d2a5afb5b197a5fc96c68746/src/main/java/org/semanticweb/HermiT/Configuration.java)。

完整性闸门必须覆盖标准类型、合法/非法 lexical form、相同值不同 lexical form、整数/小数值空间重叠、数值边界 facets、字符串 pattern/length、日期时间与时区、语言标签 literal、枚举与补集、互斥 datatype 导致的矛盾。若合法标准用例失败，报告引擎缺陷或不支持并阻止“完整 DL 已验收”状态，不能静默退到字符串比较。超时/资源耗尽返回 UNKNOWN/TIMEOUT，不返回 false 或 consistent。

### 为什么不选另两类引擎

Openllet 官方称 OWL 2 DL reasoner，提供 consistency、classification、explanations、SPARQL 集成；可作为第二实现做差异诊断，但两者一致也不构成标准正确性证明。其 2.6.5 parent 允许 OWLAPI `[5.1.9,)`，范围不等于未来版本兼容承诺，且旧发布不包含后来分支修复。[固定版本 README](https://github.com/Galigator/openllet/blob/2.6.5/README.md)。

Jena 官方明确将内置 OWL/Mini/Micro 描述为不完整 OWL/Lite 子集实现；名称中的 OWL 或 Full 不能视为完整 OWL 2 DL Direct Semantics 引擎。ELK 是 OWL 2 EL profile reasoner，适合 profile 受控的大规模分类，不能在本需求中替代 HermiT。[Jena 官方 inference 文档](https://jena.apache.org/documentation/inference/)、[ELK 官方仓库](https://github.com/liveontologies/elk-reasoner)。

## 4. Java 21 与传递依赖冲突

OWLAPI 5.5.1 parent 编译 target/source 11；HermiT 发布 POM为 1.8。较低字节码版本可被较新 JVM 加载是必要条件，**不证明反射、服务发现、XML、缓存、线程取消和宿主打包兼容**。尚未找到能够证明该精确组合在 Java 21 + Spring Boot 3.5.16 + Spring AI 1.1.8 上通过的官方矩阵。

由 [OWLAPI 5.5.1 distribution POM](https://repo.maven.apache.org/maven2/net/sourceforge/owlapi/owlapi-distribution/5.5.1/owlapi-distribution-5.5.1.pom) 与 HermiT 发布 POM确认的风险如下；这是直接发布 POM清单，尚不是 Maven 完整解析结果：

| 路径 | 已确认声明 | 后续处理与验证 |
|---|---|---|
| OWLAPI 版本 | HermiT → 5.1.9；目标 → 5.5.1 | 排除旧 distribution，统一全部 OWLAPI 工件；测试 factory 创建、加载/分类/entailment/dispose，捕获 `NoSuchMethodError`/`AbstractMethodError` |
| 日志 | OWLAPI → slf4j-api、jcl-over-slf4j 2.0.11；HermiT → commons-logging 1.1.3 | 宿主仅保留一个 SLF4J provider；Spring Boot 场景优先使用宿主 spring-jcl，验证排除两种重复 JCL 实现后 XML 与推理日志正常；不得同时引入日志桥循环 |
| Jackson | OWLAPI → core/databind/annotations 2.16.1 | 按本项目明确约束统一 2.18.3；检查宿主 effective POM，不能假设 Boot 自带版本就是 2.18.3；RDF/JSON 与 JSON-LD 路径单独测试 |
| RDF4J | OWLAPI → model、rio 组件 5.0.2 | 所有 RDF4J 组件收敛同一兼容线；测试 parser SPI、RDF/XML/Turtle/JSON-LD，防宿主 BOM 部分改写造成混用 |
| 其他解析依赖 | jsonld-java 0.13.6、xz 1.9、commons-io 2.15.1、Guava 33.0.0-jre、Caffeine 3.1.8、hppcrt 0.7.5 | 核对完整树与已有 Spring AI 路径；不能凭本 POM断言无漏洞、无冲突 |
| HermiT 老依赖 | Axiom api/c14n/impl/dom 1.2.14，automaton 1.11-8，java-getopt 1.0.13，trove4j 3.0.3 | XMLLiteral/datatype 路径可能真正需要这些依赖，不可随意删掉；查许可证、漏洞、重复类及 Java 21 XML provider 行为 |
| 打包嵌入 | HermiT bundle 插件声明 Embed-Transitive 与若干 Embed-Dependency；OWLAPI distribution 含 shade 配置 | Maven exclusion 不保证消除发行包内部嵌入类；授权下载后检查实际 JAR 内容和最终 Boot fat JAR，不能只看 dependency:tree |
| Openllet 备选 | parent 包含 SLF4J `[1.7.25,1.7.99]` 与多个版本范围 | dependencyManagement 中出现不等于所有都进入 runtime；必须解析具体 openllet-owlapi 路径后判断。不得直接把范围解析结果当可重现构建 |

独立 module 保持 `core/application` 的源码和编译依赖为 JDK-only。它**不会隔离同 JVM 的 runtime classpath**。adapter 通过 JDK DTO/接口接收 bytes、IRI、结构化请求，返回 JDK 结果；OWLAPI/HermiT 类型、注解、异常不跨边界。最终架构在 OWL-03 采用受控本地推理子进程以实现硬资源边界，见后续任务卡；解析适配仍需验证宿主 classpath。子进程不会自动解决其自身依赖兼容问题，本次不扩展远程部署。

## 5. 引入后必须执行的验收（本次未执行）

1. 固定 JDK 21 与宿主 BOM，保存 effective POM、完整 runtime dependency tree、resolved versions 与工件校验值；检查依赖收敛、重复类和最终运行包。扫描漏洞及许可证并记录具体工具/数据库日期。
2. 检查 core/application 没有第三方 imports 或传递编译依赖；adapter 单独测试，再以真实 Boot fat JAR 启动验证，而非仅在 IDE classpath 上验证。
3. OWLAPI 必验读取/写出 RDF/XML、Functional；其他格式若后续启用则另验，不扩大本轮格式承诺；比较结构化公理等价而非字符串相等。使用本地固定 imports closure，覆盖缺失/cycle/import version/hash 变化；禁止运行期任意远程取 imports。
4. `OWL2DLProfile` 检查覆盖 complex role/simple property 与 regularity 等全局限制。区分 PARSE_ERROR、PROFILE_VIOLATION、UNSUPPORTED_DATATYPE、INCONSISTENT、TIMEOUT 和正常结果。
5. 执行上文六类推理任务与 datatype 矩阵，加入 W3C Direct Semantics 正反例；把固定版本、输入摘要、预期、实际、耗时和内存作为证据保存。不得从几个简单 subclass 示例推断完整支持。
6. 单独测试取消、timeout、OOM 保护、dispose、并发请求隔离与重复调用内存增长；预计算不能承诺吞吐 SLA，复杂 DL 输入需要规模/时间上限且真实报告 UNKNOWN。
7. 故意注入缺失 import、unsupported datatype、非法 literal 与不合法 DL 公理，证明系统没有忽略信息而返回成功。对于 `isEntailed` 不支持的 axiom 类型，保留明确的不支持状态。

## 6. 本次核实结果与限制

已核实：官方仓库 README/commit/release、Central metadata/POM、W3C 规范与 Jena/ELK 官方能力定位；得到精确可申请的依赖坐标和可执行验收边界。

未核实：JAR 内容、完整传递闭包、CVE 扫描、商业分发许可结论、Java 21 运行、HermiT 1.4.5.519 与 OWLAPI 5.5.1 二进制兼容、全部 datatype 语义测试、性能 SLA。没有依赖下载或目标代码改动。若后续组合测试失败，应保留“完整 DL 尚未验收”的明确状态并评估修复/维护分支，不得用 Jena/ELK 或忽略 datatype 伪装成完成。

工具说明：GitHub 通过 agent-reach 指定的 gh 后端读取；少量 shell URL 未加引号导致的 glob 查询失败已通过正确引用重试或替代官方材料补齐。agent-reach check-update 遭 GitHub API 限流，未执行工具更新；这不影响已取得的项目证据。
