# AIRemote

**远程操纵本机编码 agent。** 电脑上跑一个 daemon，把本机安装的编码 agent CLI（当前是
Claude Code）以无头方式 spawn 起来，输出解析成统一的流式事件，通过 HTTP/SSE 推给远程客户端
（Android / iOS / 网页），支持会话续接、断线重连、取消、工具审批与多 agent 并发。

手机只是遥控器，**任务始终在电脑上执行**——手机断连不影响任务，重连后能续上看。

> [English](README.en.md) | 中文

> ⚠️ 远程驱动一个带 shell 权限的 agent 本质等于**远程代码执行**。认证、权限审批、工作目录、
> 默认拒绝是本项目的核心设计目标，不是附加项。使用前请先读[安全模型](#安全模型-)。

## 核心特性

| 能力 | 说明 |
|---|---|
| 统一事件流 | 把各 CLI 私有 stdout 归一化成 runtime 无关的 `NormalizedEvent`，客户端不感知底层是哪个 agent |
| 断线续传 | run 与连接解耦：客户端断开后 run 继续跑，事件按 `(run_id, seq)` 持久化，重连按 `?after=<seq>` 回放 + 续直播 |
| 多 run 并发 | 多个 Session / 多个 run 并行，无全局锁 |
| 工具审批 | Session 级 `ask` / `acceptEdits` / `bypass`；PreToolUse hook 把 Bash/Write/Edit/MCP 调用转发到手机，**超时或断线默认拒绝** |
| 只读白名单 | `ls` / `cat` / `git status` 等只读 Bash（且无 shell 元字符）自动放行，不打扰用户 |
| 「允许全部」 | Session + 工具级持久化授权，可撤销；MCP 工具按 `mcp__<server>__*` 归并 |
| Workspace | 手机可新增 / 切换工作区；Session 绑定 `workspace_id + cwd`，cwd 必须落在所属 Workspace 内 |
| 双向续接 | 电脑 TUI 开的 Claude 会话可导入手机继续；手机的会话也能用 `claude --resume <id>` 在电脑接续 |
| Files Tab | 手机上看工作区的 Git 未提交改动、单文件 diff、目录树与文本文件内容 |
| 应用内更新 | Android 自带 `:lib-updater`，alpha / prod 双通道，从 OSS 拉 manifest 自更新 |
| 远程部署 | 手机可直接触发发布（`POST /api/deploy`，默认关闭，需显式开启） |
| 多 runtime | 新增 agent = 实现 `RuntimeAdapter` + 注册一行，路由 / 引擎 / 持久化 / 传输不动 |

## 架构

```
┌───────────────┐   HTTP(S) + Bearer token    ┌────────────────────────────┐
│ Android / iOS │  POST /api/chat      (SSE)  │       airemote-daemon      │
│    / 网页      │ ──────────────────────────▶ │  Node 24 + Express         │
│    遥控器      │  GET  /api/runs/:id/stream  │  + node:sqlite（零原生依赖）│
└───────────────┘ ◀────────────────────────── └─────────────┬──────────────┘
                                                             │ spawn(cli, …, {cwd})
                                                             ▼
                                                本机编码 agent（Claude Code 无头）
```

数据流：

```
用户发消息 → POST /api/chat → 创建 run → spawn agent（cwd = session.cwd）
  → agent stdout → parser → NormalizedEvent → 持久化(events) + SSE 推送 + fan-out
  → agent exit → terminal status → res.end()
```

## 目录结构

```
AIRemote/
├─ airemote-daemon/        daemon（Node 24 + Express + SQLite）
│  ├─ src/runtimes/        运行时抽象（types / engine / registry / claude 适配器）
│  ├─ src/routes/          HTTP / SSE 边界
│  ├─ src/types/api.ts     跨端传输契约（唯一真源）
│  ├─ client/index.html    极简测试网页客户端（零依赖）
│  ├─ tests/               Vitest 单测
│  └─ docs/daemon.md       daemon 技术方案
├─ airemote-android/       Android 客户端（Kotlin + Compose）
│  ├─ app/                 业务上层（MVVM + UI + 仓库编排）
│  ├─ lib-network/         网络底座（DTO / Retrofit / SSE / LLM 抽象）
│  ├─ lib-updater/         应用内自更新 SDK
│  └─ docs/                updater.md / ui_adapter.md
├─ airemote-ios/           iOS 客户端（预留，原生 SwiftUI）
├─ docs/                   跨端文档（ui_design.md 为 UI 单一设计源）
└─ .claude/skills/deploy/  发布 / 部署脚本
```

## 快速开始

### 1. 启动 daemon

前置：**Node `~24`**、**pnpm 10**、本机已装并登录 `claude` CLI（`claude auth login`）。

```bash
cd airemote-daemon
pnpm install
pnpm build
node dist/index.js --workspace ~/code/my-project
```

首次启动会生成 token 并打印，同时打印本机局域网地址（供手机连接）：

```
token: 3f9c…（也持久化在 ~/.airemote/token，删掉即轮换）
LAN:   http://192.168.1.20:4780
```

常用参数：

```bash
airemote --workspace ~/code/proj --port 4780 --permission-mode acceptEdits
airemote --host 127.0.0.1        # 仅本机可访问
airemote --help
```

> 开发时可用 `pnpm dev`（tsx watch）。想全局安装成 `airemote` 命令：
> `ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote`

### 2. 装 Android 客户端

用 Android Studio 打开 `airemote-android/`（JDK 17，`minSdk 24` / `targetSdk 36`），
选择 flavor 后直接 Run：

- `alphaDebug` —— 测试通道（applicationId `com.airemote.airemote.alpha`）
- `prodDebug` —— 正式通道

正式签名需要 `airemote-android/keystore.properties`（从 `keystore.properties.example`
复制填写，该文件不入库）；没有它时只能跑未签名的本地调试包。
命令行构建等价于：

```bash
cd airemote-android
./gradlew :app:assembleAlphaDebug
```

打开 App → 填 daemon 地址和 token → 连接。之后即可在手机上新建/续接会话、看流式输出、
审批工具调用、浏览文件、管理 Workspace。

### 3. 网页测试客户端（可选）

`airemote-daemon/client/index.html` 是个零依赖的单文件客户端（直接用浏览器打开），
填入 daemon 地址与 token 就能发 prompt、看事件流、应答审批——排查问题很方便。

### 4. curl 冒烟

```bash
TOKEN=$(cat ~/.airemote/token)
curl -s http://127.0.0.1:4780/api/health
curl -N -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"列出当前目录的文件并总结"}' http://127.0.0.1:4780/api/chat
```

## 配置

优先级：**命令行 flag > shell 环境变量 > `.env` 文件 > 默认值**。

| flag | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `--host` | `AIREMOTE_HOST` | `0.0.0.0` | 监听地址（`127.0.0.1` = 仅本机） |
| `--port` | `AIREMOTE_PORT` | `4780` | 端口 |
| `--workspace` | `AIREMOTE_WORKSPACE` | 当前目录 | 初始 Workspace 根目录 |
| `--data-dir` | `AIREMOTE_DATA_DIR` | `~/.airemote` | SQLite + token |
| `--token` | `AIREMOTE_TOKEN` | 自动生成 | Bearer 鉴权密钥 |
| `--permission-mode` | `AIREMOTE_PERMISSION_MODE` | `default`(=ask) | 新 Session 权限模式 seed |
| `--env-file` | `AIREMOTE_ENV_FILE` | `./.env` | `.env` 路径 |

其他常用变量：`AIREMOTE_PERMISSION_TIMEOUT_SECONDS`（审批超时，默认 120）、
`AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`（空闲看门狗，默认 900，0=禁用）、
`AIREMOTE_TLS_CERT` / `AIREMOTE_TLS_KEY`（同时设置才启用 HTTPS）、
`AIREMOTE_ALLOW_DEPLOY`（允许手机触发部署，默认关闭）。

完整清单见 `airemote-daemon/.env.example` 与 `airemote-daemon/docs/daemon.md` §10。

## API 速览

非 `/api/health` 的所有路由都要 `Authorization: Bearer <token>`。

| 方法 & 路径 | 说明 |
|---|---|
| `GET /api/health` | 存活 + 版本（唯一免鉴权） |
| `POST /api/chat` | 发指令，返回 SSE 流；断线后 run 继续跑 |
| `GET /api/runs/:id/stream?after=` | 重连：回放 + 续直播 |
| `GET /api/runs/:id/events` | 一次性回放 run 事件 |
| `POST /api/runs/:id/cancel` | 取消运行 |
| `POST /api/permissions/:id/decision` | 审批决定（allow / deny / allow_all） |
| `GET/PATCH /api/sessions/:id/permissions` | Session 权限模式与已授权工具 |
| `GET/POST/PATCH/DELETE /api/workspaces` | 工作区管理 |
| `GET /api/fs/directories` | 目录选择器 |
| `GET /api/changes`、`/api/changes/diff` | Git 未提交改动与单文件 diff |
| `GET /api/files`、`/api/files/content` | 目录懒加载与文本文件内容 |
| `GET /api/claude-sessions` | 枚举 workspace 内的 Claude 会话（可导入续接） |
| `POST /api/deploy` | 触发发布（默认关闭） |

SSE 每帧为 `{ runId, seq, event }`，`event` 是 `NormalizedEvent`：

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request | question
```

完整端点表、请求体与错误形状见 `airemote-daemon/docs/daemon.md` §6，
跨端传输契约的唯一真源是 `airemote-daemon/src/types/api.ts`。

## 安全模型 ⚠️

**`workspace` 不是沙箱。** 它只决定 agent 从哪个目录启动（spawn cwd），不限制它能碰哪些路径
——`Read`/`Write`/`Edit` 在 `acceptEdits` 下可直接读写 workspace 之外的文件。文件级隔离需要
OS 层沙箱（bwrap / firejail / 容器），纯靠 CLI 做不到。

daemon 提供的边界：

- **认证**：所有非 health 的 `/api/*` 都要 Bearer token（常量时间比较）
- **权限模式**：默认 `ask`，`bypass` 必须用户在手机上明确切换
- **默认拒绝**：审批超时或客户端断线 → 拒绝
- **只读白名单**：仅放行无 shell 元字符的只读 Bash
- **审计**：chat / cancel / permission_decision / rename / delete 全部记入 `audit_log`

公开网络上使用请务必套 HTTPS（`AIREMOTE_TLS_*` 或反代）或 SSH 隧道，并保管好 token。
只想本机使用就 `--host 127.0.0.1`。

## 部署与发布

统一入口 `.claude/skills/deploy/scripts/deploy.sh`，按 git 变更自动决定要做什么：

```bash
deploy.sh test          # alpha 通道：本地编译 / 重启 daemon，不发 OSS
deploy.sh prod          # 生产通道：额外把 daemon 包发 OSS，Android 发 android/prod
deploy.sh test --dry-run
```

- daemon：`pnpm build` + 重启（有 systemd user service 则 `systemctl --user restart airemote`），
  prod 额外 `pnpm pack` 上传 OSS `daemon/airemote-<version>.tgz` 并覆盖 `airemote-latest.tgz`
- Android：`release-android.sh alpha|prod`，构建签名后上传 `.apk` + `manifest.json` 到 OSS

版本号手改递增（语义化：修 bug 加 patch / 加功能加 minor / 大改加 major）：

- daemon：`airemote-daemon/package.json` 的 `version`
- Android：`airemote-android/version.txt`

两者当前均为 `1.2.0`。

## 文档

| 文档 | 内容 |
|---|---|
| `CLAUDE.md` | 纲领性入口：项目边界、开发规范、关键设计决策 |
| `airemote-daemon/docs/daemon.md` | daemon 完整技术方案：架构 / 协议 / 权限 / 数据模型 / 配置 |
| `docs/ui_design.md` | 移动端 UI 设计规范（Android / iOS 通用，单一设计源） |
| `docs/permission_workspace_design.md` | 权限与工作区模型设计 |
| `docs/files_tab_design.md` | Files Tab 产品技术方案 |
| `docs/chat_history_pagination.md` | 聊天历史分页加载方案（待实施） |
| `airemote-android/docs/updater.md` | 应用内自更新 SDK 与版本号约定 |

## 状态

- **daemon**：可用（会话 / 审批 / 工作区 / 文件 / 部署 / 远程发布均已落地）
- **Android**：M1 已实现（连接、会话列表、聊天、审批、新建会话、设置、文件、Workspace 管理、自更新）
- **iOS**：预留，尚未实现；传输契约已冻结，两端据此接入
- **多 runtime**：抽象已就位，当前唯一实现是 Claude Code

## License

Apache-2.0，见 [LICENSE](LICENSE)。
