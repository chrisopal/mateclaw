# MateClaw 企业 UI 渐进演进方案

状态：Proposed

日期：2026-09-03

参考：用户提供的 MBC 企业级后台 Figma Make 设计

## 1. 目标与边界

目标是在不改变 MateClaw 业务架构、路由、权限、数据流和核心交互的前提下，将现有偏个人 AI 助手的暖色氛围界面，逐步调整为扁平、统一、可信赖的企业运营工作台。

非目标：

- 不复制 MBC 的名称、业务信息架构、租户数据或 React/Ant Design 代码。
- 不更换 Vue 3、Element Plus、Pinia、Vue Router 和 Vue I18n。
- 不把 Chat、Workflow Canvas、Wiki 等专业工作面强行改造成普通表格后台。
- 本阶段不与新业务功能开发混在同一批提交中。

## 2. 当前事实

- 全局视觉 Token 位于 `mateclaw-ui/src/assets/main.css`。
- `src/main.ts` 是全局样式的单点导入位置。
- `src/App.vue` 管理 Element Plus Provider 和主题初始化。
- `MainLayout.vue`、`Login.vue`、`Dashboard.vue` 是第一阶段最有价值的视觉切入点。
- 现有大量视觉规则位于 Vue scoped style 中，包含暖棕/橙/青配色、渐变、毛玻璃、大圆角、投影和装饰动画。
- 当前源码中有 36 个 Vue 文件复用 `mc-page-shell` 或 `mc-surface-card`，适合通过共享层获得首轮收益；另有 124 个 Vue/CSS 文件包含六位十六进制颜色、38 个文件包含渐变，后续需要区分合法专业场景与应收敛的视觉字面量。
- 当前 Git 仅有 `origin=https://github.com/mateaix/mateclaw`，`dev` 跟踪 `origin/dev`。

配套 Skill 已安装为 `$mbc-enterprise-ui`，其 MateClaw 专用规则要求每次实施前重新核对上述事实，避免把当前结构固化为永久假设。

## 3. 决策

采用“Scoped Visual Adapter（受控视觉适配层）”，不直接全量重写页面，也不复制一个独立前端。

建议结构：

```text
mateclaw-ui/src/styles/enterprise/
├── tokens.css
├── element-plus.css
├── shell.css
├── patterns.css
└── index.css
```

通过 `[data-ui-profile="enterprise"]` 限定作用域，在 `src/main.ts` 增加一个样式入口，在现有主题初始化附近设置 profile。迁移期间使用非敏感的 `VITE_UI_PROFILE=classic|enterprise` 构建变量控制，默认仍为 `classic`，样板验收后再切换默认值；因此视觉回滚不需要撤销业务代码。业务页面优先继承 Token、Element Plus 主题和通用模式；只有缺少必要 DOM 结构时才做最小模板修改。

## 4. 备选方案

### A. 直接修改所有现有 scoped style

短期最快，但会让视觉改动散落在大量上游文件中。后续同步主仓时冲突面最大，拒绝。

### B. 完全复制 `mateclaw-ui` 形成独立前端

隔离度高，但功能扩展需要双线迁移，极易与主工程分叉，拒绝。

### C. Token + 组件主题 + 壳层适配器

能让新增页面自动继承大部分视觉规则，只保留少量结构性定制。改动可分片、可回退、可持续同步，采用。

## 5. 风格映射

- 主色：`#1677FF`；普通交互只使用一个主色。
- 墨色：`#0B1220`，通过 60/40/20/12/08/06/04% 透明度建立层级。
- 画布：`#F5F7FA`；容器：`#FFFFFF`。
- 侧栏：220px；折叠 56px；顶栏 48px。
- 控件：默认 34px，小型 28px，大型 40px。
- 圆角：控件 3-4px，面板 6px。
- 普通容器使用边框，不使用投影；弹窗、抽屉和菜单保留轻投影。
- 页面标题控制在 20-24px，正文 14px，辅助信息 11-13px。

MateClaw 橙色保留在 Logo 识别中，不再同时承担全局按钮、链接、选中态和状态色。

## 6. 交付切片

1. 建立截图基线和上游 commit 基线。
2. 新增企业 Token、Element Plus 映射和 profile 开关，不改模板，迁移期默认 `classic`。
3. 扁平化 MainLayout 和共享 page shell。
4. 登录页改为 42/58 双区结构；Dashboard 验证 KPI、列表、表格和图表密度。
5. 提炼页面标题、筛选条、表格、状态、表单、Drawer、Empty State 等共享模式。
6. 按路由族迁移：Agent/Team → Settings/Security → Scheduler/Workflow → Wiki/Memory → Chat 专业工作面。
7. 记录有意保留的专业界面例外。

## 7. 主仓同步方案

长期维护时建议使用两个远端：

- `upstream`：官方 `mateaix/mateclaw`，只负责获取主仓更新。
- `origin`：组织自己的 fork，保存企业 UI 和业务扩展。

保留一个不做定制的 `upstream/dev` 镜像，使用 `enterprise/dev` 作为集成分支。企业 UI 的基础、壳层、页面迁移与业务功能必须分开提交。每次同步主仓先审查 `main.ts`、`App.vue`、`main.css`、`MainLayout.vue` 和已适配路由，再合入上游行为，最后重新应用视觉适配层。

禁止用目录复制、覆盖式同步或整侧冲突选择处理混合变更。冲突处理原则是先保留上游功能和数据行为，再恢复企业视觉适配。

## 8. 验收与回滚

- lint、typecheck、unit tests、build 全部通过。
- 登录、Dashboard、Chat、Agent、Workflow、Wiki、Settings 完成授权态截图对比。
- 验证 1366、1440、1920px 以及现有移动断点。
- 验证浅色与深色；Figma 参考只作为浅色证据，深色需独立验收。
- 不出现页面级横向溢出、焦点丢失、状态只靠颜色表达或 Element Plus 浮层样式断裂。
- 回滚以独立的 UI 分片提交为单位，不回滚同时期的业务功能。

## 9. 主要风险

- 上游重写 MainLayout 导致选择器失效：通过单一 `shell.css` 和截图门禁发现。
- Vue scoped style 特异性压过适配层：使用 profile 属性和稳定语义类，避免大面积 `!important`。
- 新页面重新引入任意颜色：在评审或 CI 中扫描新增视觉字面量，并优先映射到语义 Token。
- 适配层膨胀成第二套组件库：明确 Element Plus 仍是唯一组件系统，适配层只负责 Token、主题、壳层和重复模式。
