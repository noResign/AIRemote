# airemote daemon 技术方案

> 面向维护者和后续接入客户端的开发者。代码在 `airemote-daemon/`。
> 本文档覆盖：整体设计、实现细节、HTTP/SSE 协议、权限模型、**全部配置参数**、构建与分发。

## 1. 概述

`airemote-daemon` 是一个跑在 macOS/Linux 本机的守护进程：它 `spawn` 本机已安装的编码
agent CLI（目前 Claude Code，架构上预留多 agent），以无头方式执行，把输出解析成统一的
流式事件，通过 HTTP/SSE 推给远程客户端（Android/iOS/网页），并支持会话续接、断线重连、
取消、工具审批、多 agent 并发。

核心定位：**「远程操纵本机 agent」**。因此安全边界（认证、权限审批、工作目录、默认拒绝）
是一等公民，而不是事后补丁。

**关键心智模型**：手机只是遥控器，任务在电脑上执行。**手机断连不影响任务**——daemon 继续
跑，重连后能续上。

## 2. 总体架构

```
远程客户端 (Android / iOS / web)
   │  HTTP(S) + Bearer token
   │  POST /api/chat              (发指令，返回 SSE)
   │  GET  /api/runs/:id/stream   (重连：回放 + 续直播)
   ▼
┌────────────────────────────────────────────────────────┐
│ daemon (Node 24 + Express + node:sqlite，零原生依赖)      │
│  ├─ auth.ts           Bearer token（常量时间比较）        │
│  ├─ db.ts             SQLite（会话/消息/运行/事件/审计）   │
│  ├─ permissions.ts    工具审批 + auto-allow + 超时拒绝    │
│  ├─ run-notifier.ts   runId → 多个 SSE 订阅者（fan-out）  │
│  └─ runtimes/engine.ts  spawn agent → 归一化事件 → SSE    │
└────────────────────────────────────────────────────────┘
   │ spawn(cli, [...], { cwd: session.cwd })
   ▼
本机编码 agent（Claude Code 无头 -p，stdin stream-json）
```

数据流：

```
用户发消息 → POST /api/chat
  → 解析会话 → 创建 run → spawn agent（cwd = workspace）
  → agent stdout → createParser → NormalizedEvent → 持久化(events) + SSE 推送 + fan-out
  → agent exit → 发 terminal status → res.end()
```

## 3. 运行时抽象（多 agent 扩展点）

`src/runtimes/types.ts` 定义 `RuntimeAdapter` 接口——接入新 agent（Codex、OpenCode、
DeepSeek Harness…）只实现它并在 `registry.ts` 注册一行，路由/引擎/持久化/传输全部不动：

| 成员 | 职责 |
|---|---|
| `id` / `name` / `bin` | 标识与可执行文件名 |
| `detect(env)` | 探测版本 / 认证 / 能力 flag（从 `--help` 解析） |
| `buildArgs(ctx)` | 拼 spawn 命令行（各 CLI argv 不同） |
| `createParser(onEvent)` | 把运行时私有 stdout 翻译成 `NormalizedEvent` |
| `encodeUserMessage(text)` | 把 prompt 编码成该运行时的 stdin 格式 |
| `keepStdinOpen` | stream-json 类运行时保持 stdin 打开以支持中途追加 |

通用 `engine.ts` 只负责运行时无关的部分：spawn、stdio 接线、`turn_end` 时关 stdin、
SIGTERM→SIGKILL 取消、退出码分类、**空闲看门狗**。当前唯一实现是 `runtimes/claude/`
（detect / stream / adapter）。

**事件流是 runtime 无关的**：客户端只认 `NormalizedEvent`，不感知底层是哪个 CLI。

## 4. 核心模块

| 文件 | 职责 |
|---|---|
| `index.ts` | 入口：加载 .env → 解析 CLI → loadConfig → 建 Db/registry → 探测 claude → startServer |
| `cli.ts` | `util.parseArgs` 解析 `airemote` 命令行，`--help`/`--version` |
| `config.ts` | env + CLI 合并（CLI 优先）；token 持久化；workspace/超时等默认值 |
| `server.ts` | Express 组合根：CORS → JSON → 鉴权门 → 各路由注册 |
| `routes/*.ts` | HTTP/SSE 边界（health/agent/chat/claude-sessions/runs/sessions/permissions） |
| `runtimes/engine.ts` | 通用 spawn 生命周期（见 §3） |
| `permissions.ts` | `PermissionManager`：pending 请求 + 超时默认拒绝 + auto-allow 规则 |
| `run-notifier.ts` | `RunNotifier`：runId → 该 run 的多个 SSE 订阅者（emitter + listeners） |
| `command-safety.ts` | Bash 只读命令白名单（只读自动放行，其余询问） |
| `workspace.ts` | `resolveWorkspaceCwd`：判定 cwd 是否在 workspace 内（目录本身或其子目录） |
| `permission-hook.ts` | PreToolUse hook 脚本（被 claude 调用，转发审批到 daemon） |
| `claude-sessions.ts` | 枚举 `~/.claude/projects/` 下的 Claude 会话 |
| `sse.ts` | SSE 帧写入 + `isTerminalEvent` 判断（chat 与 stream 复用） |
| `db.ts` | `node:sqlite` 持久化（内置，零原生依赖） |
| `types/api.ts` | 传输契约：`NormalizedEvent` 事件 union + DTO |

