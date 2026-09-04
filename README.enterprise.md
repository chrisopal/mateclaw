# MateClaw Enterprise UI 使用与启动指南

Enterprise 是 MateClaw 的企业风格前端模式：统一蓝灰配色、扁平化导航与控件、双区登录页，以及蓝色 3D Logo 和页签图标。

它不是另一套后端，也不是独立的数据库或权限系统。`enterprise` 与 `classic` 共用 MateClaw 的登录、工作区、模型、Agent、Wiki、Workflow 和 API。切换 UI 模式不会清空数据，也不会额外开启尚未配置的 AI 能力。

> **默认仍为 `classic`。** 使用下面的 `--mode enterprise` 命令显式启用企业版。本文针对当前 `2.3.0-SNAPSHOT` 工程；升级后请核对实际 JAR 文件名。

## 1. 环境准备

| 工具 | 要求 / 当前验证环境 |
|---|---|
| Java | JDK 21；本地验证为 21.0.7 |
| Maven | 建议沿用已验证的 3.9.x 工具链 |
| Node.js | 当前 Vite 要求 `^20.19.0` 或 `>=22.12.0`；本地验证为 22.22.2 |
| pnpm | 本地验证为 11.25.0；使用仓库内 `pnpm-lock.yaml` |
| 数据库 | 本地默认 H2 文件数据库，无需另外安装数据库服务 |

以下命令使用 macOS/Linux 的 shell。示例仓库位置：

```bash
cd /Users/guojiexie/Development/mateclaw
```

其他机器请换成自己的仓库路径。工作副本必须包含 `mateclaw-ui/.env.enterprise` 和 `mateclaw-ui/src/styles/enterprise/`；官方分支或尚未合入定制的 fork 分支不一定包含 Enterprise UI。

首次安装前端依赖，在**仓库根目录**执行：

```bash
pnpm --dir mateclaw-ui install --frozen-lockfile
```

不要同时使用 npm/yarn 生成另一份锁文件，也不要把模型 API Key 放入前端 `VITE_*` 变量——这些变量可能被打进浏览器代码。

## 2. 快速启动：后端 + 企业版开发前端

需要两个终端。启动前先检查端口，避免重复启动或占用其他项目：

```bash
lsof -nP -iTCP:18088 -iTCP:5176 -iTCP:5177 -sTCP:LISTEN
```

若发现已有进程，先核对它属于哪个项目；不要直接结束未知进程。

### 终端 A：编译并启动后端

从**仓库根目录**执行：

```bash
mvn -pl mateclaw-server -am -DskipTests -Dmaven.compiler.proc=full package
cd mateclaw-server
java -jar target/mateclaw-server-2.3.0-SNAPSHOT.jar --server.address=127.0.0.1
```

