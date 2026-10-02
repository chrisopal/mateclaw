# AQ-05 查询响应与可空事实边界计划

起点 b7bf1bbbb591122e9d81439dd7d05121880de541，tree c0ddbe9d44e4aa33221f4b2f594e2fd08d1fc793，隔离工作树干净。初始 dev 1fc7txrr SCAN_PASS/exit0；继续使用 origin/dev 固定基线 ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93。上一轮推进分类：实际源码/证据提交及远端回读，属于 progress；完整架构尚未达成。

范围：presalesApi 的五个查询、同域 presalesResponse 解码器、编辑会话和两个字段组件/工作台相关类型、接口与选项回归测试。复用 Workspace 请求、错误/取消/scope/session/receipt，依赖方向不变；无新依赖/服务端授权/事务/SQL/迁移。共享原有私有校验原语，不新增通用层或兄弟域私有引用。

真实 wire：members 为字符串 id/workspaceId/userId/role，nickname/username 可空且角色未知原文保留；与请求 Workspace 比对。sources 的 kbId 字符串、name 可空；普通来源无 graph 字段，图来源同时携带 graphId/ontologyRevisionId，可空值原样保留。trusted statement 独立固定 DTO：id、revision、graphId、ontologyRevisionId、label、evidenceIds；ontologyRevisionId 可空，evidenceIds 为 null 或包含 null 的字符串列表，保留顺序/重复及原 JSON。employees 为 id/name/enabled/available；当前缺服务为空数组，校验类型不假定恒为true。capabilities 的4个 boolean 必需，已有可选 modelConfigured 只在出现时验证。

禁止对这些查询套用广义 PresalesRecord，避免继承虚假的非空元数据和业务字段。已声明可空类型由独立 DTO 表达；raw response 保留对象/数组恒等和未知扩展，不归一化 ID 或删除响应字段。成员权限和事实可用性继续由服务端真实授权决定，schema 校验不授予权力。

实施记录：旧 API 八项正例通过，27项新增拒绝断言失败；旧编辑会话17项通过，新null证据成员草稿断言失败。切片53项通过，最终新增root/恒等/类型/信号/403和null-list测试后查询42+编辑19=61项，完整售前13文件259项、vue-tsc与修改文件零警告lint通过。切片dev4chx277f、最终t3w1cnoc SCAN_PASS。新增403夹具曾错误期待同一个AxiosError且用默认headers构造InternalAxiosRequestConfig，分别造成一项失败和类型错误；宿主api/index.ts明确包装Error而保留response，改用adapter真实config并检查必然拒绝、Error/消息/原403响应结构，未改拦截器或弱化旧断言，重跑通过。原Workbench/member、employee和editorSession的mock补真实DTO缺字段，保留旧断言。独立wire/code审阅无发现，限定最终61项/lint通过；LSP transport closed，AST使用有界文本回退不能冒称AST PASS。原生caffeinate仅保护检查进程，不调整门禁配置。

显式行为修复：选择事实时，当前代码把 null 证据列表成员复制为草稿 ID。原事实数组保持不变，仅在用户主动选择时过滤 null，保留其他字符串的顺序/重复；整个列表 null 仍按现有行为映射为空草稿。不在加载/回读时重写历史记录、不宽化严格项目记录、不可变快照或所有 evidence 类型。null 来源名称只在组件 label 处映射为 undefined，以保持 Element Plus 既有值回退，选来源时既有空字符串回退不变。

先用实际 Axios adapter 锁路径/捕获 Workspace/signal/HTTP错误、真实空值和空查询；新增 numeric ID/布尔字符串/错 Workspace/缺字段/错 revision/错 evidence/graph字段不成对负例，在旧 API 证明失败。补事实选择 null 列表/成员测试并在旧行为证明失败。然后类型和运行时校验切片，既有 mock 填真实 DTO 缺字段但保留所有断言，全部售前/类型/lint/format/dev→精确暂存门禁→独立限定 wire/code/夹具审阅→正常提交/推送钩子和 PR 回读。主机保活仅在检查进程期间临时 caffeinate，不改测试配置。正式46项AC保持NOT_RUN；真实服务/历史数据/浏览器/模型/迁移/独立维护者及远端 required CI 未验证。回退仅还原查询入口/DTO和选项投影，无持久化操作。