## 5. 数据模型（SQLite）

数据根默认 `~/.airemote`（`--data-dir` 可改），下面有 `airemote.sqlite` 与 `token`。

| 表 | 内容 |
|---|---|
| `sessions` | 会话：`id`、`runtime`、`claude_session_id`、`cwd`、`title`、时间戳 |
| `messages` | 对话转录：user prompt + assistant 聚合后的可见文本 |
| `runs` | 一次 spawn：`status`（running/succeeded/failed/cancelled）、`exit_code`、`error` |
| `events` | 归一化事件流，键 `(run_id, seq)`，用于断线重连回放 |
| `audit_log` | chat / cancel / permission_decision / rename_session / delete_session 审计 |

> `messages` 只是「聚合可见文本」；**工具卡、思考块、用量这些细节只存在 `events`**，
> 客户端重进会话时需用 `GET /api/runs/:id/events` 回放（Android 已实现）。

## 6. HTTP / SSE 协议

| 方法 & 路径 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/health` | 否 | 存活 + 版本 + workspace 根目录 |
| `GET /api/agent` | 是 | 探测 Claude Code（版本/认证/能力/models） |
| `GET /api/agents` | 是 | 已注册运行时列表 `{agents:[{id,name,bin}]}` |
| `GET /api/claude-sessions` | 是 | 列出 **workspace 内** 的 Claude 会话（可导入续接） |
| `POST /api/chat` | 是 | 发指令，返回 SSE 流 |
| `GET /api/runs` | 是 | 当前**运行中**的 run 列表（切换多 agent 用） |
| `POST /api/runs/:id/cancel` | 是 | 取消运行 |
| `GET /api/runs/:id/events` | 是 | 一次性回放 run 事件，`?after=<seq>` 续传游标 |
| `GET /api/runs/:id/stream` | 是 | SSE：先回放 `?after=<seq>` 后的事件，再续传直播（重连订阅） |
| `GET /api/sessions` | 是 | 会话列表（含 `running`/`runningRunId`） |
| `GET /api/sessions/:id` | 是 | 会话 + 消息 + 运行 |
| `PATCH /api/sessions/:id` | 是 | 重命名（body `{title}`） |
| `DELETE /api/sessions/:id` | 是 | 删除会话（先取消进行中的 run，级联删） |
| `POST /api/permissions/:id/decision` | 是 | 工具审批决定（allow/deny/allow_all） |
| `POST /api/internal/permissions/create` | 是 | 内部：hook 注册审批请求 |
| `GET /api/internal/permissions/:id/status` | 是 | 内部：hook 轮询决定 |

SSE 每帧 `{ runId, seq, event }`，`seq` 单调递增（重连游标）。`event` 是 `NormalizedEvent`：

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request | question
```

`POST /api/chat` 请求体：`{ prompt, sessionId?, claudeSessionId?, model?, runtime? }`。

- 新会话固定用 `--workspace` 作为 cwd；续接已有会话沿用会话存下来的 `cwd`，并**每次续接都
  重新校验**是否仍在当前 workspace 内，否则 400 `cwd_not_allowed`。

**断线续接**：`/api/chat` 客户端断开后，run **继续在 daemon 上运行**（不因断线取消）；重连
用 `GET /api/runs` 找运行中的 run，再 `GET /api/runs/:id/stream?after=<seq>` 回放 + 续直播；
显式停止用 `POST /api/runs/:id/cancel`。多 run 并发无全局锁。

## 7. 权限审批

`--permission-mode acceptEdits` 默认自动放行 Read/Write/Edit；通过 `--settings` 注入
**PreToolUse hook**（`matcher: "Bash"`），只对 Bash 做远程审批：

```
claude 要跑 Bash → hook(permission-hook.js) → POST /api/internal/permissions/create
  → daemon 广播 permission_request（SSE）→ 客户端决定 → POST .../decision
  → hook 轮询 status → permissionDecision allow/deny → claude 放行/阻止
```