- 后端地址：[http://127.0.0.1:18088](http://127.0.0.1:18088)
- 首次空库启动会初始化表结构与基础数据，等待启动完成后再登录。
- `-am` 同时构建需要的内部模块，避免缺少 `mateclaw-plugin-api`。
- `-DskipTests` 只用于这条启动打包命令，不代表测试已经通过；验证命令见第 8 节。
- 以后未修改后端代码时，可以直接执行 `java -jar`，不必每次重新打包。

### 终端 B：启动企业版开发前端

在另一个终端，从**仓库根目录**执行：

```bash
cd mateclaw-ui
pnpm exec vite --mode enterprise --host 127.0.0.1 --port 5176 --strictPort
```

打开 [http://127.0.0.1:5176](http://127.0.0.1:5176)。这是开发模式，修改源文件后支持热更新。

前端会把 `/api` 代理到 `http://localhost:18088`。只启动前端只能看到页面，不能完成登录和业务操作。

## 3. 日常使用

1. 首次空库默认账号为 `admin`，密码为 `admin123`。已有数据库或改过密码时，使用现有账号，不要为了恢复默认密码而删库。
2. 登录后，在侧栏“外观”切换浅色、深色或跟随系统；在“语言”切换中文/英文。这些操作与 Enterprise/Classic 模式开关相互独立。
3. 首次使用 AI 功能，进入“设置 → 模型管理”，启用提供商、配置凭证与模型，并选择可用的当前模型。没有模型时，对话输入区禁用是预期行为。
4. 在“员工”选择 Agent 开始对话；根据业务需要配置知识库、团队和工作流。首次登录可跳过模型引导，稍后再配置。
5. 工作区切换仍使用原有权限规则；Enterprise UI 不会绕过角色或权限限制。

默认账号只适合初始本地环境。接入真实业务数据前应修改默认密码；本文命令仅监听本机，不是公网部署配置。

## 4. 构建并启动企业版预览

用于验收编译后的页面，不提供热更新。**仍需先启动第 2 节的后端。**

在**仓库根目录**打开新终端：

```bash
cd mateclaw-ui
pnpm exec vue-tsc --noEmit
pnpm exec vite build --mode enterprise --outDir dist/enterprise
pnpm exec vite preview --outDir dist/enterprise --host 127.0.0.1 --port 5177 --strictPort
```

打开 [http://127.0.0.1:5177](http://127.0.0.1:5177)。此前展示的 Enterprise 工作台使用的就是这个地址。

| 场景 | 端口 | 说明 |
|---|---:|---|
| 后端 API | 18088 | 开发前端与预览前端共用 |
| Enterprise 开发前端 | 5176 | 修改代码后热更新 |
| Enterprise 构建预览 | 5177 | 修改代码后需要重新构建并刷新 |

通常只需启动一种前端模式。`--strictPort` 会在端口被占用时直接报错，避免悄悄换到另一个端口。

构建会更新目标产物目录。不要在别人正在验收时覆盖正在服务的 `dist/enterprise`；可先构建到独立检查目录，验收通过后再安排切换。Vite preview 仅用于本地验收，不应直接作为公网生产服务器。

## 5. Enterprise / Classic 开关与回退

企业模式配置位于 [`mateclaw-ui/.env.enterprise`](mateclaw-ui/.env.enterprise)：

```dotenv
VITE_UI_PROFILE=enterprise
```

`--mode enterprise` 加载这份配置。未设置或值无效时，`profile.ts` 回退到 `classic`。进程环境中的 `VITE_UI_PROFILE` 优先于 `.env.enterprise`；若效果不符合预期，检查启动终端是否残留了相反的环境变量。

**这是构建时开关，不是后端运行参数。** 已经生成的静态页面不会因修改 `.env.enterprise` 而自动换主题，必须重新构建。`--mode enterprise` 也不等于 Spring 的数据库 profile。

在 `mateclaw-ui` 目录回退到 Classic 开发模式：

```bash
VITE_UI_PROFILE=classic pnpm exec vite --host 127.0.0.1 --port 5176 --strictPort
```

或构建 Classic 产物（先停止占用 5177 的旧预览，再启动这一份）：

```bash
VITE_UI_PROFILE=classic pnpm exec vite build --outDir dist/classic
pnpm exec vite preview --outDir dist/classic --host 127.0.0.1 --port 5177 --strictPort
```

回退仅改变前端外观和图标，不需要回滚或删除数据库。

## 6. 数据、停止与重启

按本文从 `mateclaw-server` 目录启动后端时，默认数据文件为：

```text
mateclaw-server/data/mateclaw.mv.db
```

- 数据库路径取决于后端的**工作目录**。从其他目录执行相同 JAR，可能在别处生成一份新数据库，表现为账号或配置“消失”。
- 当前常规启动采用文件数据库，重启后保留数据。之前 UI 验收使用的独立内存 H2 数据库不属于这份数据，停止后不保留。
- 不要同时启动两个使用同一 H2 文件的后端。遇到锁错误，先查原进程，不要删除数据库或锁文件。
- 备份前先正常停止后端；数据库、上传文件和实际配置应按所用环境一起备份。不要提交到 Git。
- 前台运行的服务用对应终端的 `Ctrl+C` 正常停止。停止前确认没有正在执行的任务。重启时继续使用相同工作目录和数据配置。

### 可选：使用 screen 后台运行

适用于已安装 `screen` 的 macOS/Linux。先执行 `screen -ls` 和端口检查，已有同名服务时不要重复启动。

后端：从**仓库根目录**执行：

```bash
cd mateclaw-server
screen -dmS mateclaw-enterprise-server java -jar target/mateclaw-server-2.3.0-SNAPSHOT.jar --server.address=127.0.0.1
```

前端：在另一个终端从**仓库根目录**执行；先确保已构建 `dist/enterprise`：

```bash
cd mateclaw-ui
screen -dmS mateclaw-enterprise-ui pnpm exec vite preview --outDir dist/enterprise --host 127.0.0.1 --port 5177 --strictPort
screen -ls
```

查看后台终端：`screen -r mateclaw-enterprise-server` 或 `screen -r mateclaw-enterprise-ui`。按 `Ctrl+A` 后按 `D` 可退出查看但保留服务；需要停止时，在重新连接的会话中按 `Ctrl+C`，随后检查端口是否释放。不要仅凭 screen 会话消失判断 Java 子进程已经退出。

## 7. 启动成功怎么确认

```bash
curl --fail http://127.0.0.1:18088/actuator/health
curl --fail -I http://127.0.0.1:5177/
```

后端应返回包含 `"status":"UP"` 的结果，前端应返回 HTTP 200。开发模式则把第二条地址改为 5176。

然后实际登录并进入工作台。**首页 200 不等于后端/API/登录可用。** Enterprise 页面应显示蓝灰主题、蓝色 3D Logo 与页签图标。需要精确确认时，可在浏览器控制台读取：

```javascript
document.documentElement.dataset.uiProfile // enterprise
document.querySelector('link[rel="icon"]')?.getAttribute('href')
// /logo/mateclaw-enterprise-3d-v1.png
```

这些检查不等于模型推理或工作流执行已验证；应在配置完成后单独做一次实际业务请求。

## 8. 修改后的验证命令

在 `mateclaw-ui` 目录执行：

```bash
pnpm exec vitest run
pnpm exec vue-tsc --noEmit
pnpm exec eslint src
pnpm exec vite build --mode enterprise --outDir dist/enterprise
```

当前 checkout 的 `pnpm build` 和 `pnpm lint` 脚本引用了缺失的 `../scripts/check-snowflake-precision.sh`，因此本文显式使用底层命令。这样可以验证 UI，但不代表缺失的精度检查已经执行；同步上游修复后再恢复标准入口。

2026-09-04 的验证基线：359 项测试通过；全仓 ESLint 有一条既有 `useAgentRunGroups.ts:185` 的 `prefer-const` 错误及既有警告。测试日志还可能打印既有连接重置信息，构建会提示部分大型依赖 chunk。应核对最新日志，不要把这些记录当作忽略新错误的白名单。

## 9. 常见问题

| 现象 | 检查与处理 |
|---|---|
| 仍是旧界面 | 核对 `--mode enterprise`、环境变量优先级和实际启动的产物目录；重建后刷新页面。 |
| 页签还是旧图标 | 核对 favicon 的实际 href；确认正在运行最新 enterprise 构建，再刷新或关闭重开页签。 |
| 页面打开但登录失败 / API 报 ECONNREFUSED | 检查 18088 后端是否监听；前端代理默认指向该端口。 |
| Port is already in use | 用 `lsof` 查明占用者；复用本项目实例，或显式换一个未占用的前端端口。不要结束其他项目。 |
| H2 文件被锁定 | 查找同一数据库的原后端进程，正常停止后再启动；不删除数据。 |
| 重启后数据看起来没了 | 核对后端工作目录与数据源 URL，确认没有误用新文件库或临时内存库。 |
| 对话输入框不可用 | 先配置模型提供商、凭证和当前模型，并检查当前用户权限。 |
| 缺少内部 Maven 依赖 | 从仓库根目录使用本文带 `-pl mateclaw-server -am` 的 reactor 构建。 |
| 修改 UI 后预览没变化 | 5177 是静态产物预览，不支持热更新；重新构建，或改用 5176 开发模式。 |

## 10. 后续扩展与主仓同步

新增视觉规则优先放入 `mateclaw-ui/src/styles/enterprise/`，并限制在 `html[data-ui-profile="enterprise"]` 下；复用现有 `--mc-*` / Element Plus 变量，不复制一套前端，不把 UI 修改混入业务 API 逻辑。

官方代码从 `upstream` 获取，定制代码推送到自己的 `origin` fork。同步前保存本地工作，审查并合入上游；重点核对 `main.ts`、`views/Login.vue`、`views/layout/MainLayout.vue` 三个接入点。每次同步后重新检查两种模式和核心业务流程。

更多细节：

- [UI 适配层与回退说明](docs/ui/enterprise-profile.md)
- [企业化演进方案](docs/superpowers/specs/2026-09-03-mateclaw-enterprise-ui-evolution.md)
- [实现计划](docs/superpowers/plans/2026-09-04-enterprise-ui.md)
- [验收记录及未验证范围](docs/ui/enterprise-verification-2026-09-04.md)
- [3D 图标来源与生成提示词](docs/ui/enterprise-icon.md)
- [原版中文 README](README_zh.md)