审批策略（`routes/permissions.ts` + `command-safety.ts`）：

1. **auto-allow**（用户点过「允许全部 Bash」）→ 直接放行，不再广播；
2. **只读 Bash 白名单**（`ls`/`cat`/`grep`/`node --version`/`git status`…，且不含 shell
   元字符 `| > & ; $()` 等）→ 自动放行；
3. 其余（写入/删除/未知）→ 广播给客户端，弹「允许 / 拒绝 / 允许全部 Bash」；
4. **超时（120 秒）或断线 → 默认拒绝**（deny-by-default）。

## 8. 会话与跨端续接

- Claude Code 的所有会话（TUI 与 headless）统一存 `~/.claude/projects/<cwd>/<id>.jsonl`，
  会话 id 就是文件名；`claude --resume <id>` 续接。
- daemon 自己生成 `--session-id`，续接用 `--resume`，并把 `claude_session_id` 存进
  `sessions` 表。
- `GET /api/claude-sessions` 枚举**workspace 内**的会话，客户端可「导入」一个 TUI 会话继续
  （`POST /api/chat` 带 `claudeSessionId`）。
- 双向互通：手机会话 → 电脑 `claude --resume <id>`；电脑 TUI 会话 → 手机导入继续。

## 9. 安全模型

- **认证**：非 health 的 `/api/*` 全要 Bearer token（`auth.ts` 常量时间比较）。
- **传输**：默认 HTTP 绑 `0.0.0.0`（局域网）；生产建议 `AIREMOTE_TLS_CERT/KEY` 或反代/SSH 隧道。
- **权限**：acceptEdits + Bash 远程审批 + 只读白名单 + 默认拒绝（见 §7）。
- **工作目录**：单一 `--workspace` 根目录；新会话的 cwd、续接会话的存量 cwd、导入 Claude 会话
  的 cwd 都要落在 workspace 内（目录本身或其子目录），否则 400 `cwd_not_allowed`。
- **防御性超时**：run 空闲看门狗（`AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`，默认 900 秒、0=禁用）。
- **审计**：chat / cancel / permission_decision / rename_session / delete_session 记入 `audit_log`。
- **CORS**：`Access-Control-Allow-Origin: *` 仅为让网页客户端可用；真正边界是 token。

### ⚠️ 重要边界：`workspace` 不是沙箱

`workspace` 只决定 **Claude 进程从哪个目录启动（spawn cwd）**，**不是文件系统沙箱**。
Claude 启动后，访问别的目录由「工具权限」决定，与 workspace 无关：

| 工具 | 行为 |
|---|---|
| Read / Write / Edit | acceptEdits 下**直接放行**，可读写任意路径（含 workspace 外） |
| Bash 只读（`cat`/`ls`/…） | 自动放行，不看目录 |
| Bash 有副作用 | 弹审批（审批的是命令本身，不专门按目录拦截） |

要做到文件级物理隔离，需要 OS 层沙箱（bwrap / firejail / 容器 / sandbox-exec），纯靠 Claude
Code CLI 做不到。`workspace` 的准确语义是「**从哪个目录启动**」，不是「只能碰哪些目录」。

## 10. 配置参数参考

优先级：**命令行 flag > 已存在的 shell 环境变量 > `.env` 文件 > 默认值**。
所有变量都可用 `--env-file <path>` 指定 `.env` 加载（默认 `./.env`）。

### 10.1 命令行 flag

| flag | 默认 | 说明 |
|---|---|---|
| `--host <host>` | `0.0.0.0` | 监听地址；`127.0.0.1` = 仅本机 |
| `--port <port>` | `4780` | 端口（1–65535） |
| `--workspace <path>` | 当前目录 | 工作空间根目录（agent 只在其下、含子目录，干活） |
| `--data-dir <path>` | `~/.airemote` | 数据根（SQLite + token） |
| `--token <token>` | 自动生成并持久化 | Bearer 鉴权密钥 |
| `--permission-mode <mode>` | `acceptEdits` | 权限模式（见下） |
| `--env-file <path>` | `./.env` | `.env` 文件路径 |
| `-h, --help` | — | 帮助 |
| `-v, --version` | — | 版本 |

### 10.2 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `AIREMOTE_HOST` | `0.0.0.0` | 监听地址 |
| `AIREMOTE_PORT` | `4780` | 端口 |
| `AIREMOTE_WORKSPACE` | 当前目录 | 工作空间根目录 |
| `AIREMOTE_DATA_DIR` | `~/.airemote` | 数据根 |
| `AIREMOTE_TOKEN` | 自动生成 | 鉴权密钥 |
| `AIREMOTE_PERMISSION_MODE` | `acceptEdits` | 权限模式 |
| `AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS` | `900` | 空闲看门狗（无事件多少秒自动取消；`0` = 禁用） |
| `AIREMOTE_TLS_CERT` | 无 | TLS 证书路径（与 KEY 同设才启用 TLS） |
| `AIREMOTE_TLS_KEY` | 无 | TLS 私钥路径 |
| `AIREMOTE_ENV_FILE` | `./.env` | `.env` 路径（等价 `--env-file`） |

### 10.3 权限模式（`--permission-mode`）

| 值 | 含义 |
|---|---|
| `default` | 只读保守（不自动改文件） |
| `acceptEdits` | **默认**。允许读/写/编辑文件，仅 Bash 走远程审批 |
| `plan` | 计划模式（只规划不动手） |
| `bypassPermissions` | 完全放开（⚠️ 危险，远程使用强烈不建议） |

### 10.4 token 解析顺序

1. `--token` flag 或 `AIREMOTE_TOKEN` env → 直接使用；
2. 否则读 `<data-dir>/token`（存在则复用，保证重启不变）；
3. 否则生成 64 位 hex（32 字节随机），写入 `<data-dir>/token`（权限 0600）。

删掉 `<data-dir>/token` 即轮换 token。

### 10.5 `.env` 示例

```ini
# 参考 airemote-daemon/.env.example
AIREMOTE_HOST=0.0.0.0
AIREMOTE_PORT=4780
AIREMOTE_WORKSPACE=/home/renbin/OpenProject/AIRemote
AIREMOTE_PERMISSION_MODE=acceptEdits
AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS=900
# AIREMOTE_TOKEN=
# AIREMOTE_TLS_CERT=/path/cert.pem
# AIREMOTE_TLS_KEY=/path/key.pem
```

## 11. 目录结构

```
airemote-daemon/
├─ src/
│  ├─ index.ts           入口（.env → CLI → config → server）
│  ├─ server.ts          Express 组合
│  ├─ config.ts / cli.ts / log.ts / network.ts / cors.ts / auth.ts / sse.ts / workspace.ts
│  ├─ db.ts              SQLite 持久化
│  ├─ permissions.ts     权限注册表
│  ├─ run-notifier.ts    runId → 多 SSE 订阅者
│  ├─ command-safety.ts  只读命令白名单
│  ├─ permission-hook.ts PreToolUse hook 脚本（→ dist/permission-hook.js）
│  ├─ claude-sessions.ts 枚举本机 Claude 会话
│  ├─ session-title.ts   从首条 prompt 生成会话标题
│  ├─ types/api.ts       传输契约
│  ├─ routes/            HTTP/SSE 边界
│  └─ runtimes/          运行时抽象 + claude 适配器 + 通用 engine
├─ client/index.html     极简测试网页客户端（零依赖）
├─ tests/                Vitest 单测（stream / permissions / command-safety / workspace / session-title）
├─ docs/daemon.md        本文件
├─ .env.example          配置模板
├─ package.json / tsconfig.json / vitest.config.ts
└─ dist/                 构建产物（`airemote` bin 指向 dist/index.js）
```

## 12. 构建 / 运行 / 分发

```bash
cd airemote-daemon
pnpm install
pnpm build          # tsc + chmod → dist/index.js（含 permission-hook.js）
pnpm typecheck
pnpm test

# 全局命令（一次性）
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote
airemote --help
```

**打包分发**（产出 npm 包，对方需 Node ~24 + Claude Code）：

```bash
pnpm pack                 # 自动先 build，产出 airemote-0.1.0.tgz（仅 dist + package.json）
# 对方机器：
npm install -g airemote-0.1.0.tgz
airemote --help
```

`.env` 配置（运行目录下自动加载）：

```bash
cp .env.example .env && 编辑 .env
airemote
```

## 13. 已知限制 / 后续

- 只读 Bash 白名单是内置默认，可考虑做成配置文件（`.airemote/policy.json`）可增减。
- auto-allow（「允许全部 Bash」）当前是 **run 级**（一次对话内），不是 session 级。
- 权限审批依赖 Claude Code 的 PreToolUse hook 格式，需随 CLI 版本演进同步探测/适配。
- **`workspace` 不是沙箱**（见 §9）——文件级隔离需 OS 层沙箱。
- 会话列表无「最后一条消息预览」字段（客户端暂用标题/时间/运行态）。
- Android 客户端已实现 M1；iOS 预留。传输契约（`types/api.ts`）已冻结，两端据此接入。
